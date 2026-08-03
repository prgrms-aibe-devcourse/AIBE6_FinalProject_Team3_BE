package com.algogyeyak.auth.token;

import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RefreshTokenServiceTest}는 Mockito로 rotate/revoke의 호출 순서만 검증한다 — Redis의
 * getAndDelete가 실제로 동시 rotate() 중 정확히 하나만 성공시키는지, TTL이 실제로 자연 만료를
 * 일으키는지는 실제 Redis가 있어야 검증할 수 있다. 이 테스트는 Testcontainers로 띄운 실제 Redis +
 * 실제 {@link RefreshTokenService} 빈으로 그 지점을 직접 확인한다.
 */
@SpringBootTest
@Testcontainers
class RefreshTokenServiceRedisIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private UserRepository userRepository;

    private User saveUser(String email) {
        // 닉네임도 유니크 제약이 있으므로, 같은 테스트 클래스 안에서 여러 유저를 만들 때 이메일의
        // local-part로 닉네임을 다르게 만들어 충돌을 피한다.
        String nickname = "테스트유저-" + email.substring(0, email.indexOf('@'));
        return userRepository.saveAndFlush(User.createOAuthUser(email, nickname, "http://img"));
    }

    @Test
    void issuingANewTokenImmediatelyInvalidatesThePreviousRawToken() {
        User user = saveUser("single-session@example.com");
        String firstToken = refreshTokenService.issue(user);
        refreshTokenService.issue(user);

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(firstToken));
    }

    // issue()가 (구 버전처럼) get→delete→set→set을 별도 명령으로 나눠 보내면, 동시에 두 번 로그인할 때
    // 둘 다 같은 이전 hash를 읽고 각자 새 by-hash를 남겨 "유저당 1세션" 보장이 깨진다 — 두 raw token
    // 모두 rotate()에 성공할 수 있다는 뜻이다. ISSUE_SCRIPT가 원자적으로 처리하는지 실제 Redis로 검증한다.
    @Test
    @Timeout(15)
    void concurrentIssueForSameUserLeavesAtMostOneValidToken() throws Exception {
        User user = saveUser("concurrent-issue@example.com");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> a = executor.submit(() -> refreshTokenService.issue(user));
        Future<String> b = executor.submit(() -> refreshTokenService.issue(user));
        String tokenA = a.get(10, TimeUnit.SECONDS);
        String tokenB = b.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        attemptRotate(tokenA, successCount, failureCount);
        attemptRotate(tokenB, successCount, failureCount);

        assertEquals(1, successCount.get(),
                "동시 issue() 두 번 중 최종적으로 유효한 세션은 하나여야 한다 - 둘 다 rotate에 성공하면 안 된다");
        assertEquals(1, failureCount.get());
    }

    @Test
    void tokenNaturallyExpiresViaRedisTtl() throws InterruptedException {
        User user = saveUser("ttl@example.com");
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenValiditySeconds", 1L);

        String rawToken = refreshTokenService.issue(user);
        Thread.sleep(1500);

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(rawToken));
    }

    @Test
    @Timeout(15)
    void concurrentRotateWithSameRawTokenOnlyOneSucceeds() throws Exception {
        User user = saveUser("concurrent-rotate@example.com");
        String rawToken = refreshTokenService.issue(user);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        Future<?> a = executor.submit(() -> attemptRotate(rawToken, successCount, failureCount));
        Future<?> b = executor.submit(() -> attemptRotate(rawToken, successCount, failureCount));
        a.get(10, TimeUnit.SECONDS);
        b.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "동시 rotate() 중 정확히 하나만 성공해야 한다");
        assertEquals(1, failureCount.get());
    }

    private void attemptRotate(String rawToken, AtomicInteger successCount, AtomicInteger failureCount) {
        try {
            refreshTokenService.rotate(rawToken);
            successCount.incrementAndGet();
        } catch (BusinessException e) {
            failureCount.incrementAndGet();
        }
    }

    @Test
    void revokeThenRotateWithSameTokenFails() {
        User user = saveUser("revoke@example.com");
        String rawToken = refreshTokenService.issue(user);

        refreshTokenService.revoke(rawToken);

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(rawToken));
    }

    @Test
    void rotateReturnsNewTokenThatCanBeRotatedAgain() {
        User user = saveUser("rotate-chain@example.com");
        String rawToken = refreshTokenService.issue(user);

        RefreshTokenService.RotationResult first = refreshTokenService.rotate(rawToken);
        RefreshTokenService.RotationResult second = refreshTokenService.rotate(first.rawToken());

        assertEquals(user.getId(), second.user().getId());
    }
}
