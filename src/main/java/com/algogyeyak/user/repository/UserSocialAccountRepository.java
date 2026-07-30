package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {

    Optional<UserSocialAccount> findByProviderAndProviderId(AuthProvider provider, String providerId);

    boolean existsByUserId(Long userId);
}
