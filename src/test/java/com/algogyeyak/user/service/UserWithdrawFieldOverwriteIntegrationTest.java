package com.algogyeyak.user.service;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User/UserPreference에 {@code @DynamicUpdate}를 붙이기 전까지는, Hibernate가 매핑된 모든
 * 컬럼을 담은 정적 UPDATE를 쓴다 - 한 트랜잭션이 필드 하나만 바꿔도 그 트랜잭션이 읽은 시점의
 * 스냅샷 값으로 나머지 모든 컬럼을 다시 써넣는다. 이 테스트는 그 정확한 시나리오(관리자의
 * 권한 변경 트랜잭션이 사용자 본인의 탈퇴보다 먼저 그 행을 읽었지만 더 늦게 커밋되는 경우)를
 * 실제 H2 DB로 재현한다 - {@code @DynamicUpdate}를 떼면 이 테스트가 실패해야 한다(수동으로
 * 확인함: 익명화된 email/nickname/status가 탈퇴 전 값으로 되돌아가 PII가 되살아난다).
 */
@SpringBootTest
class UserWithdrawFieldOverwriteIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentRoleChangeDoesNotResurrectFieldsAnonymizedByWithdraw() throws Exception {
        User user = userRepository.saveAndFlush(
                User.createLocalUser("withdraw-overwrite-race@example.com", "encoded-hash", "탈퇴레이스닉네임"));
        Long userId = user.getId();

        CountDownLatch roleChangeHasLoaded = new CountDownLatch(1);
        CountDownLatch withdrawHasCommitted = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 B(관리자의 권한 변경): 탈퇴 전 상태를 읽어 스냅샷을 고정한 뒤, A가 탈퇴를
        // 커밋할 때까지 기다렸다가 role만 바꿔 커밋한다 - 읽기는 A보다 먼저, 커밋은 A보다 나중.
        Future<?> roleChangeResult = executor.submit(() -> transactionTemplate.execute(status -> {
            User managed = userRepository.findById(userId).orElseThrow();
            roleChangeHasLoaded.countDown();

            awaitOrFail(withdrawHasCommitted, "A(탈퇴)가 커밋을 완료하지 않았습니다.");

            managed.changeRole(Role.ADMIN);
            return null;
        }));

        // 스레드 A(본인 탈퇴): B가 먼저 읽은 뒤에만 탈퇴를 커밋한다.
        Future<?> withdrawResult = executor.submit(() -> {
            awaitOrFail(roleChangeHasLoaded, "B(권한 변경)가 먼저 조회를 마치지 않았습니다.");
            userService.withdraw(userId);
            withdrawHasCommitted.countDown();
            return null;
        });

        try {
            withdrawResult.get(10, TimeUnit.SECONDS);
            roleChangeResult.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        User persisted = userRepository.findById(userId).orElseThrow();
        assertThat(persisted.isWithdrawn()).isTrue();
        assertThat(persisted.getNickname()).startsWith("탈퇴회원_");
        assertThat(persisted.getEmail()).contains("withdrawn.algogyeyak.local");
        assertThat(persisted.getPasswordHash()).isNull();
        // B의 변경도 그대로 반영되어야 한다 - @DynamicUpdate가 A의 값을 지키는 대신 B의 변경
        // 자체를 날려버리는 건 아님을 함께 확인한다.
        assertThat(persisted.getRole()).isEqualTo(Role.ADMIN);
    }

    private static void awaitOrFail(CountDownLatch latch, String timeoutMessage) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), timeoutMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
