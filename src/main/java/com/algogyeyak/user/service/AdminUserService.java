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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                condition.email(), condition.nickname(), condition.role(), condition.status(), pageable);
        return PageResponse.from(page, AdminUserListItemResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse getDetail(Long userId) {
        return AdminUserDetailResponse.from(findUser(userId));
    }

    @Transactional
    public AdminUserDetailResponse updateRole(Long actorId, Long userId, Role role) {
        User user = findUser(userId);
        if (role != Role.ADMIN) {
            rejectIfLastActiveAdmin(user);
        }
        Role previousRole = user.getRole();
        user.changeRole(role);
        adminAuditLogger.log(actorId, AdminAuditAction.UPDATE_ROLE, userId,
                Map.of("beforeRole", previousRole, "afterRole", role));
        return AdminUserDetailResponse.from(user);
    }

    // status는 컨트롤러 DTO 단계에서 이미 ACTIVE/SUSPENDED로 제한되어 있고, 탈퇴 유저에 대한 거부는
    // User.suspend()/activate()가 던진다 - 여기서는 그 두 값 중 어느 쪽으로 갈지만 분기한다.
    @Transactional
    public AdminUserDetailResponse updateStatus(Long actorId, Long userId, UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "ACTIVE/SUSPENDED로만 변경할 수 있습니다.");
        }

        User user = findUser(userId);
        UserStatus previousStatus = user.getStatus();
        if (status == UserStatus.SUSPENDED) {
            rejectIfLastActiveAdmin(user);
            user.suspend();
        } else {
            user.activate();
        }
        adminAuditLogger.log(actorId, AdminAuditAction.UPDATE_STATUS, userId,
                Map.of("beforeStatus", previousStatus, "afterStatus", status));
        return AdminUserDetailResponse.from(user);
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
                if (actorId.equals(userId)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "자기 자신의 권한/상태는 변경할 수 없습니다.");
                }
                updateStatus(actorId, userId, status);
                succeededIds.add(userId);
            } catch (BusinessException e) {
                failures.add(new AdminBulkActionResponse.Failure(userId, e.getErrorCode().name(), e.getMessage()));
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
    private void rejectIfLastActiveAdmin(User user) {
        if (user.getRole() != Role.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
            return;
        }
        if (userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE).size() <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT);
        }
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_NOT_FOUND));
    }
}
