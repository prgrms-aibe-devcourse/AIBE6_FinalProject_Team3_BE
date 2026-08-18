package com.algogyeyak.user.service;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.pagination.PageableUtils;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.user.dto.AdminUserDetailResponse;
import com.algogyeyak.user.dto.AdminUserListItemResponse;
import com.algogyeyak.user.dto.UserSearchCondition;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt", "nickname", "email");

    private final UserRepository userRepository;
    private final AdminAuditLogger adminAuditLogger;

    @Transactional(readOnly = true)
    public PageResponse<AdminUserListItemResponse> list(Pageable pageable, UserSearchCondition condition) {
        PageableUtils.validateSort(pageable, ALLOWED_SORT_PROPERTIES);
        PageableUtils.validateMaxSize(pageable);

        Page<User> page = userRepository.search(
                escapeLikePattern(condition.email()), escapeLikePattern(condition.nickname()),
                condition.role(), condition.status(), pageable);
        return PageResponse.from(page, AdminUserListItemResponse::from);
    }

    /**
     * role 변경은 User.changeRole()로 엔티티 필드를 바꾸고 커밋 시점 dirty-checking에 맡기는 대신,
     * UserRepository.updateRoleIfNotWithdrawn()으로 조건부 UPDATE를 직접 실행한다 - 그래야 "이
     * 메서드가 대상을 읽은 시점엔 활성 상태였지만, 실제 UPDATE 시점엔 이미 본인 탈퇴가 커밋된"
     * 레이스에서 그 탈퇴를 무시하고 role을 덮어쓰는 걸 막을 수 있다(UserRepository 참고). 영향받은
     * row가 0건이면 원인은 "findUser()가 확인한 뒤 그 사이에 탈퇴함" 하나뿐이므로 안전하게 단정해
     * 에러로 변환한다. user 엔티티의 role 필드 자체는 절대 건드리지 않는다 - 건드리면 이 엔티티가
     * dirty로 남아 커밋 시점에 조건 없는 UPDATE가 한 번 더 나가 방금 막은 레이스가 되살아난다.
     */
    @Transactional
    public AdminUserDetailResponse updateRole(Long actorId, Long userId, Role role) {
        rejectSelf(actorId, userId);
        User user = findUser(userId);
        if (role != Role.ADMIN) {
            rejectIfLastActiveAdmin(user);
        }
        Role previousRole = user.getRole();

        int updated = userRepository.updateRoleIfNotWithdrawn(userId, role, UserStatus.WITHDRAWN);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_ROLE_TRANSITION, "탈퇴한 사용자는 권한을 변경할 수 없습니다.");
        }

        adminAuditLogger.log(actorId, AdminAuditAction.UPDATE_ROLE, userId,
                Map.of("beforeRole", previousRole, "afterRole", role));
        return AdminUserDetailResponse.from(user, role);
    }

    // status는 컨트롤러 DTO 단계에서 이미 ACTIVE/SUSPENDED로 제한되어 있다. 탈퇴 유저에 대한 거부는
    // updateRole()과 동일한 이유로 조건부 UPDATE(updateStatusIfNotWithdrawn)의 영향받은 row 수로
    // 판단한다 - User.suspend()/activate()를 더 이상 호출하지 않는다(이유는 updateRole() 참고).
    @Transactional
    public AdminUserDetailResponse updateStatus(Long actorId, Long userId, UserStatus status) {
        rejectSelf(actorId, userId);
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "ACTIVE/SUSPENDED로만 변경할 수 있습니다.");
        }

        User user = findUser(userId);
        UserStatus previousStatus = user.getStatus();
        if (status == UserStatus.SUSPENDED) {
            rejectIfLastActiveAdmin(user);
        }

        int updated = userRepository.updateStatusIfNotWithdrawn(userId, status, UserStatus.WITHDRAWN);
        if (updated == 0) {
            String message = status == UserStatus.SUSPENDED
                    ? "탈퇴한 사용자는 정지할 수 없습니다."
                    : "탈퇴한 사용자는 활성화할 수 없습니다.";
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, message);
        }

        adminAuditLogger.log(actorId, AdminAuditAction.UPDATE_STATUS, userId,
                Map.of("beforeStatus", previousStatus, "afterStatus", status));
        return AdminUserDetailResponse.from(user, status);
    }

    /**
     * 여러 유저를 한 번에 정지/정지해제한다. 대상 하나하나가 이미 updateStatus()의 가드(자기 자신
     * 변경 금지, 마지막 활성 관리자 보호, 탈퇴 유저 제외)를 그대로 적용받으므로, 이건 원자적
     * 전체성공-전체실패가 아니라 항목별 성공/실패가 갈리는 배치 처리다 - 하나가 가드에 막혀도
     * 나머지는 계속 처리하고, 실패한 항목과 사유를 그대로 응답에 담아 돌려준다. updateStatus() 호출은
     * 같은 인스턴스 안에서의 일반 메서드 호출(self-invocation)이라 별도 트랜잭션을 열지 않고 이
     * 메서드의 트랜잭션 안에서 실행되며, 여기서 잡아낸 예외는 그 트랜잭션을 롤백시키지 않는다.
     * 입력에 같은 id가 중복되면(프론트는 Set이라 안 만들지만 API 호출로는 가능) 먼저 처리된 id가
     * 두 번째 시도에서 상태 전이 실패로 다시 걸려 같은 id가 성공/실패 양쪽에 나타날 수 있다 -
     * 순서를 보존한 채 중복만 제거해 이 문제를 없앤다.
     */
    @Transactional
    public AdminBulkActionResponse bulkUpdateStatus(Long actorId, List<Long> userIds, UserStatus status) {
        List<Long> succeededIds = new ArrayList<>();
        List<AdminBulkActionResponse.Failure> failures = new ArrayList<>();
        for (Long userId : new LinkedHashSet<>(userIds)) {
            try {
                // 자기 자신 제외 가드는 updateStatus() 자신이 rejectSelf()로 이미 강제한다 -
                // 여기서 별도로 다시 확인할 필요 없다(예전엔 이 가드가 컨트롤러/이 메서드 두 곳에
                // 손으로 복붙돼 있어, updateStatus()를 직접 호출하는 미래의 다른 호출부가 생기면
                // 조용히 건너뛸 위험이 있었다).
                updateStatus(actorId, userId, status);
                succeededIds.add(userId);
            } catch (BusinessException e) {
                failures.add(new AdminBulkActionResponse.Failure(userId, e.getMessage()));
            } catch (RuntimeException e) {
                // rejectIfLastActiveAdmin()의 비관적 락 대기 타임아웃, updateStatusIfNotWithdrawn()의
                // @Modifying UPDATE 등에서 BusinessException이 아닌 예외(DataAccessException 계열)가
                // 날 수 있다. 여기서 잡지 않으면 이 예외가 트랜잭션 프록시 경계(bulkUpdateStatus 자신)를
                // 벗어나 전체 트랜잭션이 롤백되고, 이미 처리된 앞 항목들까지 함께 취소된다 - 항목별
                // 성공/실패가 갈리는 배치라는 이 메서드의 설계 의도(위 클래스 주석 참고)를 예상 못한
                // 예외 타입 때문에 잃지 않도록 여기서 흡수하고 일반화된 메시지로 실패 처리한다.
                log.warn("일괄 상태 변경 중 예상치 못한 오류 (userId={})", userId, e);
                failures.add(new AdminBulkActionResponse.Failure(userId, "처리 중 오류가 발생했습니다."));
            }
        }
        return new AdminBulkActionResponse(succeededIds, failures);
    }

    /**
     * 관리자 화면에서 마지막 남은 ADMIN 계정을 강등/정지시키면, 그 순간부터 아무도 /admin/**에
     * 접근할 수 없게 된다. AdminAccountSeeder는 이미 존재하는 계정을 절대 다시 승격/치료하지
     * 않기로 되어 있어(기존 계정을 건드리지 않는다는 결정 참고) 앱 안에서 되돌릴 방법이 없다.
     *
     * findAllByRoleAndStatusForUpdate로 대상 행에 PESSIMISTIC_WRITE를 걸어 원자적으로 만들었다 -
     * 서로 다른 관리자 두 명이 동시에 서로를 강등/정지시켜도, 먼저 이 메서드에 들어온 트랜잭션이
     * 커밋될 때까지 나중 트랜잭션은 락 대기 상태가 되고, 재개된 뒤에는 이미 강등된 관리자가 빠진
     * 최신 카운트를 보게 되어 "마지막 남은 관리자"를 정확히 감지한다. updateRole/updateStatus가
     * 이미 @Transactional이라 이 락은 메서드가 리턴할 때(실제 강등/정지 UPDATE까지 커밋된 뒤)
     * 풀린다.
     */
    // 관리자가 실수로 자기 자신의 권한을 강등하거나 자기 자신을 정지시켜 스스로를 잠그는 사고를
    // 막는다. updateRole()/updateStatus() 자신에 있어야 한다 - 컨트롤러에만 있으면 이 서비스를
    // 직접 호출하는 다른 호출부(다른 컨트롤러, 내부 도구, 스케줄 작업 등)가 생겼을 때 조용히
    // 우회될 수 있다.
    private void rejectSelf(Long actorId, Long targetUserId) {
        if (actorId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "자기 자신의 권한/상태는 변경할 수 없습니다.");
        }
    }

    private void rejectIfLastActiveAdmin(User user) {
        if (user.getRole() != Role.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        if (userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE).size() <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT);
        }
    }

    // UserRepository.search()의 LIKE 검색은 파라미터를 그대로 CONCAT('%', :x, '%')에 넣는다 -
    // 완전히 파라미터화돼 있어 SQL 인젝션 위험은 없지만, 검색어에 리터럴 %나 _가 들어있으면 그
    // 문자 자체가 SQL LIKE 와일드카드로 해석돼 관리자가 의도한 것보다 훨씬 넓게 매칭된다(예:
    // "test_user"로 검색하면 "_"가 임의의 한 글자와 매칭돼 "testXuser" 같은 계정도 걸린다).
    // 역슬래시 자신부터 먼저 이스케이프해야 한다 - 순서를 바꾸면 방금 넣은 이스케이프용 역슬래시가
    // 다시 이스케이프된다.
    private static String escapeLikePattern(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_NOT_FOUND));
    }
}
