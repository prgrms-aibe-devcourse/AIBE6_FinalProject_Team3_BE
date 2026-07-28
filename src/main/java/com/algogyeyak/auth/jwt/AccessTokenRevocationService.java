package com.algogyeyak.auth.jwt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 로그아웃된 access token(jti)을 자연 만료 시각까지만 블랙리스트로 들고 있는다. 별도 배치/스케줄러
 * 없이, revoke() 호출 시점에 이미 만료된 옛 기록을 함께 정리한다 — 로그아웃이 뜸한 서비스 규모에서는
 * 이 정도 지연 청소로 테이블이 무한정 커지지 않는다.
 */
@Service
public class AccessTokenRevocationService {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    public AccessTokenRevocationService(RevokedAccessTokenRepository revokedAccessTokenRepository) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
    }

    @Transactional
    public void revoke(String jti, LocalDateTime expiresAt) {
        if (jti == null) {
            return;
        }

        revokedAccessTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        revokedAccessTokenRepository.save(new RevokedAccessToken(jti, expiresAt));
    }

    public boolean isRevoked(String jti) {
        return jti != null && revokedAccessTokenRepository.existsById(jti);
    }
}
