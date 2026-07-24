package com.algogyeyak.auth.token;

import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rotate()가 만료/탈퇴 사용자의 행을 지운 뒤 예외를 던지는데, {@code delete()} 호출과 {@code throw}가
 * 같은 {@code @Transactional} 메서드 안에 있으면 Spring이 delete까지 통째로 롤백해버려 실제로는
 * 아무것도 지워지지 않는다 — Mockito 단위 테스트는 {@code delete()}가 "호출됐는지"만 보고 실제
 * 트랜잭션 커밋 여부는 검증하지 못하므로, 실제 Spring 프록시 + 실제 DB로 직접 확인한다.
 */
@SpringBootTest
class RefreshTokenRotateDeletePersistenceIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void expiredRowIsActuallyGoneAfterRotateThrows() throws Exception {
        User user = userRepository.saveAndFlush(
                User.createOAuthUser("expired-cleanup@example.com", "만료행유저", "http://img", AuthProvider.KAKAO, "expired-1"));
        String rawToken = "already-expired-raw-token";
        refreshTokenRepository.saveAndFlush(RefreshToken.builder()
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(LocalDateTime.now().minusSeconds(1))
                .build());

        assertThrows(BusinessException.class, () -> refreshTokenService.rotate(rawToken));

        assertTrue(refreshTokenRepository.findByUserId(user.getId()).isEmpty(),
                "만료된 행은 rotate()가 던진 뒤에도 실제로 삭제되어 있어야 한다");
    }

    private static String sha256(String raw) throws Exception {
        byte[] hashed = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
    }
}
