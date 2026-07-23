package com.algogyeyak.auth.token;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefreshTokenServiceTest}는 Mockito로 {@code issue()}의 제어 흐름(catch 후 재조회/rotate)만
 * 검증한다 — 실제 Hibernate 세션이 {@code saveAndFlush()}의 유니크 제약 위반 이후에도 같은 트랜잭션
 * 안에서 안전하게 재사용될 수 있는지는 검증하지 못한다. 이 테스트는 실제 H2 DB + 실제
 * {@link RefreshTokenRepository} 빈으로 그 지점을 직접 검증한다.
 *
 * <p>운영 코드({@code issue()})는 findByUserId → (필요 시) saveAndFlush를 실행하므로, 두 스레드를 그냥
 * 동시에 돌리는 것만으로는 "먼저 읽었지만 나중에 커밋 시도"라는 정확한 race를 재현할 보장이 없다
 * (스케줄링에 따라 매번 재현되지 않을 수 있음). 그래서 이 테스트는 스레드 A에서 findByUserId로 먼저
 * 빈 결과를 확인한 뒤, {@code RefreshTokenService.insertNewRow()}(package-private, 운영 코드 그대로)를
 * 스레드 B가 실제로 커밋을 완료한 *뒤에* 직접 호출하도록 래치로 순서를 강제해 race를 결정론적으로
 * 재현한다 — 직접 재구현한 로직이 아니라 실제 운영 코드 경로를 검증한다.
 *
 * <p>주의: 이 테스트는 H2로만 실행된다. findByUserId에 잠금을 걸지 않는 이유(MySQL/InnoDB의
 * gap lock으로 인한 자기 자신과의 데드락 위험, {@link RefreshTokenRepository} 주석 참고)는
 * H2에서 재현되지 않으므로 이 테스트로는 검증되지 않는다.
 */
@SpringBootTest
class RefreshTokenConcurrentIssueIntegrationTest {

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Timeout(15)
    void secondTransactionRecoversAfterRealUniqueConstraintViolation() throws Exception {
        User user = userRepository.saveAndFlush(
                User.createOAuthUser("concurrent@example.com", "동시성유저", "http://img", AuthProvider.KAKAO, "concurrent-1"));
        Long userId = user.getId();
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(1);

        CountDownLatch aHasReadEmptyRow = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: 실제 issue()의 바깥 트랜잭션과 같은 조건(조회 → 대기 → insertNewRow 실제 호출)을
        // 재현한다. bHasCommitted를 기다리는 지점이, B가 실제로 행을 커밋한 뒤에야 A가 INSERT를
        // 시도하도록 강제하는 부분이다.
        Future<?> aResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            refreshTokenRepository.findByUserId(userId); // 이 시점엔 아직 아무 행도 없다.
            aHasReadEmptyRow.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            refreshTokenService.insertNewRow(user, "a-hash", expiresAt);
        }));

        // 스레드 B: A가 먼저 읽고 대기 상태에 들어간 뒤에만 실제 issue()를 실행해, 정상적으로
        // 새 행을 커밋한다 — 운영 코드에서 "동시에 첫 로그인이 들어온 다른 요청"에 해당한다.
        Future<String> bResult = executor.submit(() -> {
            awaitOrFail(aHasReadEmptyRow, "A가 먼저 조회를 마치지 않았습니다.");
            String rawToken = refreshTokenService.issue(user);
            bHasCommitted.countDown();
            return rawToken;
        });

        aResult.get(10, TimeUnit.SECONDS);
        bResult.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        RefreshToken row = refreshTokenRepository.findByUserId(userId).orElseThrow();
        assertEquals("a-hash", row.getTokenHash());
        assertEquals(1, refreshTokenRepository.findAll().stream().filter(t -> t.getUser().getId().equals(userId)).count());
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
