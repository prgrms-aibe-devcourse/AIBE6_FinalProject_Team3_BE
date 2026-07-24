package com.algogyeyak.user.repository;

import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    // 본인 닉네임은 중복 검사에서 제외하기 위한 메서드
    boolean existsByNicknameAndIdNot(String nickname, Long id);
}
