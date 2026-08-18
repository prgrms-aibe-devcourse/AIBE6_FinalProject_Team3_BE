package com.algogyeyak.auth.service;

import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 회원가입 전(계정이 아직 없는) 이메일 소유권 확인. Redis에 이메일별로 코드 해시/시도 횟수/발송
 * 쿨다운/인증 완료 여부를 저장한다 - RefreshTokenService와 동일하게 원문 코드는 저장하지 않는다.
 *
 * <p>키 프리픽스는 {@code auth:email-verify:*} - RefreshTokenService의 {@code auth:refresh-token:*}와
 * 같은 "도메인:용도:식별자" 컨벤션을 따른다.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String CODE_HASH_KEY_PREFIX = "auth:email-verify:code-hash:";
    private static final String ATTEMPTS_KEY_PREFIX = "auth:email-verify:attempts:";
    private static final String COOLDOWN_KEY_PREFIX = "auth:email-verify:cooldown:";
    private static final String VERIFIED_KEY_PREFIX = "auth:email-verify:verified:";

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.email-verification.code-validity-seconds}")
    private long codeValiditySeconds;

    @Value("${app.email-verification.resend-cooldown-seconds}")
    private long resendCooldownSeconds;

    @Value("${app.email-verification.max-attempts}")
    private int maxAttempts;

    @Value("${app.email-verification.verified-ticket-validity-seconds}")
    private long verifiedTicketValiditySeconds;

    public EmailVerificationService(
            StringRedisTemplate redisTemplate, UserRepository userRepository, EmailService emailService) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    public void requestCode(String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        // 쿨다운 확인을 계정 존재 확인보다 먼저 해야 한다 - 순서가 반대였을 때는 "이미 가입된
        // 이메일"로 확정되는 분기가 이 메서드의 다른 모든 결과(미가입 이메일의 쿨다운, 코드
        // 확인의 maxAttempts)와 달리 아무 속도 제한도 받지 않아, 이 엔드포인트가 원래도 의도된
        // 이메일-존재 오라클이라는 점을 감안해도 무제한 속도로 이메일을 열거할 수 있었다.
        String cooldownKey = cooldownKey(normalizedEmail);
        Boolean cooldownSet;
        try {
            cooldownSet = redisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(resendCooldownSeconds));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }
        if (!Boolean.TRUE.equals(cooldownSet)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_VERIFICATION_TOO_MANY_REQUESTS);
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_VERIFICATION_EMAIL_ALREADY_EXISTS);
        }

        String code = generateCode();
        try {
            redisTemplate.opsForValue()
                    .set(codeHashKey(normalizedEmail), hash(code), Duration.ofSeconds(codeValiditySeconds));
            // 새 코드를 발급하면 이전 코드에 대한 시도 횟수는 더 이상 의미가 없다 - 리셋한다.
            redisTemplate.delete(attemptsKey(normalizedEmail));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        try {
            emailService.sendVerificationCode(normalizedEmail, code);
        } catch (MailException e) {
            log.error("이메일 인증번호 발송 실패 email={}", normalizedEmail, e);
            // 쿨다운은 실제 발송 성공을 전제로 한 제한이다 - 발송 자체가 서버 쪽 이유(SMTP 일시
            // 장애 등)로 실패했는데 쿨다운만 그대로 남으면, 사용자는 코드/링크를 받지도 못한 채
            // 서버 잘못으로 60초를 그냥 기다려야 한다. 발송 실패는 사용자 잘못이 아니므로 쿨다운을
            // 풀어 즉시 재시도할 수 있게 한다.
            releaseCooldownBestEffort(cooldownKey, normalizedEmail);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    private void releaseCooldownBestEffort(String cooldownKey, String normalizedEmail) {
        try {
            redisTemplate.delete(cooldownKey);
        } catch (DataAccessException e) {
            log.warn("메일 발송 실패 후 쿨다운 해제 실패(TTL로 자연 정리됨) email={}", normalizedEmail, e);
        }
    }

    public void confirmCode(String email, String code) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        String codeHashKeyName = codeHashKey(normalizedEmail);
        String attemptsKeyName = attemptsKey(normalizedEmail);

        String storedHash;
        Long attempts;
        try {
            storedHash = redisTemplate.opsForValue().get(codeHashKeyName);
            attempts = storedHash != null ? redisTemplate.opsForValue().increment(attemptsKeyName) : null;
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        // 코드 자체가 없으면(발송한 적 없음/만료됨) 시도 횟수를 셀 필요 없이 바로 거부한다.
        if (storedHash == null) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID);
        }

        // attempts 키가 이번에 처음 생성됐다면(코드 발급 이후 첫 시도) 코드와 동일한 TTL을 맞춰준다 -
        // 안 그러면 attempts 키가 코드보다 오래 살아남아, 코드가 자연 만료된 뒤에도 예전 시도 횟수가
        // 남아 있다가 다음에 새로 발급된 코드의 attemptsKey를 (delete가 이미 지웠어야 하는데도 타이밍상)
        // 오염시킬 수 있다.
        if (attempts != null && attempts == 1L) {
            try {
                redisTemplate.expire(attemptsKeyName, Duration.ofSeconds(codeValiditySeconds));
            } catch (DataAccessException e) {
                log.warn("attempts 키 TTL 설정 실패(기능에는 영향 없음) email={}", normalizedEmail, e);
            }
        }

        if (attempts != null && attempts > maxAttempts) {
            // 시도 횟수를 초과하면 코드 자체를 무효화해 사용자가 새로 발급받도록 강제한다.
            try {
                redisTemplate.delete(codeHashKeyName);
            } catch (DataAccessException e) {
                log.warn("시도 횟수 초과 코드 정리 실패(TTL로 자연 정리됨) email={}", normalizedEmail, e);
            }
            throw new BusinessException(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID);
        }

        if (!storedHash.equals(hash(code))) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_VERIFICATION_CODE_INVALID);
        }

        try {
            redisTemplate.delete(codeHashKeyName);
            redisTemplate.delete(attemptsKeyName);
            redisTemplate.opsForValue()
                    .set(verifiedKey(normalizedEmail), "1", Duration.ofSeconds(verifiedTicketValiditySeconds));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }
    }

    /** signup()이 계정을 만들기 전에 이 이메일의 인증 완료 여부를 확인한다. */
    public boolean isVerified(String normalizedEmail) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(verifiedKey(normalizedEmail)));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }
    }

    /** signup() 성공 후 인증 완료 티켓을 소비(삭제)한다 - 실패 시(닉네임 중복 등) 재시도할 수 있도록 남겨둔다. */
    public void consumeVerified(String normalizedEmail) {
        try {
            redisTemplate.delete(verifiedKey(normalizedEmail));
        } catch (DataAccessException e) {
            log.warn("이메일 인증 완료 티켓 정리 실패(TTL로 자연 정리됨) email={}", normalizedEmail, e);
        }
    }

    private static String generateCode() {
        int code = SECURE_RANDOM.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private static String codeHashKey(String email) {
        return CODE_HASH_KEY_PREFIX + email;
    }

    private static String attemptsKey(String email) {
        return ATTEMPTS_KEY_PREFIX + email;
    }

    private static String cooldownKey(String email) {
        return COOLDOWN_KEY_PREFIX + email;
    }

    private static String verifiedKey(String email) {
        return VERIFIED_KEY_PREFIX + email;
    }

    private static String hash(String rawCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(rawCode.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " algorithm not available", e);
        }
    }

    private static BusinessException redisUnavailable(DataAccessException cause) {
        log.error("Redis 장애로 이메일 인증 처리 실패", cause);
        return new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE);
    }
}
