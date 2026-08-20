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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class AdminUserService {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt", "nickname", "email");

    private final UserRepository userRepository;
    private final AdminAuditLogger adminAuditLogger;
    // bulkUpdateStatus()가 항목별로 이 템플릿을 통해 updateStatus()를 REQUIRES_NEW로 감싼다 -
    // self-invocation(this.updateStatus(...))은 @Transactional 프록시를 우회하므로, 그냥
    // 호출하면 전부 bulkUpdateStatus() 자신의 트랜잭션 하나를 공유하게 된다. 그 상태에서
    // updateStatus() 도중(특히 AdminAuditLogger.log() 이후) 예외가 나면, 이미 실행된
    // UPDATE는 (예외가 이 메서드 밖으로 전파되지 않는 한) 그대로 커밋되면서도 응답에는 실패로
    // 기록되는 모순이 생긴다 - AdminAuditLogger의 "감사 로그 실패 시 실제 변경도 함께 롤백"
    // 정책이 벌크 경로에서만 조용히 깨지는 것이다. TransactionTemplate으로 항목마다 진짜 새
    // 물리 트랜잭션을 열면, 그 항목에서 어떤 예외가 나든 그 항목의 변경만 롤백되고 이미
    // 커밋된 앞 항목들은 그대로 남는다.
    private final TransactionTemplate requiresNewTransactionTemplate;

    public AdminUserService(
            UserRepository userRepository, AdminAuditLogger adminAuditLogger, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.adminAuditLogger = adminAuditLogger;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
    public AdminUserDetailResponse updateRole(Long actorId, String actorEmail, Long userId, Role role) {
        rejectSelf(actorId, userId);
        User user = findUser(userId);
        if (role != Role.ADMIN) {
            rejectIfLastActiveAdmin(user);
        }
        Role previousRole = user.getRole();

        // JPQL bulk UPDATE라 @LastModifiedDate가 관여하지 않는다 - UPDATE 쿼리와 응답이 같은
        // 값을 보도록 여기서 한 번만 계산해 그대로 넘긴다(AdminUserDetailResponse.from 참고).
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = userRepository.updateRoleIfNotWithdrawn(userId, role, UserStatus.WITHDRAWN, updatedAt);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_ROLE_TRANSITION, "탈퇴한 사용자는 권한을 변경할 수 없습니다.");
        }

        adminAuditLogger.log(actorId, actorEmail, AdminAuditAction.UPDATE_ROLE, userId,
                Map.of("beforeRole", previousRole, "afterRole", role));
        return AdminUserDetailResponse.from(user, role, updatedAt);
    }

    // status는 컨트롤러 DTO 단계에서 이미 ACTIVE/SUSPENDED로 제한되어 있다. 탈퇴 유저에 대한 거부는
    // updateRole()과 동일한 이유로 조건부 UPDATE(updateStatusIfNotWithdrawn)의 영향받은 row 수로
    // 판단한다 - User.suspend()/activate()를 더 이상 호출하지 않는다(이유는 updateRole() 참고).
    @Transactional
    public AdminUserDetailResponse updateStatus(Long actorId, String actorEmail, Long userId, UserStatus status) {
        rejectSelf(actorId, userId);
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "ACTIVE/SUSPENDED로만 변경할 수 있습니다.");
        }

        User user = findUser(userId);
        UserStatus previousStatus = user.getStatus();
        if (status == UserStatus.SUSPENDED) {
            rejectIfLastActiveAdmin(user);
        }

        // updateRole()과 동일한 이유로, UPDATE 쿼리와 응답이 같은 updatedAt 값을 보도록 한 번만
        // 계산해 그대로 넘긴다.
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = userRepository.updateStatusIfNotWithdrawn(userId, status, UserStatus.WITHDRAWN, updatedAt);
        if (updated == 0) {
            String message = status == UserStatus.SUSPENDED
                    ? "탈퇴한 사용자는 정지할 수 없습니다."
                    : "탈퇴한 사용자는 활성화할 수 없습니다.";
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, message);
        }

        adminAuditLogger.log(actorId, actorEmail, AdminAuditAction.UPDATE_STATUS, userId,
                Map.of("beforeStatus", previousStatus, "afterStatus", status));
        return AdminUserDetailResponse.from(user, status, updatedAt);
    }

    /**
     * 여러 유저를 한 번에 정지/정지해제한다. 대상 하나하나가 이미 updateStatus()의 가드(자기 자신
     * 변경 금지, 마지막 활성 관리자 보호, 탈퇴 유저 제외)를 그대로 적용받으므로, 이건 원자적
     * 전체성공-전체실패가 아니라 항목별 성공/실패가 갈리는 배치 처리다 - 하나가 가드에 막혀도
     * 나머지는 계속 처리하고, 실패한 항목과 사유를 그대로 응답에 담아 돌려준다.
     *
     * <p>updateStatus() 호출은 requiresNewTransactionTemplate으로 항목마다 독립된 REQUIRES_NEW
     * 트랜잭션 안에서 실행한다 - self-invocation(this.updateStatus(...))은 @Transactional
     * 프록시를 우회하므로, 그냥 호출하면 이 메서드의 트랜잭션 하나를 모든 항목이 공유하게 된다.
     * 그 상태에서 항목 처리 도중(특히 AdminAuditLogger.log() 호출 이후) 예외가 나면, 그 항목의
     * UPDATE는 이미 실행된 채로 예외가 여기 catch에서 흡수돼 밖으로 전파되지 않으므로 결국 정상
     * 커밋되는데, 응답에는 그 항목이 실패로 기록되는 모순이 생긴다(AdminAuditLogger가 명시하는
     * "감사 로그 실패 시 실제 변경도 함께 롤백" 정책이 벌크 경로에서만 조용히 깨지는 것과 같다).
     * 항목마다 진짜 새 물리 트랜잭션을 열면 그 항목의 변경(+감사 로그)만 원자적으로 롤백되고,
     * 이미 커밋된 앞 항목들은 그대로 남는다.
     *
     * <p>입력에 같은 id가 중복되면(프론트는 Set이라 안 만들지만 API 호출로는 가능) 먼저 처리된 id가
     * 두 번째 시도에서 상태 전이 실패로 다시 걸려 같은 id가 성공/실패 양쪽에 나타날 수 있다 -
     * 순서를 보존한 채 중복만 제거해 이 문제를 없앤다.
     */
    public AdminBulkActionResponse bulkUpdateStatus(Long actorId, String actorEmail, List<Long> userIds, UserStatus status) {
        List<Long> succeededIds = new ArrayList<>();
        List<AdminBulkActionResponse.Failure> failures = new ArrayList<>();
        for (Long userId : new LinkedHashSet<>(userIds)) {
            try {
                // 자기 자신 제외 가드는 updateStatus() 자신이 rejectSelf()로 이미 강제한다 -
                // 여기서 별도로 다시 확인할 필요 없다(예전엔 이 가드가 컨트롤러/이 메서드 두 곳에
                // 손으로 복붙돼 있어, updateStatus()를 직접 호출하는 미래의 다른 호출부가 생기면
                // 조용히 건너뛸 위험이 있었다). actorEmail도 루프 밖(컨트롤러)에서 한 번만 확보한
                // 값을 그대로 전달한다 - 행위자는 이 벌크 처리 전체에서 항상 같은 관리자이므로,
                // 항목마다 다시 조회할 이유가 없다(AdminAuditLogger.log() 참고).
                requiresNewTransactionTemplate.executeWithoutResult(status_ -> updateStatus(actorId, actorEmail, userId, status));
                succeededIds.add(userId);
            } catch (BusinessException e) {
                failures.add(new AdminBulkActionResponse.Failure(userId, e.getMessage()));
            } catch (RuntimeException e) {
                // rejectIfLastActiveAdmin()의 비관적 락 대기 타임아웃, updateStatusIfNotWithdrawn()의
                // @Modifying UPDATE, adminAuditLogger.log() 등에서 BusinessException이 아닌 예외
                // (DataAccessException 계열)가 날 수 있다. 여기서 잡지 않으면 이 예외가 전파되면서
                // 항목별 성공/실패가 갈리는 배치라는 이 메서드의 설계 의도(위 클래스 주석 참고)를
                // 예상 못한 예외 타입 때문에 잃게 되므로, 여기서 흡수하고 일반화된 메시지로 실패
                // 처리한다 - 위 REQUIRES_NEW 덕분에 이 항목의 변경은 이미 롤백된 뒤이므로 안전하다.
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
            throw new BusinessException(ErrorCode.ADMIN_USER_SELF_ACTION_FORBIDDEN);
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
