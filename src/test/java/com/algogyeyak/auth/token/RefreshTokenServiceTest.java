package com.algogyeyak.auth.token;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final RefreshTokenService refreshTokenService =
            new RefreshTokenService(refreshTokenRepository, transactionManager);

    private User user(Long id) {
        User user = User.createOAuthUser("test@example.com", "테스트유저", "http://img");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenValiditySeconds", 1209600L);
        when(refreshTokenRepository.saveAndFlush(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issueCreatesNewRowWhenUserHasNoExistingSession() {
        User user = user(1L);
        when(refreshTokenRepository.findByUserId(1L)).thenReturn(Optional.empty());

        String rawToken = refreshTokenService.issue(user);

        assertEquals(43, rawToken.length()); // base64url(32바이트, 패딩 없음)
        verify(refreshTokenRepository).saveAndFlush(any(RefreshToken.class));
    }

    @Test
    void issueRecoversWhenConcurrentFirstIssueHitsUniqueConstraint() {
        User user = user(1L);
        RefreshToken winner = RefreshToken.builder()
                .user(user).tokenHash("winner-hash").expiresAt(LocalDateTime.now().plusDays(1)).build();
        // 최초 조회 시점엔 아직 행이 없다고 나오지만(레이스), INSERT 시도 시 동시 요청이 먼저 커밋해서
        // user_id 유니크 제약 위반이 난다 — CustomOAuth2UserService의 동시 최초 로그인 레이스와 동일한 패턴.
        when(refreshTokenRepository.findByUserId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(refreshTokenRepository.saveAndFlush(any(RefreshToken.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        String rawToken = refreshTokenService.issue(user);

        // 유니크 제약에 걸린 뒤에도 500으로 터지지 않고, 먼저 커밋된 행을 이번 요청의 토큰으로 재회전시켜야 한다.
        assertNotEquals("winner-hash", winner.getTokenHash());
        assertEquals(43, rawToken.length());
    }

    @Test
    void issueRethrowsOriginalExceptionWhenRecoveryQueryAlsoFindsNothing() {
        User user = user(1L);
        DataIntegrityViolationException original = new DataIntegrityViolationException("unique constraint violation");
        when(refreshTokenRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(refreshTokenRepository.saveAndFlush(any(RefreshToken.class))).thenThrow(original);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> refreshTokenService.issue(user));
        assertEquals(original, thrown);
    }

    @Test
    void issueRotatesExistingRowInPlaceForSingleSession() {
        User user = user(1L);
        RefreshToken existing = RefreshToken.builder()
                .user(user).tokenHash("old-hash").expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(refreshTokenRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        String rawToken = refreshTokenService.issue(user);

        assertNotEquals("old-hash", existing.getTokenHash());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void issueProducesDifferentRawTokenOnEachCall() {
        User user = user(1L);
        when(refreshTokenRepository.findByUserId(1L)).thenReturn(Optional.empty());

        String first = refreshTokenService.issue(user);
        String second = refreshTokenService.issue(user);

        assertNotEquals(first, second);
    }

    @Test
    void rotateSucceedsAndIssuesNewTokenWhenValid() {
        User user = user(1L);
        RefreshToken stored = RefreshToken.builder()
                .user(user).tokenHash("irrelevant").expiresAt(LocalDateTime.now().plusDays(1)).build();
        // rotate()는 내부적으로 해시를 재계산하므로, 저장된 해시값을 미리 알 필요 없이
        // findByTokenHash가 호출되는 시점에 이 stored row를 반환하도록만 스텁한다.
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        RefreshTokenService.RotationResult result = refreshTokenService.rotate("presented-raw-token");

        assertEquals(user, result.user());
        assertNotEquals("presented-raw-token", result.rawToken());
    }

    @Test
    void rotateThrowsWhenTokenNotFound() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("unknown-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
    }

    @Test
    void rotateThrowsAndDeletesRowWhenExpired() {
        User user = user(1L);
        RefreshToken expired = RefreshToken.builder()
                .user(user).tokenHash("hash").expiresAt(LocalDateTime.now().minusSeconds(1)).build();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("expired-token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_EXPIRED, exception.getErrorCode());
        verify(refreshTokenRepository).delete(expired);
    }

    @Test
    void rotateThrowsAndDeletesRowWhenUserWithdrawn() {
        User user = user(1L);
        user.withdraw();
        RefreshToken stored = RefreshToken.builder()
                .user(user).tokenHash("hash").expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> refreshTokenService.rotate("token"));
        assertEquals(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, exception.getErrorCode());
        verify(refreshTokenRepository).delete(stored);
    }

    @Test
    void revokeDeletesMatchingRow() {
        User user = user(1L);
        RefreshToken stored = RefreshToken.builder()
                .user(user).tokenHash("hash").expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));

        refreshTokenService.revoke("some-token");

        verify(refreshTokenRepository).delete(stored);
    }

    @Test
    void revokeIsNoOpWhenTokenUnknown() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        refreshTokenService.revoke("unknown-token");

        verify(refreshTokenRepository, never()).delete(any());
    }

    @Test
    void replayingARotatedOutTokenFailsBecauseTheRowNoLongerMatchesItsHash() {
        User user = user(1L);
        RefreshToken stored = RefreshToken.builder()
                .user(user).tokenHash("placeholder").expiresAt(LocalDateTime.now().plusDays(1)).build();
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(stored));
        String firstRawToken = "first-token";

        refreshTokenService.rotate(firstRawToken);

        // 단일 세션이므로 회전 후에는 같은 행이 새 해시로 덮어써져 있고, DB에는 이전 토큰의 해시가 남아있지 않다.
        // 따라서 이전 토큰이 재사용되면 (실제 저장소에서는) findByTokenHash가 더 이상 이 행을 찾지 못해 거부된다.
        // 이 테스트는 stub이 아니라 rotate()가 실제로 tokenHash를 변경한다는 사실 자체를 검증한다.
        assertNotEquals("placeholder", stored.getTokenHash());
    }
}
