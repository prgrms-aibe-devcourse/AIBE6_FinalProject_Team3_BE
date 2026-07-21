package com.algogyeyak.user.repository;

import com.algogyeyak.user.entity.AuthProvider;
import com.algogyeyak.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    Optional<User> findByEmail(String email);
}
