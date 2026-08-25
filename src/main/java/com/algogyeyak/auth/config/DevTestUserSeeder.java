package com.algogyeyak.auth.config;

import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * {@link AdminAccountSeeder}와 동일한 이유·동일한 안전장치(이미 존재하면 절대 건드리지 않음,
 * 닉네임/동시기동 충돌 시 경고만 남기고 기동 계속)로, dev-login이 로그인시킬 수 있는 일반(USER)
 * 역할 테스트 계정을 시딩한다. 관리자 계정만 있고 일반 회원 테스트 계정이 없어 QA/멘토링 등
 * 외부 테스트 시 일반 사용자 화면을 확인하려면 매번 실제로 회원가입을 해야 했다(2026-08-20
 * 멘토링 피드백).
 *
 * 별도 클래스로 둔 이유: AdminAccountSeeder에 로직을 합쳐 계정 2개를 시딩하게 만들 수도 있었지만,
 * "관리자 계정"과 "일반 테스트 계정"은 서로 무관하게 독립적으로 존재/부재할 수 있어야 하고
 * (하나가 실패해도 다른 하나의 시딩에 영향을 주면 안 됨), 클래스 이름이 계속 "Admin"이면서
 * 일반 계정까지 만드는 건 오해를 부른다.
 */
@Component
public class DevTestUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevTestUserSeeder.class);

    private final UserRepository userRepository;

    @Value("${app.dev-login.enabled}")
    private boolean devLoginEnabled;

    @Value("${app.dev-login.user-email}")
    private String devLoginUserEmail;

    public DevTestUserSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!devLoginEnabled) {
            return;
        }

        String normalizedEmail = EmailNormalizer.normalize(devLoginUserEmail);

        // AdminAccountSeeder와 동일한 이유 - 이미 존재하면 시더가 예전에 만든 것인지 우연히 이메일이
        // 겹친 실제 사용자 계정인지 구분할 방법이 없으므로, 어떤 상태든 절대 건드리지 않고 건너뛴다.
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            log.warn(
                    "app.dev-login.user-email({})에 이미 계정이 존재해 시딩을 건너뜁니다 - 기존 계정은"
                            + " 그대로 둡니다. dev-login(일반회원)으로 로그인하려면 이 이메일에 계정이"
                            + " 없는 상태에서 기동해야 합니다.",
                    normalizedEmail);
            return;
        }

        User testUser = User.createLocalUser(normalizedEmail, null, "테스트유저");

        try {
            userRepository.save(testUser);
        } catch (DataIntegrityViolationException e) {
            log.warn(
                    "app.dev-login.user-email({})용 테스트 계정 시딩 실패 - 닉네임 '테스트유저'가 이미"
                            + " 사용 중이거나, 동시 기동 중인 다른 인스턴스가 같은 이메일로 먼저 계정을"
                            + " 만들었을 수 있습니다(이 경우 재기동 없이도 다음 요청부터 정상 동작)."
                            + " 원인은 cause 메시지를 확인하세요.",
                    normalizedEmail, e);
        }
    }
}
