package com.algogyeyak.auth.token;

import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RefreshTokenServiceTest}는 Mockito로 스크립트 호출 앞뒤의 자바 분기만 검증한다 — Lua
 * script가 실제로 동시 issue()/rotate() 중 정확히 하나만 살아남게 하는지, TTL이 실제로 자연 만료를
 * 일으키는지, by-user와 어긋난 고아 by-hash를 실제로 거부하는지는 실제 Redis가 있어야 검증할 수
 * 있다. 이 테스트는 Testcontainers로 띄운 실제 Redis + 실제 {@link RefreshTokenService} 빈으로
 * 그 지점들을 직접 확인한다.
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

    @Autowired
    private StringRedisTemplate redisTemplate;

    private User saveUser(String email) {
        // 닉네임도 유니크 제약이 있으므로, 같은 테스트 클래스 안에서 여러 유저를 만들 때 이메일의
        // local-part로 닉네임을 다르게 만들어 충돌을 피한다.
        String nickname = "테스트유저-" + email.substring(0, email.indexOf('@'));
        return userRepository.saveAndFlush(User.createOAuthUser(email, nickname, "http://img"));
    }

    // RefreshTokenService.hash()와 동일한 알고리즘 - 서비스 코드를 바꾸지 않고 "이미 by-hash에
    // 등록된 것처럼 보이는" raw token을 직접 만들기 위해 테스트에서도 같은 해시를 계산한다.
    private static String hash(String rawToken) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
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

    // 고정 sleep(예: TTL 1초 + sleep 1500ms)은 느린 CI/컨테이너 warm-up 등으로 TTL 만료보다 먼저
    // 깨어날 수 있어 드물게 흔들린다 - "얼마나 기다릴지"를 추측하는 대신, 키가 실제로 사라졌는지를
    // 짧은 간격으로 직접 확인(polling)해 진짜 만료를 기다린다.
    @Test
    @Timeout(15)
    void tokenNaturallyExpiresViaRedisTtl() throws Exception {
        User user = saveUser("ttl@example.com");
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenValiditySeconds", 1L);

        String rawToken = refreshTokenService.issue(user);
        awaitKeyAbsence("auth:refresh-token:by-hash:" + hash(rawToken));

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(rawToken));
    }

    private void awaitKeyAbsence(String key) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("키가 TTL로 자연 만료되지 않았다: " + key);
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

    // by-user와 by-hash가 어긋난 "고아" by-hash가 남아있는 상황(예전 issue() 동시성 버그의 잔재,
    // 또는 운영 중 장애로 발생 가능)을 직접 재현한다. ROTATE_SCRIPT가 by-user 일치 확인 없이 소비만
    // 했다면, 이 오래된 토큰도 rotate()에 성공하며 최신 세션의 by-user를 덮어썼을 것이다.
    @Test
    void rotateRejectsAnOrphanedByHashThatNoLongerMatchesTheCurrentSession() throws Exception {
        User user = saveUser("orphaned-hash@example.com");
        String legitToken = refreshTokenService.issue(user);

        String orphanRawToken = "manually-orphaned-raw-token";
        redisTemplate.opsForValue().set("auth:refresh-token:by-hash:" + hash(orphanRawToken), String.valueOf(user.getId()));

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(orphanRawToken));
        // 고아 토큰의 rotate 시도가 실패하는 것만으로는 부족하다 - 현재 세션의 by-user를 건드리지
        // 않았는지(=진짜 세션이 여전히 살아있는지)까지 확인해야 이 수정의 핵심을 검증한 것이다.
        refreshTokenService.rotate(legitToken);
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
