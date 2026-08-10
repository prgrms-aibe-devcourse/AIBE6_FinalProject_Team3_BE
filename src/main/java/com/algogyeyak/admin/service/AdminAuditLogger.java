package com.algogyeyak.admin.service;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.entity.AdminAuditLog;
import com.algogyeyak.admin.repository.AdminAuditLogRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 액션 감사 로그 기록의 단일 진입점. 호출하는 서비스 메서드가 이미 @Transactional이므로
 * 여기서 저장하는 행은 그 메서드의 실제 변경(role 변경, 문항 삭제 등)과 같은 트랜잭션에 묶여
 * 함께 커밋/롤백된다 - 이 저장이 실패하면(제약 위반 등) 실제 변경도 함께 롤백된다. 감사 기록을
 * 남길 수 없으면 관리자 변경 자체도 실패해야 한다는 의도적 정책이다.
 */
@Component
@RequiredArgsConstructor
public class AdminAuditLogger {

    // SecurityConfig와 동일한 이유(클래스 내부 단순 직렬화 용도) - 이 프로젝트는
    // spring-boot-starter-webmvc만 쓰고 spring-boot-starter-json이 없어 Boot이 자동 구성하는
    // ObjectMapper 빈이 없다. 여기서 직접 인스턴스를 만들어 그 빈에 의존하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AdminAuditLogRepository adminAuditLogRepository;
    private final UserRepository userRepository;

    /**
     * @param detail 사람이 읽기 좋은 문자열이 아니라 나중에 조회/필터링이 가능하도록
     *               {"beforeRole": "USER", "afterRole": "ADMIN"}처럼 JSON 직렬화할 Map으로 받는다.
     */
    public void log(Long adminUserId, AdminAuditAction action, Long targetId, Map<String, Object> detail) {
        String adminEmailSnapshot = userRepository.findById(adminUserId)
                .map(User::getEmail)
                .orElse(null);
        adminAuditLogRepository.save(
                AdminAuditLog.of(adminUserId, adminEmailSnapshot, action, targetId, toJson(detail)));
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
