package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        return processOAuth2User(registrationId, oAuth2User);
    }

    /**
     * provider가 이미 응답한 {@link OAuth2User} 속성으로 가입 여부를 확인하고 User를 생성/재사용한다.
     * {@code super.loadUser(...)}(실제 HTTP 호출)와 분리해둔 덕에, 이 메서드는 속성 Map만 있으면
     * 테스트에서 HTTP 목킹 없이 바로 호출해 검증할 수 있다.
     */
    OAuth2User processOAuth2User(String registrationId, OAuth2User oAuth2User) {
        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, oAuth2User.getAttributes());
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());

        String nickname = userInfo.getNickname() != null
                ? userInfo.getNickname()
                : provider.name().toLowerCase() + "_" + userInfo.getProviderId();

        User user = findOrCreateUser(provider, userInfo, nickname);

        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }

    private User findOrCreateUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname) {
        return userRepository.findByProviderAndProviderId(provider, userInfo.getProviderId())
                .map(existing -> {
                    existing.updateProfile(nickname, userInfo.getProfileImageUrl());
                    return existing;
                })
                .orElseGet(() -> createUser(provider, userInfo, nickname));
    }

    private User createUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname) {
        try {
            // saveAndFlush로 이 자리에서 즉시 INSERT를 실행시켜, 유니크 제약 위반이
            // (엔티티의 ID 생성 전략과 무관하게) 반드시 이 catch에서 잡히도록 보장한다.
            return userRepository.saveAndFlush(
                    User.createOAuthUser(
                            userInfo.getEmail(),
                            nickname,
                            userInfo.getProfileImageUrl(),
                            provider,
                            userInfo.getProviderId()
                    )
            );
        } catch (DataIntegrityViolationException e) {
            // 같은 provider+providerId로 동시에 첫 로그인이 들어와 유니크 제약에 걸린 경우 —
            // 먼저 커밋된 쪽의 row를 그대로 사용한다 (드문 동시 최초 로그인 레이스 대비).
            return userRepository.findByProviderAndProviderId(provider, userInfo.getProviderId())
                    .orElseThrow(() -> e);
        }
    }
}
