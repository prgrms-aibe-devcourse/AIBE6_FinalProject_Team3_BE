package com.algogyeyak.auth.token;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Refresh Token은 Redis 없이 DB(단일 세션, 유저당 1행)로 관리한다. 원문 토큰은 저장하지 않고
 * SHA-256 해시만 저장하며, 매 회전(rotate)마다 기존 행을 새 값으로 덮어써 이전 토큰은 즉시 무효화된다.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final RefreshTokenRepository refreshTokenRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    @Value("${app.jwt.refresh-token-validity-seconds}")
    private long refreshTokenValiditySeconds;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public long getValiditySeconds() {
        return refreshTokenValiditySeconds;
    }

    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();
        String tokenHash = hash(rawToken);
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenValiditySeconds);

        refreshTokenRepository.findByUserId(user.getId())
                .ifPresentOrElse(
                        existing -> existing.rotate(tokenHash, expiresAt),
                        () -> insertNewRow(user, tokenHash, expiresAt));

        return rawToken;
    }

    // package-private: RefreshTokenConcurrentIssueIntegrationTest가 실제 이 메서드를 호출해
    // 동시 최초 발급 시나리오를 검증할 수 있도록 한다 (CustomOAuth2UserService.processOAuth2User와 동일한 이유).
    void insertNewRow(User user, String tokenHash, LocalDateTime expiresAt) {
        try {
            // INSERT를 별도(REQUIRES_NEW) 트랜잭션/세션에서 시도한다. 같은 세션에서 saveAndFlush가
            // 유니크 제약 위반으로 실패한 뒤 그 세션으로 쿼리를 이어가면 Hibernate가
            // "세션이 예외 이후 flush됨(AssertionFailure)"을 던진다 — 실제 H2로 재현 확인함
            // (RefreshTokenConcurrentIssueIntegrationTest). 실패해도 폐기되는 세션이 이 임시
            // 트랜잭션뿐이도록 분리해, 바깥(issue()) 트랜잭션의 세션은 항상 정상 상태로 남는다.
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                    refreshTokenRepository.saveAndFlush(
                            RefreshToken.builder().user(user).tokenHash(tokenHash).expiresAt(expiresAt).build()));
        } catch (DataIntegrityViolationException e) {
            // 같은 유저로 Refresh Token 최초 발급(동시 로그인 등)이 겹쳐 user_id 유니크 제약에 걸린 경우 —
            // 먼저 커밋된 행을 지금 이 요청의 토큰으로 다시 회전시킨다. 단일 세션 모델이라 마지막으로
            // 발급을 완료한 쪽이 유효해야 하며, 그래야 이 응답으로 내려줄 rawToken과 DB 상태가 일치한다.
            refreshTokenRepository.findByUserId(user.getId())
                    .orElseThrow(() -> e)
                    .rotate(tokenHash, expiresAt);
        }
    }

    // findByTokenHash가 이미 이 행에 PESSIMISTIC_WRITE 락을 쥐고 있는 채로 REQUIRES_NEW를 쓰면
    // (바깥 트랜잭션이 들고 있는 락을 안쪽 트랜잭션이 기다리는) 자기 자신과의 데드락이 난다 — 실제
    // H2로 재현 확인함. 그래서 delete는 REQUIRES_NEW로 분리하지 않고 같은 트랜잭션에서 수행하되,
    // noRollbackFor로 이 메서드가 BusinessException을 던지고 나가도 delete는 그대로 커밋되게 한다.
    @Transactional(noRollbackFor = BusinessException.class)
    public RotationResult rotate(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID));

        if (refreshToken.isExpired(LocalDateTime.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_EXPIRED);
        }

        User user = refreshToken.getUser();
        if (user.isWithdrawn()) {
            refreshTokenRepository.delete(refreshToken);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "존재하지 않거나 탈퇴한 사용자입니다.");
        }

        String newRawToken = generateRawToken();
        refreshToken.rotate(hash(newRawToken), LocalDateTime.now().plusSeconds(refreshTokenValiditySeconds));

        return new RotationResult(user, newRawToken);
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(refreshTokenRepository::delete);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " algorithm not available", e);
        }
    }

    public record RotationResult(User user, String rawToken) {
    }
}
