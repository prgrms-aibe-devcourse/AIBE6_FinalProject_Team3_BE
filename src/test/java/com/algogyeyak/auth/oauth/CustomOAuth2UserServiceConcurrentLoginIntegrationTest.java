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

    // 위 테스트의 바깥 트랜잭션은 명시적 격리수준이 없어 H2 기본값(READ_COMMITTED)으로 뜨는데, 이
    // 격리수준에서는 문장마다 항상 최신 커밋을 보므로 "재조회가 stale한 스냅샷을 본다"는 문제 자체가
    // 재현되지 않는다. 운영 DB(MySQL InnoDB)의 기본 격리수준은 REPEATABLE READ라 사정이 다르다 -
    // 트랜잭션이 첫 조회 시점에 스냅샷을 고정하므로, 그 이후 다른 트랜잭션이 커밋해도 같은 트랜잭션의
    // 재조회에는 계속 안 보일 수 있다. 이 테스트는 바깥 트랜잭션 격리수준을 REPEATABLE_READ로 명시해
    // 그 조건을 그대로 재현하고, createUser()의 winner 재조회가 REQUIRES_NEW(새 스냅샷)로 분리돼
    // 있어야만 이 상황에서도 winner를 제대로 찾는다는 것을 확인한다 - 그 분리가 없으면(고친 기능을
    // 되돌리면) A의 재조회가 바깥 트랜잭션의 오래된(B 커밋 이전) 스냅샷을 그대로 써서 winner를 못
    // 찾고, 실제로는 계정이 있는데도 email_conflict로 로그인이 실패한다.
    @Test
    @Timeout(15)
    void secondLoginRecoversEvenWhenOuterTransactionSnapshotPredatesWinnerCommit() throws Exception {
        // 555는 이 클래스의 다른 테스트가, 777은 CustomOAuth2UserServiceLazyLoadingIntegrationTest가
        // 이미 쓰고 있다 - 모든 @SpringBootTest가 같은 gradle 실행 안에서 컨텍스트(및 H2 DB)를
        // 공유하고 이 테스트들은 실제로 커밋하므로, providerId가 겹치면 남의 테스트가 이미 심어둔
        // 행과 유니크 제약이 충돌해 이 테스트와 무관한 이유로 실패한다.
        long kakaoNumericId = 918_273_645L;
        String providerId = String.valueOf(kakaoNumericId);
        OAuth2User oAuth2User = kakaoOAuth2User(kakaoNumericId, "스냅샷유저", "http://img", "snapshot-race@example.com");

        CountDownLatch aHasReadEmpty = new CountDownLatch(1);
        CountDownLatch bHasCommitted = new CountDownLatch(1);
        TransactionTemplate repeatableReadOuterTransactionTemplate = new TransactionTemplate(transactionManager);
        repeatableReadOuterTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 스레드 A: 실제 운영 코드(processOAuth2User)를 REPEATABLE READ 바깥 트랜잭션 안에서 그대로
        // 호출한다. 맨 앞의 조회(운영에서 findOrCreateUser()가 하는 것과 동일)가 이 트랜잭션의
        // 스냅샷을 "아직 아무도 없음" 상태로 고정시킨 뒤, B가 커밋하기를 기다렸다가 진행한다.
        Future<User> aResult = executor.submit(() -> repeatableReadOuterTransactionTemplate.execute(status -> {
            userSocialAccountRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId);
            aHasReadEmpty.countDown();

            awaitOrFail(bHasCommitted, "B가 커밋을 완료하지 않았습니다.");

            OAuth2User result = customOAuth2UserService.processOAuth2User("kakao", oAuth2User);
            return ((CustomOAuth2User) result).getUser();
        }));

        // 스레드 B: A가 스냅샷을 고정한 뒤에만 실제로 커밋해, A의 INSERT 시도가 유니크 제약 위반을
        // 겪게 만든다.
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

        assertEquals(bUser.getId(), aUser.getId());
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
