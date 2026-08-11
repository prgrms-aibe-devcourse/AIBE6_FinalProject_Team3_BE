package com.algogyeyak.user.service;

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
    public AdminUserDetailResponse updateRole(Long userId, Role role) {
        User user = findUser(userId);
        if (role != Role.ADMIN) {
            rejectIfLastActiveAdmin(user);
        }
        user.changeRole(role);
        return AdminUserDetailResponse.from(user);
    }

    // status는 컨트롤러 DTO 단계에서 이미 ACTIVE/SUSPENDED로 제한되어 있고, 탈퇴 유저에 대한 거부는
    // User.suspend()/activate()가 던진다 - 여기서는 그 두 값 중 어느 쪽으로 갈지만 분기한다.
    @Transactional
    public AdminUserDetailResponse updateStatus(Long userId, UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "ACTIVE/SUSPENDED로만 변경할 수 있습니다.");
        }

        User user = findUser(userId);
        if (status == UserStatus.SUSPENDED) {
            rejectIfLastActiveAdmin(user);
            user.suspend();
        } else {
            user.activate();
        }
        return AdminUserDetailResponse.from(user);
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
