package com.algogyeyak.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.entity.AdminAuditLog;
import com.algogyeyak.admin.entity.AdminAuditTargetType;
import com.algogyeyak.admin.repository.AdminAuditLogRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * AdminAuditLogger.log()는 실제 활성 트랜잭션 안에서만 호출되도록 방어한다 - 그래서 이 mock 기반
 * 단위 테스트는 실제 서비스 호출부와 같은 조건을 흉내내기 위해 TransactionSynchronizationManager의
 * "활성 트랜잭션" 플래그를 직접 세팅한다(가벼운 페이크 - 실제 DataSourceTransactionManager 없이도
 * isActualTransactionActive()만 true로 만들면 충분하다). 실제 트랜잭션 안에서 끝까지 실행되는
 * 경로는 AdminAuditLoggerIntegrationTest가 별도로 검증한다.
 */
class AdminAuditLoggerTest {

    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final AdminAuditLogger adminAuditLogger = new AdminAuditLogger(adminAuditLogRepository);

    @BeforeEach
    void fakeActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearFakeTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void logSavesActionTargetAndJsonSerializedDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("beforeRole", "USER");
        detail.put("afterRole", "ADMIN");

        adminAuditLogger.log(1L, "admin@example.com", AdminAuditAction.UPDATE_ROLE, 5L, detail);

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        AdminAuditLog saved = captor.getValue();
        assertThat(saved.getAdminUserId()).isEqualTo(1L);
        assertThat(saved.getAction()).isEqualTo(AdminAuditAction.UPDATE_ROLE);
        assertThat(saved.getTargetId()).isEqualTo(5L);
        assertThat(saved.getDetail()).isEqualTo("{\"beforeRole\":\"USER\",\"afterRole\":\"ADMIN\"}");
    }

    // targetType은 호출부가 넘기지 않고 action에서 자동으로 유추된다 - action은 바뀌는데 targetType은
    // 안 바뀌는 실수 자체가 애초에 불가능해야 한다는 걸 보장한다.
    @Test
    void logDerivesTargetTypeFromAction() {
        adminAuditLogger.log(1L, "admin@example.com", AdminAuditAction.DELETE_CHECKLIST_TEMPLATE, 5L, Map.of("content", "누수 확인"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetType()).isEqualTo(AdminAuditTargetType.CHECKLIST_TEMPLATE);
    }

    // adminEmail은 더 이상 이 클래스가 userRepository로 재조회하지 않고, 호출부(컨트롤러/서비스가
    // 인증 컨텍스트에서 이미 확보한 값)가 그대로 넘긴 문자열을 스냅샷으로 저장한다.
    @Test
    void logCapturesActorEmailSnapshotPassedByCaller() {
        adminAuditLogger.log(1L, "admin@example.com", AdminAuditAction.DELETE_CHECKLIST_TEMPLATE, 5L, Map.of("content", "누수 확인"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminEmailSnapshot()).isEqualTo("admin@example.com");
    }

    // 호출부가 행위자의 이메일을 확보하지 못한 극단적인 경우(정상 흐름에서는 발생하지 않지만)에도
    // null을 그대로 전달하면 감사 로그 저장 자체는 계속되어야 한다 - 스냅샷만 비어있을 뿐이다.
    @Test
    void logToleratesNullAdminEmail() {
        adminAuditLogger.log(999L, null, AdminAuditAction.REVIEW_PROPERTY_REPORT, 5L, Map.of("status", "RESOLVED"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(adminAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getAdminEmailSnapshot()).isNull();
    }

    // 이 테스트만 예외적으로 활성 트랜잭션 플래그를 다시 꺼서, 호출부가 트랜잭션 밖에 있는 상황을
    // 재현한다 - 조용히 저장되면 안 되고 즉시 실패해야 한다(클래스 상단 정책 참고).
    @Test
    void logThrowsWhenCalledOutsideAnActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> adminAuditLogger.log(1L, "admin@example.com", AdminAuditAction.UPDATE_ROLE, 5L, Map.of("beforeRole", "USER")))
                .isInstanceOf(IllegalStateException.class);

        verify(adminAuditLogRepository, never()).save(any());
    }
}
