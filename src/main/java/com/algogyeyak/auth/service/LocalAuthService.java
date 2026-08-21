package com.algogyeyak.auth.service;

import com.algogyeyak.auth.jwt.UserAuthStatusCacheService;
import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LocalAuthService {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthService.class);

    // login()이 계정 없음/소셜 전용 계정(passwordHash 없음)일 때 BCrypt 비교 자체를 건너뛰면,
    // 그 경로가 실제 비밀번호 불일치 경로보다 눈에 띄게 빨라 응답 시간만으로 "이 이메일에 로컬
    // 비밀번호가 있는지"를 알아낼 수 있다(에러 메시지/코드는 이미 동일하게 처리돼 있었지만 처리
    // 시간은 갈려 있었다). 이 값은 실제 사용자의 비밀번호가 아니라 형식만 유효한 더미 해시로,
    // 그 경로에서도 항상 같은 비용의 BCrypt 비교를 한 번 수행해 시간차를 없애는 데만 쓰인다.
    private static final String DUMMY_PASSWORD_HASH_FOR_TIMING_SAFETY =
            "$2a$10$CwTycUXWue0Thq9StjUM0uJ8Q0kQAr9Z6FkQx9F.C2t2CSqDNXW0e";

    private static final String LOGIN_ATTEMPTS_KEY_PREFIX = "auth:login:attempts:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final StringRedisTemplate redisTemplate;
    private final UserAuthStatusCacheService userAuthStatusCacheService;

    @Value("${app.dev-login.email}")
    private String devLoginEmail;

    @Value("${app.login.max-attempts}")
    private int loginMaxAttempts;

    @Value("${app.login.lockout-window-seconds}")
    private long loginLockoutWindowSeconds;

    public LocalAuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            PlatformTransactionManager transactionManager,
            StringRedisTemplate redisTemplate,
            UserAuthStatusCacheService userAuthStatusCacheService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.redisTemplate = redisTemplate;
        this.userAuthStatusCacheService = userAuthStatusCacheService;
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
        // 이메일 인증(POST /auth/email-verification/{request,confirm})을 먼저 마쳐야 한다 - 인증
        // 없이 바로 가입을 시도했거나, 인증 완료 후 유효시간(30분)이 지난 뒤 뒤늦게 가입을 완료하려는
        // 경우 모두 여기서 막는다.
        if (!emailVerificationService.isVerified(normalizedEmail)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
        }

        User newUser = User.createLocalUser(normalizedEmail, passwordEncoder.encode(rawPassword), nickname);

        try {
            // CustomOAuth2UserService.createUser / RefreshTokenService.insertNewRow와 동일한 이유로
            // INSERT를 REQUIRES_NEW(별도 세션)로 분리한다 — 동시 가입 레이스로 유니크 제약에 걸려
            // saveAndFlush가 실패해도, 폐기되는 세션이 이 임시 트랜잭션뿐이도록 격리해
            // 바깥(signup()) 트랜잭션의 세션은 항상 정상 상태로 남는다.
            requiresNewTransactionTemplate.executeWithoutResult(status -> userRepository.saveAndFlush(newUser));
            // 인증 완료 티켓은 이 계정 생성이 실제로 성공했을 때만 소비한다 - 위에서 닉네임 중복 등으로
            // 실패하면 티켓을 남겨둬 사용자가 이메일 인증부터 다시 하지 않고 나머지 폼만 고쳐 재시도할
            // 수 있게 한다.
            emailVerificationService.consumeVerified(normalizedEmail);
            return newUser;
        } catch (DataIntegrityViolationException e) {
            // 이메일이 원인이 아니면 무조건 닉네임이 원인이라고 단정하지 않는다 - 실제로
            // existsByNickname()까지 재확인한다. 지금은 유니크 제약이 이메일/닉네임 둘뿐이라
            // 실질적으로 거의 항상 닉네임이 맞겠지만, 재확인 없이 단정하면 제약이 하나 더
            // 늘어나거나 일시적인 DB 이슈로 같은 예외가 나는 경우에도 항상 "닉네임 중복"이라는
            // 틀린 응답이 나갈 수 있다.
            //
            // 이 재확인도 REQUIRES_NEW(새 스냅샷)에서 해야 한다 - 이 메서드의 바깥(signup())
            // 트랜잭션은 이미 existsByEmail/existsByNickname을 먼저 읽어 스냅샷을 확보해둔 상태라
            // (MySQL InnoDB 기본 격리수준 REPEATABLE READ 기준), 그 스냅샷으로 재확인하면 방금
            // 경쟁에서 이긴 다른 트랜잭션의 커밋이 안 보여 stale한 "중복 아님" 결과가 나오고,
            // 원래 DataIntegrityViolationException이 그대로 다시 던져져 500으로 샐 수 있다.
            if (Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status -> userRepository.existsByEmail(normalizedEmail)))) {
                throw new BusinessException(ErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
            }
            if (Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status -> userRepository.existsByNickname(nickname)))) {
                throw new BusinessException(ErrorCode.AUTH_NICKNAME_ALREADY_EXISTS);
            }
            throw e;
        }
    }

    /**
     * signup() 직후 refresh token 발급(Redis 장애 등)이 실패해 세션을 만들지 못했을 때, 방금
     * 커밋된 계정을 되돌리는 보상 트랜잭션이다({@link com.algogyeyak.auth.controller.AuthController#signup}
     * 참고). 세션 없이 "가입만 된" 계정이 그대로 남으면, 재시도 시 AUTH_EMAIL_ALREADY_EXISTS로
     * 막혀 사용자가 같은 이메일로 다시 가입도 로그인도 하기 어려워진다(로그인 화면으로 바꿔야
     * 한다는 걸 알기 어려움). signup()이 방금 이 요청에서 만든 계정임이 확실한 경우에만 호출해야
     * 한다 — login()/OAuth처럼 기존 계정을 재사용하는 경로에서는 절대 호출하면 안 된다.
     */
    @Transactional
    public void deleteNewlyCreatedUserAfterSessionSetupFailure(Long userId) {
        userRepository.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public User login(String email, String rawPassword) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        // 무차별대입 방지 - 이메일 존재 여부와 무관하게(계정이 없어도) 항상 같은 방식으로
        // 카운트해야 위 타이밍 안전장치와 같은 이유로 계정 존재 여부가 새어나가지 않는다.
        // EmailVerificationService.confirmCode()의 maxAttempts와 동일한 패턴 - Redis 장애 시에는
        // 가용성을 우선해 로그인 자체를 막지 않는다(무차별대입 방지가 로그인 가용성보다 우선순위가
        // 높지 않다는 판단).
        String attemptsKeyName = loginAttemptsKey(normalizedEmail);
        Long attempts = null;
        try {
            // increment() 후 attempts == 1일 때만 expire()를 별도 호출하면, increment는 성공하고
            // expire만 실패하는 경우(네트워크 순간 장애 등) 그 키가 TTL 없이 영구히 남아 이후
            // 시도 때마다 만료 없이 계속 증가해, 이 이메일이 사실상 영구 잠금될 수 있다.
            // setIfAbsent로 키 생성과 동시에 TTL을 확정해두면, increment가 그 뒤에 실패하더라도
            // 이미 설정된 TTL로 자연 정리되므로 이 경합이 생기지 않는다.
            redisTemplate.opsForValue().setIfAbsent(attemptsKeyName, "0", Duration.ofSeconds(loginLockoutWindowSeconds));
            attempts = redisTemplate.opsForValue().increment(attemptsKeyName);
            if (attempts != null && attempts > loginMaxAttempts) {
                // 브루트포스 의심 신호 - Redis TTL 카운터는 만료되면 사라져 사후 조사가 불가능하므로,
                // 잠금이 실제로 걸리는 시점만큼은 반드시 로그로 남겨 나중에 "이 계정이 언제/몇 번
                // 공격받았는지" 추적할 수 있게 한다. 비밀번호는 절대 로그에 남기지 않는다.
                log.warn("로그인 시도 횟수 초과로 잠금 처리 email={} attempts={}", normalizedEmail, attempts);
                throw new BusinessException(ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS);
            }
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 로그인 시도 횟수 확인 실패 - 가용성을 우선해 로그인은 계속 진행합니다", e);
        }

        User user = userRepository.findByEmail(normalizedEmail)
                .filter(found -> !found.isWithdrawn() && !found.isSuspended())
                .orElse(null);

        // passwordHash가 없는 계정(소셜 전용 가입)은 계정 존재 여부를 드러내지 않도록 자격 증명
        // 오류와 동일한 메시지/코드로 처리한다. 계정이 아예 없거나 passwordHash가 없어도 항상 같은
        // 비용의 BCrypt 비교를 한 번 수행해(DUMMY_PASSWORD_HASH_FOR_TIMING_SAFETY 참고), 그
        // 비교를 건너뛰는 경로가 실제 비밀번호 불일치 경로보다 빨리 응답해 계정 존재 여부가
        // 새어나가는 것을 막는다.
        String passwordHash = user != null ? user.getPasswordHash() : null;
        boolean matches = passwordEncoder.matches(
                rawPassword, passwordHash != null ? passwordHash : DUMMY_PASSWORD_HASH_FOR_TIMING_SAFETY);
        if (user == null || passwordHash == null || !matches) {
            // 최초 1회 실패(오타 등)는 흔한 정상 케이스라 매번 WARN을 남기면 노이즈만 커지지만,
            // 같은 이메일에 연속으로 실패가 쌓이는 것은 브루트포스를 의심할 신호이므로 그때만
            // WARN으로 남긴다. attempts는 위에서 Redis 장애로 못 구한 경우 null일 수 있다.
            // 비밀번호는 절대 로그에 남기지 않는다.
            if (attempts != null && attempts > 1) {
                log.warn("로그인 자격 증명 반복 실패 email={} attempts={}", normalizedEmail, attempts);
            }
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // 로그인 성공 - 이 이메일에 대한 시도 횟수를 리셋한다(실패하면 TTL로 자연 정리되므로 무시해도 무방).
        try {
            redisTemplate.delete(attemptsKeyName);
        } catch (DataAccessException e) {
            log.warn("로그인 성공 후 시도 횟수 초기화 실패(TTL로 자연 정리됨) email={}", normalizedEmail, e);
        }

        return user;
    }

    private static String loginAttemptsKey(String normalizedEmail) {
        return LOGIN_ATTEMPTS_KEY_PREFIX + normalizedEmail;
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
                .filter(found -> !found.isWithdrawn() && !found.isSuspended())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "존재하지 않거나 탈퇴한 사용자입니다."));

        // {@link #login}은 email+passwordHash 조합으로만 계정을 찾으므로, email이 없는 계정(카카오는
        // 현재 profile_nickname 스코프만 요청해 이메일 동의항목 자체가 없음 — application.yml 참고)에
        // 비밀번호를 설정해봐야 그 비밀번호로 로그인할 방법이 아예 없다. 프론트가 "같은 이메일로
        // 로그인할 수도 있어요"라고 안내해놓고 실제로는 로그인이 불가능한 상황을 막기 위해 여기서
        // 막는다 — 카카오 email 스코프 승인 후 이메일이 채워지면 그때부터 정상적으로 설정 가능해진다.
        if (user.getEmail() == null) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_REQUIRED_FOR_PASSWORD);
        }

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
            throw new BusinessException(ErrorCode.AUTH_CURRENT_PASSWORD_MISMATCH);
        }

        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        // passwordChangedAt이 캐시에 최대 30초 stale하게 남아있으면 이번에 바꾼 비밀번호 이전에
        // 발급된 access token(탈취됐거나 다른 기기에 열려 있던)이 그동안 계속 통과한다 - 커밋
        // 직후 지워 다음 요청부터 바로 DB의 새 passwordChangedAt을 보게 한다.
        userAuthStatusCacheService.evictAfterCommit(userId);
    }
}
