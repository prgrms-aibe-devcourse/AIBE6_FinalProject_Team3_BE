package com.algogyeyak.user.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마지막 활성 관리자 보호(rejectIfLastActiveAdmin)가 findAllByRoleAndStatusForUpdate(PESSIMISTIC_WRITE)를
 * 올바르게 사용하는지 검증한다. 실제 락이 두 트랜잭션을 직렬화하는지는 DB 엔진의 락 구현에 달려 있어
 * Mockito로는 확인할 수 없다 - 여기서는 그 조회 결과(size)에 따라 서비스가 옳게 분기하는지만 본다.
 */
class AdminUserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminUserService adminUserService = new AdminUserService(userRepository);

    private User adminUser(Long id) {
        User user = User.createOAuthUser("admin" + id + "@example.com", "관리자" + id, "http://img");
        user.grantAdminRole();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User normalUser(Long id) {
        User user = User.createOAuthUser("user" + id + "@example.com", "유저" + id, "http://img");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void updateRoleThrowsWhenDemotingTheLastActiveAdmin() {
        User admin = adminUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.updateRole(1L, Role.USER));

        assertEquals(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT, exception.getErrorCode());
        assertEquals(Role.ADMIN, admin.getRole(), "거부됐다면 실제 강등이 적용되면 안 된다");
    }

    @Test
    void updateRoleSucceedsWhenAnotherActiveAdminExists() {
        User admin = adminUser(1L);
        User otherAdmin = adminUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin, otherAdmin));

        adminUserService.updateRole(1L, Role.USER);

        assertEquals(Role.USER, admin.getRole());
    }

    @Test
    void updateStatusThrowsWhenSuspendingTheLastActiveAdmin() {
        User admin = adminUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(1L, UserStatus.SUSPENDED));

        assertEquals(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT, exception.getErrorCode());
        assertEquals(UserStatus.ACTIVE, admin.getStatus());
    }

    @Test
    void updateStatusSucceedsWhenAnotherActiveAdminExists() {
        User admin = adminUser(1L);
        User otherAdmin = adminUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin, otherAdmin));

        adminUserService.updateStatus(1L, UserStatus.SUSPENDED);

        assertEquals(UserStatus.SUSPENDED, admin.getStatus());
    }

    // 강등/정지 대상이 이미 ADMIN+ACTIVE가 아니면(예: 일반 유저 정지) 마지막 관리자와 무관하므로
    // 락 조회 자체를 타지 않아야 한다 - 불필요하게 관리자 테이블 전체에 락을 거는 것을 피한다.
    @Test
    void updateStatusSkipsLockQueryForNonAdminTarget() {
        User user = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        adminUserService.updateStatus(1L, UserStatus.SUSPENDED);

        assertEquals(UserStatus.SUSPENDED, user.getStatus());
        verify(userRepository, never()).findAllByRoleAndStatusForUpdate(any(), any());
    }
}
