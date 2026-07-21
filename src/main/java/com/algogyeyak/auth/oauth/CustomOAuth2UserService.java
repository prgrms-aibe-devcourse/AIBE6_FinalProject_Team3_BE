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
