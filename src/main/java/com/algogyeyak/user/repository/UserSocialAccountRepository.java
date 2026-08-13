package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {

    // user는 지연 로딩(@ManyToOne LAZY)이라, JOIN FETCH 없이 조회하면 트랜잭션(요청) 종료 후
    // user의 필드(예: role)에 접근할 때 LazyInitializationException이 난다 — 이 조회 결과의 User는
    // CustomOAuth2User에 담겨 Spring Security 인증 처리(트랜잭션 밖)까지 전달되므로 항상 함께 로딩한다.
    @Query("SELECT s FROM UserSocialAccount s JOIN FETCH s.user WHERE s.provider = :provider AND s.providerId = :providerId")
    Optional<UserSocialAccount> findByProviderAndProviderId(
            @Param("provider") AuthProvider provider, @Param("providerId") String providerId);

    boolean existsByUserId(Long userId);

    // uk_social_user_provider(한 User가 같은 provider를 두 개 연동할 수 없다는 제약) 위반인지
    // 판별하기 위한 조회 - linkNewSocialAccount()의 유니크 제약 위반 복구가 uk_social_provider_provider_id
    // (같은 소셜 계정을 두 User가 동시에 연동)와 이 제약을 구분하는 데 쓴다.
    boolean existsByUserIdAndProvider(Long userId, AuthProvider provider);

    // 회원 탈퇴 시 OAuth 연동 정보 정리용 - 남겨두면 (provider, provider_id) unique 제약 때문에
    // 같은 소셜 계정으로 재가입하려는 탈퇴자를 영구히 막게 된다.
    void deleteAllByUserId(Long userId);
}
