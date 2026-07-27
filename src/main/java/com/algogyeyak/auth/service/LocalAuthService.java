package com.algogyeyak.auth.service;

import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocalAuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate requiresNewTransactionTemplate;

    @Value("${app.dev-login.email}")
    private String devLoginEmail;

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
        String normalizedEmail = EmailNormalizer.normalize(email);

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
        User user = userRepository.findByEmail(EmailNormalizer.normalize(email))
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

    /**
     * 로그인된 사용자 본인의 비밀번호를 설정/변경한다. 구글/카카오로만 가입한 계정은 OAuth가 이미
     * 이메일 소유권을 검증해준 상태이므로, 로그인된 상태에서 비밀번호를 새로 설정하면 그 이메일로
     * 로컬 로그인도 바로 가능해진다({@link #login}은 provider와 무관하게 email+passwordHash만 본다).
     * 이미 비밀번호가 있는 계정은 탈취된 세션 하나만으로 비밀번호가 바뀌는 것을 막기 위해 현재
     * 비밀번호 확인을 요구한다.
     */
    @Transactional
    public void setPassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "존재하지 않거나 탈퇴한 사용자입니다."));

        // 개발용 dev-login 관리자 계정(AdminAccountSeeder)은 passwordHash가 항상 null이어야
        // dev-login 스위치가 꺼졌을 때 이 계정으로 들어올 방법이 완전히 사라진다. 여기서 비밀번호
        // 설정을 허용하면 dev-login 세션 하나로 이 계정에 영구적인 로컬 로그인 수단을 만들어버려
        // 그 안전장치가 무의미해지므로, 이 이메일에 대해서는 통째로 막는다. devLoginEmail이 null이면
        // (단위 테스트처럼 @Value가 주입되지 않은 경우) 이 검사 자체를 건너뛴다.
        String normalizedDevLoginEmail = EmailNormalizer.normalize(devLoginEmail);
        if (normalizedDevLoginEmail != null
                && normalizedDevLoginEmail.equals(EmailNormalizer.normalize(user.getEmail()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 계정은 비밀번호를 설정할 수 없습니다.");
        }

        String existingHash = user.getPasswordHash();
        if (existingHash != null
                && (currentPassword == null || !passwordEncoder.matches(currentPassword, existingHash))) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "현재 비밀번호가 올바르지 않습니다.");
        }

        user.updatePasswordHash(passwordEncoder.encode(newPassword));
    }
}
