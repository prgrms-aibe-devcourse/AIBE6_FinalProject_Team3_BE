package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomOAuth2UserServiceTest#reusesWinnerRowWhenConcurrentFirstLoginHitsUniqueConstraint}는
 * Mockito로 제어 흐름만 검증한다. 이 테스트는 {@code UserServiceConcurrentNicknameChangeIntegrationTest}와
 * 동일한 기법으로, 실제 H2 DB + REPEATABLE READ 트랜잭션에서 "최초 조회 통과 후 커밋 시점 레이스 →
 * 유니크 제약 위반 → 복구 조회"가 바깥 트랜잭션의 스냅샷에 흔들리지 않는지 직접 검증한다.
 *
 * 스레드 A는 손으로 재구현한 로직이 아니라 실제 {@link CustomOAuth2UserService#processOAuth2User}를
 * 그대로 호출한다 - "먼저 읽고 대기" 지점을 코드 밖에서 강제로 만들 수 없으므로, A의 바깥 트랜잭션을
 * REPEATABLE READ로 걸고 production 코드가 내부에서 실행할 것과 동일한 조회를 먼저 한 번 실행해
 * 스냅샷을 "아직 아무도 없음" 상태로 고정시킨다. 이후 processOAuth2User() 내부에서 실행되는 모든
 * 조회(findOrCreateUser()의 재조회 포함)는 REQUIRES_NEW로 격리되지 않는 한 이 고정된 스냅샷을
 * 그대로 물려받으므로, 운영 코드를 전혀 손대지 않고 동일한 레이스를 재현한다.
 */
@SpringBootTest
class CustomOAuth2UserServiceConcurrentLoginIntegrationTest {

    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSocialAccountRepository userSocialAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static OAuth2User kakaoOAuth2User(long id, String nickname, String profileImageUrl, String email) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("nickname", nickname);
        profile.put("profile_image_url", profileImageUrl);

        // is_email_verified=false로 둔다 - true였다면 A가 대기에서 풀린 뒤 findVerifiedEmailMatch()가
        // B가 이미 커밋한 유저를 이메일로 곧장 찾아버려, 이 테스트가 검증하려는 createUser()의
        // 유니크 제약 위반 복구 경로 자체를 안 타게 된다.
        Map<String, Object> kakaoAccount = new HashMap<>();
        kakaoAccount.put("email", email);
        kakaoAccount.put("is_email_verified", false);
        kakaoAccount.put("profile", profile);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", id);
        attributes.put("kakao_account", kakaoAccount);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new DefaultOAuth2User(authorities, attributes, "id");
    }

    @Test
    @Timeout(15)
    void secondLoginRecoversEvenWhenOuterTransactionSnapshotPredatesWinnerCommit() throws Exception {
        // KakaoOAuth2UserInfo는 attributes의 숫자 "id"를 문자열로 변환해 providerId로 쓴다.
        long kakaoNumericId = 555L;
        String providerId = String.valueOf(kakaoNumericId);
        OAuth2User oAuth2User = kakaoOAuth2User(kakaoNumericId, "동시로그인유저", "http://img", "concurrent-login@example.com");

        CountDownLatch aHasReadEmpty = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate repeatableReadOuterTransactionTemplate = new TransactionTemplate(transactionManager);
        repeatableReadOuterTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: findOrCreateUser()가 내부에서 실행할 것과 동일한 조회를 REPEATABLE READ 바깥
        // 트랜잭션에서 먼저 실행해 스냅샷을 고정한 뒤, B가 커밋하기를 기다렸다가 실제
        // processOAuth2User()를 그대로 호출한다 - 이후 내부의 재조회는 REQUIRES_NEW가 아닌 한
        // 전부 이 고정된(stale) 스냅샷을 물려받는다.
        Future<User> aResult = executor.submit(() -> repeatableReadOuterTransactionTemplate.execute(status -> {
            boolean existsBefore = userSocialAccountRepository
                    .findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                    .isPresent();
            assertFalse(existsBefore, "A가 조회했을 때 이미 계정이 존재합니다.");
            aHasReadEmpty.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            OAuth2User result = customOAuth2UserService.processOAuth2User("kakao", oAuth2User);
            return ((CustomOAuth2User) result).getUser();
        }));

        // 스레드 B: A가 먼저 조회를 마치고 대기 상태에 들어간 뒤에만 실제 processOAuth2User()를
        // 실행해 정상적으로 새 User를 커밋한다 — 운영 코드에서 "동시에 첫 로그인이 들어온 다른
        // 요청"에 해당하며, 별도 트랜잭션 래핑 없이 그대로 호출한다.
        Future<User> bResult = executor.submit(() -> {
            awaitOrFail(aHasReadEmpty, "A가 먼저 조회를 마치지 않았습니다.");
            OAuth2User result = customOAuth2UserService.processOAuth2User("kakao", oAuth2User);
            User committed = ((CustomOAuth2User) result).getUser();
            bHasCommitted.countDown();
            return committed;
        });

        User aUser;
        User bUser;
        try {
            aUser = aResult.get(10, TimeUnit.SECONDS);
            bUser = bResult.get(10, TimeUnit.SECONDS);
        } finally {
            // get()이 타임아웃/예외로 먼저 던지면 shutdown()이 실행되지 않아 스레드가 남을 수 있다 -
            // 정상/실패 어느 쪽이든 반드시 정리되도록 finally에서 shutdownNow()로 강제 종료한다.
            executor.shutdownNow();
        }

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // 세션 오염 없이 예외가 전파되지 않고 회복되어야 하고, 두 결과 모두 같은(먼저 커밋된) row를 가리켜야 한다.
        assertEquals(bUser.getId(), aUser.getId());

        transactionTemplate.executeWithoutResult(status -> {
            // 이 테스트의 kakaoOAuth2User 픽스처는 is_email_verified=false라 email이 검증 안 됨
            // (null 저장)으로 처리되므로, email 대신 이 테스트에서만 유일한 nickname으로 식별한다.
            long userCount = userRepository.findAll().stream()
                    .filter(u -> "동시로그인유저".equals(u.getNickname()))
                    .count();
            assertEquals(1, userCount);

            // User뿐 아니라 그 첫 UserSocialAccount도 중복 없이 정확히 한 행만 있어야 한다 —
            // 둘은 항상 같은 트랜잭션에서 함께 커밋되므로 여기서도 짝이 맞는지 같이 확인한다.
            long socialAccountCount = userSocialAccountRepository.findAll().stream()
                    .filter(a -> AuthProvider.KAKAO.equals(a.getProvider()) && providerId.equals(a.getProviderId()))
                    .count();
            assertEquals(1, socialAccountCount);
        });
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
