package com.algogyeyak.user.service;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.dto.AdminUserDetailResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 마지막 활성 관리자 보호(rejectIfLastActiveAdmin)가 findAllByRoleAndStatusForUpdate(PESSIMISTIC_WRITE)를
 * 올바르게 사용하는지 검증한다. 실제 락이 두 트랜잭션을 직렬화하는지는 DB 엔진의 락 구현에 달려 있어
 * Mockito로는 확인할 수 없다 - 여기서는 그 조회 결과(size)에 따라 서비스가 옳게 분기하는지만 본다.
 * AdminAuditLogger는 mock으로 대체해, 거부된 변경에는 감사 로그가 남지 않는지도 함께 확인한다.
 *
 * updateRole()/updateStatus()는 이제 User.changeRole()/suspend()/activate()로 엔티티를 직접
 * 바꾸지 않고 UserRepository.updateRoleIfNotWithdrawn()/updateStatusIfNotWithdrawn() 조건부
 * UPDATE의 영향받은 row 수로 판단하므로(AdminUserService 참고), 성공 케이스는 그 메서드가 1을
 * 반환하도록 스텁해야 하고, 결과 검증은 엔티티 필드가 아니라 반환된 AdminUserDetailResponse로 한다.
 */
class AdminUserServiceTest {

