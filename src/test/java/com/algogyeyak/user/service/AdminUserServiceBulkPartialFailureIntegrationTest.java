package com.algogyeyak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.algogyeyak.admin.dto.AdminBulkActionResponse;
import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.UserStatus;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link AdminUserServiceTest}는 UserRepository/AdminAuditLogger를 mock으로 대체하고
 * PlatformTransactionManager도 순수 mock이라, "항목마다 REQUIRES_NEW로 감쌌더니 실제로 그
 * 항목의 DB 변경이 롤백되는지"는 검증하지 못한다(mock은 커밋/롤백을 실제로 구분하지 않는다).
 * 이 테스트는 real H2 + real 트랜잭션 매니저로 그 지점을 직접 확인한다 - AdminAuditLogger만
 * mock으로 대체해 특정 대상에서만 감사 로그가 실패하도록 만든 뒤, 그 대상의 상태 변경이
 * (UPDATE 자체는 실행됐더라도) 최종적으로 DB에 반영되지 않는지 재조회로 검증한다.
 *
 * <p>이 픽스 이전에는 bulkUpdateStatus()가 self-invocation으로 updateStatus()를 호출해 모든
 * 항목이 bulkUpdateStatus() 자신의 트랜잭션 하나를 공유했다 - 감사 로그 실패가
 * catch(RuntimeException)에서 흡수되면서 그 트랜잭션 밖으로 전파되지 않아, 이미 실행된 UPDATE가
 * 그대로 커밋됐다(응답은 실패로 보고하면서 실제로는 DB가 바뀌는 모순).
 */
@SpringBootTest
class AdminUserServiceBulkPartialFailureIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private AdminAuditLogger adminAuditLogger;

    private User saveActiveUser(String email, String nickname) {
        return userRepository.saveAndFlush(User.createLocalUser(email, "hash", nickname));
    }

    @Test
    void 감사로그_실패한_항목은_UPDATE가_실행됐어도_실제로는_롤백된다() {
        User succeeds = saveActiveUser("bulk-partial-ok@example.com", "정상처리유저");
        User fails = saveActiveUser("bulk-partial-fail@example.com", "감사로그실패유저");
        Long actorId = 999_000L; // 실제로 존재하지 않는 관리자 id - 자기 자신 가드에만 안 걸리면 됨

        // succeeds 대상은 정상적으로 감사 로그가 남고, fails 대상만 감사 로그 저장 시점에 실패한다
        // (예: JSON 직렬화 실패 등 - AdminAuditLogger.toJson()이 실제로 던지는 예외와 같은 종류).
        doThrow(new IllegalStateException("감사 로그 직렬화 실패"))
                .when(adminAuditLogger).log(eq(actorId), eq(AdminAuditAction.UPDATE_STATUS), eq(fails.getId()), any());

        AdminBulkActionResponse result = adminUserService.bulkUpdateStatus(
                actorId, java.util.List.of(succeeds.getId(), fails.getId()), UserStatus.SUSPENDED);

        assertThat(result.succeededIds()).containsExactly(succeeds.getId());
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).id()).isEqualTo(fails.getId());

        // 핵심 검증 - fails 대상은 updateStatusIfNotWithdrawn()의 UPDATE 자체는 실행됐지만
        // (감사 로그 실패가 그 이후 단계이므로), REQUIRES_NEW 트랜잭션이 롤백되어 최종적으로
        // DB에는 반영되지 않아야 한다. 반대로 succeeds 대상은 정상적으로 SUSPENDED로 남아있어야
        // 한다(한 항목의 실패가 다른 항목의 이미 커밋된 변경까지 되돌리지 않는다).
        User reloadedSucceeds = userRepository.findById(succeeds.getId()).orElseThrow();
        User reloadedFails = userRepository.findById(fails.getId()).orElseThrow();
        assertThat(reloadedSucceeds.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(reloadedFails.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }
}
