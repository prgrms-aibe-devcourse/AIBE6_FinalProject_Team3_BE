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
}
