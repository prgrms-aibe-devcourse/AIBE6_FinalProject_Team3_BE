package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code UserSocialAccount.user}는 지연 로딩(@ManyToOne LAZY)이다. 이미 연동된 provider로 재로그인하는
 * 경로는 {@code UserSocialAccount}로 조회한 User를 그대로 {@link CustomOAuth2User}에 담아 Spring
 * Security의 인증 처리(트랜잭션 밖)까지 넘기므로, 그 User가 JOIN FETCH 없이 지연 프록시로 남아있으면
 * {@code getAuthorities()}(내부에서 {@code user.getRole()} 접근) 시점에 LazyInitializationException이
 * 난다 — 이 클래스는 이 회귀를 트랜잭션 없는 테스트 메서드에서 직접 재현해 방지한다.
 */
@SpringBootTest
class CustomOAuth2UserServiceLazyLoadingIntegrationTest {

    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSocialAccountRepository userSocialAccountRepository;

    private static OAuth2User kakaoOAuth2User(long id, String nickname, String email) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("nickname", nickname);

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

    // 이 테스트 메서드 자체가 @Transactional이 아니므로, 아래 두 리포지토리 호출은 각자 자기만의
    // 짧은 트랜잭션/세션에서 실행되고 즉시 커밋/종료된다 — 실제 loadUser() 호출 이후 Spring
    // Security가 트랜잭션 밖에서 getAuthorities()를 부르는 상황과 동일한 조건을 만든다.
    @Test
    void reLoginThroughAlreadyLinkedProviderDoesNotFailWithLazyInitializationOutsideTransaction() {
        User user = userRepository.saveAndFlush(User.createOAuthUser("lazy-login@example.com", "지연로딩유저", null));
        // KakaoOAuth2UserInfo.getProviderId()는 kakao_account의 숫자 "id"를 String.valueOf로 변환한
        // 값을 쓰므로, 아래 kakaoOAuth2User(777L, ...)와 반드시 같은 값("777")으로 미리 연동해둬야
        // findByProviderAndProviderId가 이 UserSocialAccount를 찾아 "이미 연동된 provider로 재로그인"
        // 경로를 탄다.
        userSocialAccountRepository.saveAndFlush(UserSocialAccount.of(user, AuthProvider.KAKAO, "777"));

        OAuth2User result = customOAuth2UserService.processOAuth2User(
                "kakao", kakaoOAuth2User(777L, "지연로딩유저", "lazy-login@example.com"));

        CustomOAuth2User customOAuth2User = (CustomOAuth2User) result;
        List<GrantedAuthority> authorities = assertDoesNotThrow(customOAuth2User::getAuthorities);
        assertEquals("ROLE_USER", authorities.get(0).getAuthority());
    }
}
