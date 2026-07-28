package com.algogyeyak.auth.jwt;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessTokenRevocationServiceTest {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository = mock(RevokedAccessTokenRepository.class);
    private final AccessTokenRevocationService accessTokenRevocationService =
            new AccessTokenRevocationService(revokedAccessTokenRepository);

    @Test
    void revokeSavesTheJtiWithItsExpiry() {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(30);

        accessTokenRevocationService.revoke("some-jti", expiresAt);

        ArgumentCaptor<RevokedAccessToken> captor = ArgumentCaptor.forClass(RevokedAccessToken.class);
        verify(revokedAccessTokenRepository).save(captor.capture());
        assertEquals("some-jti", captor.getValue().getJti());
        assertEquals(expiresAt, captor.getValue().getExpiresAt());
    }

    // 별도 스케줄러 없이 revoke() 호출 시점에 이미 만료된 옛 기록을 함께 정리한다는 게 이 서비스의
    // 핵심 설계라서, save 전에 청소가 실제로 호출되는지를 직접 검증한다.
    @Test
    void revokeCleansUpExpiredRowsBeforeSavingTheNewOne() {
        accessTokenRevocationService.revoke("some-jti", LocalDateTime.now().plusMinutes(30));

        verify(revokedAccessTokenRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }

    @Test
    void revokeIsNoOpWhenJtiIsNull() {
        accessTokenRevocationService.revoke(null, LocalDateTime.now().plusMinutes(30));

        verifyNoInteractions(revokedAccessTokenRepository);
    }

    @Test
    void isRevokedReturnsTrueWhenJtiExistsInBlacklist() {
        when(revokedAccessTokenRepository.existsById("blacklisted-jti")).thenReturn(true);

        assertTrue(accessTokenRevocationService.isRevoked("blacklisted-jti"));
    }

    @Test
    void isRevokedReturnsFalseWhenJtiIsNotBlacklisted() {
        when(revokedAccessTokenRepository.existsById(anyString())).thenReturn(false);

        assertFalse(accessTokenRevocationService.isRevoked("clean-jti"));
    }

    @Test
    void isRevokedReturnsFalseForNullJtiWithoutQueryingTheRepository() {
        assertFalse(accessTokenRevocationService.isRevoked(null));

        verify(revokedAccessTokenRepository, never()).existsById(any());
    }
}
