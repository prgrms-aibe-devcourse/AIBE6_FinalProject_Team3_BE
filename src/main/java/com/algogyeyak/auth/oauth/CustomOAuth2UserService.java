package com.algogyeyak.auth.oauth;

import com.algogyeyak.user.entity.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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

        User user = userRepository.findByProviderAndProviderId(provider, userInfo.getProviderId())
                .map(existing -> {
                    existing.updateProfile(nickname, userInfo.getProfileImageUrl());
                    return existing;
                })
                .orElseGet(() -> userRepository.save(
                        User.createOAuthUser(
                                userInfo.getEmail(),
                                nickname,
                                userInfo.getProfileImageUrl(),
                                provider,
                                userInfo.getProviderId()
                        )
                ));

        return new CustomOAuth2User(user, oAuth2User.getAttributes());
    }
}
