package com.algogyeyak.user.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.entity.User;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * registerProfile()의 사전 검사(existsByUserId)는 온보딩 화면에서 중복 클릭 등으로 동시에 두
 * 요청이 들어오면 둘 다 통과할 수 있다 - 이전에는 그 뒤의 INSERT가 유니크 제약에 걸려도 잡는
 * 코드가 전혀 없어 원인 불명의 500으로 샜다. UserServiceConcurrentNicknameChangeIntegrationTest와
 * 동일한 방식으로, 실제 H2 DB + REPEATABLE READ 트랜잭션에서 재현해 정확한
 * USER_PROFILE_ALREADY_EXISTS 409로 복구되는지 검증한다.
 */
@SpringBootTest
class UserServiceConcurrentProfileRegistrationIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ProfileRegisterRequest registerRequest() {
        ProfileRegisterRequest request = new ProfileRegisterRequest();
        ReflectionTestUtils.setField(request, "interestRegion", "서울시 강남구");
        return request;
    }

    @Test
    void secondConcurrentRegistrationForTheSameUserRecoversAsAlreadyRegistered() throws Exception {
        User user = userRepository.saveAndFlush(
                User.createLocalUser("concurrent-profile-register@example.com", "encoded-hash", "동시등록레이스닉네임"));
        Long userId = user.getId();

        CountDownLatch aHasReadNotRegistered = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate repeatableReadOuterTransactionTemplate = new TransactionTemplate(transactionManager);
        repeatableReadOuterTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: 실제 운영 코드(registerProfile)를 REPEATABLE READ 바깥 트랜잭션 안에서 그대로
        // 호출한다. registerProfile()의 사전 검사와 동일한 조회를 먼저 실행해 이 트랜잭션의
        // 스냅샷을 "아직 등록 안 됨" 상태로 고정시킨 뒤, B가 커밋하기를 기다렸다가 진행한다.
        Future<?> aResult = executor.submit(() -> repeatableReadOuterTransactionTemplate.execute(status -> {
            User managed = userRepository.findById(userId).orElseThrow();
            aHasReadNotRegistered.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            return userService.registerProfile(managed.getId(), registerRequest());
        }));

        // 스레드 B: A가 읽은 뒤에만 실제로 커밋해, A의 등록 시도가 유니크 제약 위반을 겪게 만든다.
        Future<?> bResult = executor.submit(() -> {
            awaitOrFail(aHasReadNotRegistered, "A가 먼저 조회를 마치지 않았습니다.");
            var response = userService.registerProfile(userId, registerRequest());
            bHasCommitted.countDown();
            return response;
        });

        try {
            bResult.get(10, TimeUnit.SECONDS);

            ExecutionException exception = assertThrows(
                    ExecutionException.class, () -> aResult.get(10, TimeUnit.SECONDS));

            assertThat(exception.getCause()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) exception.getCause()).getErrorCode())
                    .isEqualTo(ErrorCode.USER_PROFILE_ALREADY_EXISTS);
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
