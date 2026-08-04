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
            user.suspend();
        } else {
            user.activate();
        }
        return AdminUserDetailResponse.from(user);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_NOT_FOUND));
    }
}
