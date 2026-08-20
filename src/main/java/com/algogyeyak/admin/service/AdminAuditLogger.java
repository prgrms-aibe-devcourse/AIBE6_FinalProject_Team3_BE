package com.algogyeyak.admin.service;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.entity.AdminAuditLog;
import com.algogyeyak.admin.repository.AdminAuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관리자 액션 감사 로그 기록의 단일 진입점. 호출하는 서비스 메서드가 이미 @Transactional이므로
 * 여기서 저장하는 행은 그 메서드의 실제 변경(role 변경, 문항 삭제 등)과 같은 트랜잭션에 묶여
 * 함께 커밋/롤백된다 - 이 저장이 실패하면(제약 위반 등) 실제 변경도 함께 롤백된다. 감사 기록을
 * 남길 수 없으면 관리자 변경 자체도 실패해야 한다는 의도적 정책이다.
 *
 * <p>이 정책은 호출부가 실제로 활성 트랜잭션 안에 있을 때만 성립한다 - 그래서 javadoc만으로 두지
 * 않고 {@link #log}가 시작 시점에 {@link TransactionSynchronizationManager#isActualTransactionActive()}로
 * 직접 확인한다. 앞으로 누군가 트랜잭션 밖에서(예: @Async, 별도 스레드) 실수로 호출해도, 감사 기록이
 * 조용히 트랜잭션 보장 없이 저장되는 대신 즉시 실패한다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogger {

    // SecurityConfig와 동일한 이유(클래스 내부 단순 직렬화 용도) - 이 프로젝트는
    // spring-boot-starter-webmvc만 쓰고 spring-boot-starter-json이 없어 Boot이 자동 구성하는
    // ObjectMapper 빈이 없다. 여기서 직접 인스턴스를 만들어 그 빈에 의존하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AdminAuditLogRepository adminAuditLogRepository;

    /**
     * @param adminEmail 행위자(관리자)의 현재 이메일 스냅샷. 호출부가 이미 인증 컨텍스트(JwtUserPrincipal
     *                   등)에서 들고 있는 값을 그대로 넘겨받는다 - 예전에는 여기서 매 호출마다
     *                   userRepository.findById(adminUserId)로 같은 관리자 row를 다시 조회했는데,
     *                   벌크 관리자 액션(N건 루프)에서 같은 관리자에 대해 이 SELECT가 N번 반복되는
     *                   문제가 있었다. 호출부가 요청 컨텍스트에서 이미 확보한 값을 그대로 전달하면
     *                   이 재조회 자체가 필요 없다.
     * @param detail 사람이 읽기 좋은 문자열이 아니라 나중에 조회/필터링이 가능하도록
     *               {"beforeRole": "USER", "afterRole": "ADMIN"}처럼 JSON 직렬화할 Map으로 받는다.
     */
    public void log(Long adminUserId, String adminEmail, AdminAuditAction action, Long targetId, Map<String, Object> detail) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "AdminAuditLogger.log()는 실제 변경과 같은 트랜잭션 안에서만 호출할 수 있습니다 - "
                            + "호출부가 @Transactional 메서드 안에 있는지 확인하세요.");
        }
        adminAuditLogRepository.save(
                AdminAuditLog.of(adminUserId, adminEmail, action, targetId, toJson(detail)));
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // 감사 로그 자체가 이 메서드가 속한 트랜잭션의 일부이므로, 여기서 던지면 실제 변경도
            // 함께 롤백된다(클래스 상단 정책 참고) - 조용히 삼키고 텍스트 로그만 남기지 않는다.
            throw new IllegalStateException("감사 로그 detail 직렬화에 실패했습니다", e);
        }
    }
}
