package com.algogyeyak.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.entity.AdminAuditLog;
import com.algogyeyak.admin.repository.AdminAuditLogRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.service.AdminUserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AdminAuditLoggerTest는 repository를 mock으로 대체해 저장 호출 자체만 검증한다 - 이 테스트는 실제
 * AdminUserService.updateRole()을 통해 끝까지 실행해, 실제 변경(role 변경)과 감사 로그 저장이
 * 같은 트랜잭션에서 함께 커밋되어 실제로 조회 가능한 행이 남는지 확인한다.
 */
@SpringBootTest
class AdminAuditLoggerIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAuditLogRepository adminAuditLogRepository;

    @Test
    void updateRoleThroughRealServicePersistsAQueryableAuditLogRow() {
        // AdminUserService.updateRole()은 행위자(actorId)의 role을 검사하지 않는다(그건 컨트롤러 밖의
        // hasRole("ADMIN") 필터가 이미 막은 뒤라는 전제) - 그래서 행위자를 실제 ADMIN으로 만들 필요가
        // 없다. 불필요하게 ADMIN+ACTIVE 유저를 만들면 같은 컨텍스트를 공유하는 다른 테스트(예:
        // AdminUserServiceConcurrentDemotionIntegrationTest)의 "활성 관리자는 이 두 명뿐" 전제를
        // 깨뜨릴 수 있다.
        User admin = userRepository.saveAndFlush(User.createLocalUser("actor@example.com", "hash", "행위자"));
        User target = userRepository.saveAndFlush(User.createLocalUser("target@example.com", "hash", "대상유저"));

        adminUserService.updateRole(admin.getId(), admin.getEmail(), target.getId(), Role.ADMIN);

        // 같은 @SpringBootTest 컨텍스트를 공유하는 다른 테스트도 이 테이블에 행을 남길 수 있어
        // findAll() 전체 개수가 아니라, 이 테스트가 만든 target.getId()(새로 생성된 유저라 다른
        // 테스트와 겹치지 않음)로 걸러 정확히 그 행만 확인한다.
        List<AdminAuditLog> logs = adminAuditLogRepository.findAll().stream()
                .filter(log -> log.getTargetId().equals(target.getId()))
                .toList();
        assertThat(logs).hasSize(1);
        AdminAuditLog saved = logs.get(0);
        assertThat(saved.getAdminUserId()).isEqualTo(admin.getId());
        assertThat(saved.getAdminEmailSnapshot()).isEqualTo("actor@example.com");
        assertThat(saved.getAction()).isEqualTo(AdminAuditAction.UPDATE_ROLE);
        assertThat(saved.getTargetId()).isEqualTo(target.getId());
        assertThat(saved.getDetail()).contains("\"beforeRole\":\"USER\"").contains("\"afterRole\":\"ADMIN\"");
        assertThat(saved.getCreatedAt()).isNotNull();
    }
}
