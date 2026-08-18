package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.repository.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link LocalAuthServiceTest#signupRecoversAsEmailDuplicateWhenConcurrentSignupHitsUniqueConstraint}는
 * Mockito로 제어 흐름만 검증한다. 이 테스트는 {@code UserServiceConcurrentNicknameChangeIntegrationTest}와
 * 동일한 이유로, 실제 H2 DB + REPEATABLE READ 트랜잭션에서 "사전 검사 통과 후 커밋 시점 레이스 →
 * 유니크 제약 위반 → 재확인"이 바깥 트랜잭션의 스냅샷에 흔들리지 않는지 직접 검증한다 -
 * {@code LocalAuthService.signup()}의 재확인(existsByEmail/existsByNickname)이 REQUIRES_NEW 없이
 * 바깥 트랜잭션에서 그냥 실행되면, 그 바깥 트랜잭션이 경쟁 요청의 커밋 전 스냅샷을 이미 고정하고
 * 있을 때 stale한 "아직 안 겹침" 결과를 돌려줘 원래 예외가 500으로 새어나갈 수 있었다.
 */
@SpringBootTest
class LocalAuthServiceConcurrentSignupIntegrationTest {

    @Autowired
    private LocalAuthService localAuthService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // 이 테스트는 DB 레벨 레이스(REPEATABLE READ 스냅샷/유니크 제약)만 검증 대상이므로, 이메일 인증
    // 자체는 이미 끝난 것으로 간주하고 Redis 의존 없이 항상 통과시킨다.
    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @Test
    void secondSignupRecoversAsEmailDuplicateEvenWhenOuterTransactionSnapshotPredatesWinnerCommit() throws Exception {
        String contestedEmail = "concurrent-signup-race@example.com";
        when(emailVerificationService.isVerified(anyString())).thenReturn(true);

        CountDownLatch aHasReadAvailable = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate repeatableReadOuterTransactionTemplate = new TransactionTemplate(transactionManager);
        repeatableReadOuterTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: 실제 운영 코드(signup)를 REPEATABLE READ 바깥 트랜잭션 안에서 그대로 호출한다.
        // signup()의 사전 검사와 동일한 조회를 먼저 실행해 이 트랜잭션의 스냅샷을 "아직 아무도 안 씀"
        // 상태로 고정시킨 뒤, B가 커밋하기를 기다렸다가 진행한다.
        Future<?> aResult = executor.submit(() -> repeatableReadOuterTransactionTemplate.execute(status -> {
            boolean takenBefore = userRepository.existsByEmail(contestedEmail);
            assertThat(takenBefore).isFalse();
            aHasReadAvailable.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            return localAuthService.signup(contestedEmail, "Password123", "동시가입A_닉네임");
        }));

        // 스레드 B: A가 스냅샷을 고정한 뒤에만 실제로 커밋해, A의 시도가 유니크 제약 위반을 겪게 만든다.
        Future<?> bResult = executor.submit(() -> {
            awaitOrFail(aHasReadAvailable, "A가 먼저 조회를 마치지 않았습니다.");
            var user = localAuthService.signup(contestedEmail, "Password123", "동시가입B_닉네임");
            bHasCommitted.countDown();
            return user;
        });

        try {
            bResult.get(10, TimeUnit.SECONDS);

            ExecutionException exception = assertThrows(
                    ExecutionException.class, () -> aResult.get(10, TimeUnit.SECONDS));

            assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) exception.getCause()).getErrorCode())
                    .isEqualTo(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
        } finally {
            executor.shutdownNow();
        }
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
