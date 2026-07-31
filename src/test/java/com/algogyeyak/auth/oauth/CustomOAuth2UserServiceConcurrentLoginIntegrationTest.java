package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomOAuth2UserServiceTest#reusesWinnerRowWhenConcurrentFirstLoginHitsUniqueConstraint}는
 * Mockito로 제어 흐름만 검증한다. 이 테스트는 {@link RefreshTokenConcurrentIssueIntegrationTest}와
 * 동일한 이유로, 실제 H2 DB + 실제 세션에서 "동시 최초 로그인 → 유니크 제약 위반 → 복구"가
 * 안전한지(세션이 오염되지 않는지) 직접 검증한다.
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

        Map<String, Object> kakaoAccount = new HashMap<>();
        kakaoAccount.put("email", email);
        kakaoAccount.put("is_email_verified", true);
        kakaoAccount.put("profile", profile);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", id);
        attributes.put("kakao_account", kakaoAccount);

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new DefaultOAuth2User(authorities, attributes, "id");
    }

    @Test
    @Timeout(15)
    void secondLoginRecoversAfterRealUniqueConstraintViolation() throws Exception {
        // KakaoOAuth2UserInfo는 attributes의 숫자 "id"를 문자열로 변환해 providerId로 쓰므로,
        // 스레드 A가 수동으로 재현하는 쪽도 반드시 같은 값을 써야 실제로 provider+providerId가 충돌한다.
        long kakaoNumericId = 555L;
        String providerId = String.valueOf(kakaoNumericId);
        OAuth2User oAuth2User = kakaoOAuth2User(kakaoNumericId, "동시로그인유저", "http://img", "concurrent-login@example.com");

        CountDownLatch aHasReadEmpty = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: createUser()와 정확히 같은 구조(바깥 트랜잭션 조회 → 대기 → REQUIRES_NEW로 격리된
        // User+UserSocialAccount INSERT 시도 → 실패하면 바깥 트랜잭션의 세션으로 재조회)를 재현한다.
        Future<User> aResult = executor.submit(() -> transactionTemplate.execute(status -> {
            userSocialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId); // 아직 아무도 없다.
            aHasReadEmpty.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            User newUser = User.createOAuthUser(
                    "concurrent-login@example.com", "동시로그인유저", "http://img");
            try {
                requiresNewTransactionTemplate.executeWithoutResult(innerStatus -> {
                    userRepository.saveAndFlush(newUser);
                    userSocialAccountRepository.saveAndFlush(UserSocialAccount.of(newUser, AuthProvider.KAKAO, providerId));
                });
                return newUser;
            } catch (DataIntegrityViolationException e) {
                return userSocialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
                        .map(UserSocialAccount::getUser)
                        .orElseThrow(() -> e);
            }
        }));

        // 스레드 B: A가 먼저 읽고 대기 상태에 들어간 뒤에만 실제 processOAuth2User()를 실행해
        // 정상적으로 새 User를 커밋한다 — 운영 코드에서 "동시에 첫 로그인이 들어온 다른 요청"에 해당한다.
        Future<User> bResult = executor.submit(() -> {
            awaitOrFail(aHasReadEmpty, "A가 먼저 조회를 마치지 않았습니다.");
            OAuth2User result = customOAuth2UserService.processOAuth2User("kakao", oAuth2User);
            User committed = ((CustomOAuth2User) result).getUser();
            bHasCommitted.countDown();
            return committed;
        });

        User aUser = aResult.get(10, TimeUnit.SECONDS);
        User bUser = bResult.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 세션 오염 없이 예외가 전파되지 않고 회복되어야 하고, 두 결과 모두 같은(먼저 커밋된) row를 가리켜야 한다.
        assertEquals(bUser.getId(), aUser.getId());

        transactionTemplate.executeWithoutResult(status -> {
            // 이 테스트의 kakaoOAuth2User 픽스처는 is_email_verified를 채우지 않아 실제로는
            // email이 검증 안 됨(null 저장)으로 처리되므로, email 대신 이 테스트에서만 유일한
            // nickname으로 식별한다.
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
