package com.algogyeyak.auth.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

@Service
public class LocalAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public LocalAuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public User signup(String email, String rawPassword, String nickname) {
        String normalizedEmail = normalizeEmail(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.AUTH_NICKNAME_ALREADY_EXISTS);
        }

        User newUser = User.createLocalUser(normalizedEmail, passwordEncoder.encode(rawPassword), nickname);

        try {
            // CustomOAuth2UserService.createUser / RefreshTokenService.insertNewRow와 동일한 이유로
            // INSERT를 REQUIRES_NEW(별도 세션)로 분리한다 — 동시 가입 레이스로 유니크 제약에 걸려
            // saveAndFlush가 실패해도, 폐기되는 세션이 이 임시 트랜잭션뿐이도록 격리해
            // 바깥(signup()) 트랜잭션의 세션은 항상 정상 상태로 남는다.
            requiresNewTransactionTemplate.executeWithoutResult(status -> userRepository.saveAndFlush(newUser));
            return newUser;
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByEmail(normalizedEmail)) {
                throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
            }
            throw new BusinessException(ErrorCode.AUTH_NICKNAME_ALREADY_EXISTS);
        }
    }

    @Transactional(readOnly = true)
    public User login(String email, String rawPassword) {
        User user = userRepository.findByEmail(normalizeEmail(email))
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        // passwordHash가 없는 계정(소셜 전용 가입)은 계정 존재 여부를 드러내지 않도록 자격 증명
        // 오류와 동일한 메시지/코드로 처리한다.
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || !passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        return user;
    }

    // 가입/로그인 조회 기준의 이메일 대소문자·앞뒤 공백 차이로 중복 가입이 뚫리거나 로그인이
    // 실패하는 것을 막기 위해, DB에 저장/조회하는 시점에 항상 동일한 정규화 규칙을 적용한다.
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
