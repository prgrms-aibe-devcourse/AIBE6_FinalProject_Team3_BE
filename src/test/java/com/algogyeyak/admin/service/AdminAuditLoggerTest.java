package com.algogyeyak.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.entity.AdminAuditLog;
import com.algogyeyak.admin.repository.AdminAuditLogRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AdminAuditLoggerTest {

    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminAuditLogger adminAuditLogger = new AdminAuditLogger(adminAuditLogRepository, userRepository);

    private User user(Long id, String email) {
        User user = User.createOAuthUser(email, "관리자", "http://img");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void logSavesActionTargetAndJsonSerializedDetail() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "admin@example.com")));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("beforeRole", "USER");
        detail.put("afterRole", "ADMIN");

        adminAuditLogger.log(1L, AdminAuditAction.UPDATE_ROLE, 5L, detail);

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        AdminAuditLog saved = captor.getValue();
        assertThat(saved.getAdminUserId()).isEqualTo(1L);
        assertThat(saved.getAction()).isEqualTo(AdminAuditAction.UPDATE_ROLE);
        assertThat(saved.getTargetId()).isEqualTo(5L);
        assertThat(saved.getDetail()).isEqualTo("{\"beforeRole\":\"USER\",\"afterRole\":\"ADMIN\"}");
    }

    @Test
    void logCapturesActorEmailSnapshotAtWriteTime() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "admin@example.com")));

        adminAuditLogger.log(1L, AdminAuditAction.DELETE_CHECKLIST_TEMPLATE, 5L, Map.of("content", "누수 확인"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminEmailSnapshot()).isEqualTo("admin@example.com");
    }

    // 이론상 principal이 참조하는 유저가 이미 삭제된 극단적인 경우에도(정상 흐름에서는 발생하지
    // 않지만) 감사 로그 저장 자체는 계속되어야 한다 - 스냅샷만 비어있을 뿐이다.
    @Test
    void logToleratesMissingActorForEmailSnapshot() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        adminAuditLogger.log(999L, AdminAuditAction.REVIEW_PROPERTY_REPORT, 5L, Map.of("status", "RESOLVED"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminEmailSnapshot()).isNull();
    }
}
