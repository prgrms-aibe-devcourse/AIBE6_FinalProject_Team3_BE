package com.algogyeyak.auth.jwt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 로그아웃 시점에 아직 만료되지 않은 access token을 jti 단위로 무효화 처리하기 위한 기록.
 * expiresAt은 그 access token 자체의 만료 시각과 같다 — 그 시점이 지나면 jti가 이 표에 남아있어도
 * 어차피 서명 검증 단계에서 만료로 걸러지므로 더 오래 보관할 이유가 없다.
 */
@Entity
@Table(name = "revoked_access_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedAccessToken {

    @Id
    @Column(name = "jti", nullable = false, updatable = false, length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public RevokedAccessToken(String jti, LocalDateTime expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }
}