    private static final Long ACTOR_ID = 100L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminAuditLogger adminAuditLogger = mock(AdminAuditLogger.class);
    private final AdminUserService adminUserService = new AdminUserService(userRepository, adminAuditLogger);

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
                () -> adminUserService.updateRole(ACTOR_ID, 1L, Role.USER));

        assertEquals(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT, exception.getErrorCode());
        verify(userRepository, never()).updateRoleIfNotWithdrawn(any(), any(), any());
        verify(adminAuditLogger, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateRoleSucceedsWhenAnotherActiveAdminExists() {
        User admin = adminUser(1L);
        User otherAdmin = adminUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin, otherAdmin));
        when(userRepository.updateRoleIfNotWithdrawn(1L, Role.USER, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminUserDetailResponse response = adminUserService.updateRole(ACTOR_ID, 1L, Role.USER);

        assertEquals(Role.USER, response.role());
        verify(adminAuditLogger).log(ACTOR_ID, AdminAuditAction.UPDATE_ROLE, 1L,
                Map.of("beforeRole", Role.ADMIN, "afterRole", Role.USER));
    }

    // AdminUserService.updateRole()가 조건부 UPDATE(updateRoleIfNotWithdrawn)의 영향받은 row 수로
    // 탈퇴 여부를 판단하는 경로 자체를 검증한다 - findUser()가 대상을 읽은 뒤(활성 상태였음),
    // 실제 UPDATE 시점에는 동시에 탈퇴가 먼저 커밋되어 0건이 반영된 상황을 흉내낸다. 이 경우
    // WITHDRAWN 상태의 계정이 role만 조용히 바뀌는 대신 명확한 에러로 거부돼야 한다.
    @Test
    void updateRoleThrowsWhenTargetWasConcurrentlyWithdrawn() {
        User user = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.updateRoleIfNotWithdrawn(1L, Role.ADMIN, UserStatus.WITHDRAWN)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.updateRole(ACTOR_ID, 1L, Role.ADMIN));

        assertEquals(ErrorCode.ADMIN_INVALID_ROLE_TRANSITION, exception.getErrorCode());
        verify(adminAuditLogger, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateStatusThrowsWhenSuspendingTheLastActiveAdmin() {
        User admin = adminUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(ACTOR_ID, 1L, UserStatus.SUSPENDED));

        assertEquals(ErrorCode.ADMIN_LAST_ADMIN_ACCOUNT, exception.getErrorCode());
        verify(userRepository, never()).updateStatusIfNotWithdrawn(any(), any(), any());
        verify(adminAuditLogger, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateStatusSucceedsWhenAnotherActiveAdminExists() {
        User admin = adminUser(1L);
        User otherAdmin = adminUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(admin, otherAdmin));
        when(userRepository.updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminUserDetailResponse response = adminUserService.updateStatus(ACTOR_ID, 1L, UserStatus.SUSPENDED);

        assertEquals(UserStatus.SUSPENDED, response.status());
        verify(adminAuditLogger).log(ACTOR_ID, AdminAuditAction.UPDATE_STATUS, 1L,
                Map.of("beforeStatus", UserStatus.ACTIVE, "afterStatus", UserStatus.SUSPENDED));
    }

    // AdminUserService.updateStatus()가 조건부 UPDATE(updateStatusIfNotWithdrawn)의 영향받은
    // row 수로 탈퇴 여부를 판단하는 경로 자체를 검증한다 - updateRoleThrowsWhenTargetWasConcurrentlyWithdrawn과
    // 동일한 레이스를 정지 쪽에서 재현한다.
    @Test
    void updateStatusThrowsWhenTargetWasConcurrentlyWithdrawn() {
        User user = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adminUserService.updateStatus(ACTOR_ID, 1L, UserStatus.SUSPENDED));

        assertEquals(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, exception.getErrorCode());
        verify(adminAuditLogger, never()).log(any(), any(), any(), any());
    }

    // 강등/정지 대상이 이미 ADMIN+ACTIVE가 아니면(예: 일반 유저 정지) 마지막 관리자와 무관하므로
    // 락 조회 자체를 타지 않아야 한다 - 불필요하게 관리자 테이블 전체에 락을 거는 것을 피한다.
    @Test
    void updateStatusSkipsLockQueryForNonAdminTarget() {
        User user = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminUserDetailResponse response = adminUserService.updateStatus(ACTOR_ID, 1L, UserStatus.SUSPENDED);

        assertEquals(UserStatus.SUSPENDED, response.status());
        verify(userRepository, never()).findAllByRoleAndStatusForUpdate(any(), any());
    }

    // 셀프 대상(2L)이 가드에 막혀도, 목록의 나머지(1L)는 그대로 처리돼야 한다 - 하나의 실패가
    // 전체 배치를 롤백시키지 않는다는 걸 확인한다.
    @Test
    void bulkUpdateStatus는_일부_실패해도_나머지는_그대로_처리된다() {
        User target = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminBulkActionResponse result =
                adminUserService.bulkUpdateStatus(ACTOR_ID, List.of(1L, ACTOR_ID), UserStatus.SUSPENDED);

        verify(userRepository).updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN);
        assertEquals(List.of(1L), result.succeededIds());
        assertEquals(1, result.failures().size());
        assertEquals(ACTOR_ID, result.failures().get(0).id());
    }

    // 회귀 테스트 - 중복 제거 전에는 같은 id를 두 번 처리해 첫 시도는 성공(succeededIds)하고
    // 두 번째 시도가 (이미 정지 상태라) 상태 전이 규칙에 걸려 같은 id가 failures에도 나타날 수
    // 있었다. 순서를 보존한 채 중복만 제거해 같은 id가 성공/실패 양쪽에 나타나지 않아야 한다.
    @Test
    void bulkUpdateStatus는_중복된_id를_한_번만_처리한다() {
        User target = normalUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(userRepository.updateStatusIfNotWithdrawn(1L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminBulkActionResponse result =
                adminUserService.bulkUpdateStatus(ACTOR_ID, List.of(1L, 1L), UserStatus.SUSPENDED);

        assertEquals(List.of(1L), result.succeededIds());
        assertEquals(0, result.failures().size());
        verify(userRepository, org.mockito.Mockito.times(1)).findById(1L);
    }

    // 마지막 활성 관리자 보호에 걸린 항목만 실패 목록에 담기고, 다른 대상은 정상 처리돼야 한다.
    @Test
    void bulkUpdateStatus는_마지막_관리자_보호에_걸린_항목만_실패한다() {
        User lastAdmin = adminUser(1L);
        User normalTarget = normalUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(lastAdmin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(normalTarget));
        when(userRepository.findAllByRoleAndStatusForUpdate(Role.ADMIN, UserStatus.ACTIVE))
                .thenReturn(List.of(lastAdmin));
        when(userRepository.updateStatusIfNotWithdrawn(2L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN)).thenReturn(1);

        AdminBulkActionResponse result =
                adminUserService.bulkUpdateStatus(ACTOR_ID, List.of(1L, 2L), UserStatus.SUSPENDED);

        verify(userRepository, never()).updateStatusIfNotWithdrawn(eq(1L), any(), any());
        verify(userRepository).updateStatusIfNotWithdrawn(2L, UserStatus.SUSPENDED, UserStatus.WITHDRAWN);
        assertEquals(List.of(2L), result.succeededIds());
        assertEquals(1, result.failures().size());
        assertEquals(1L, result.failures().get(0).id());
        assertTrue(result.failures().get(0).message() != null && !result.failures().get(0).message().isBlank());
    }
}
