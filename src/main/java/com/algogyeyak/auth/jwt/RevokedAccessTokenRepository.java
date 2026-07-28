package com.algogyeyak.auth.jwt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
