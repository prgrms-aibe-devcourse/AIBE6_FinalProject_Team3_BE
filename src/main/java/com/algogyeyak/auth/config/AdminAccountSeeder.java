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
 * 개발 편의용 "관리자로 로그인" 버튼(POST /auth/dev-login)이 로그인시킬 admin 계정을 앱 시작
 * 시점에 시드한다. {@link com.algogyeyak.checklist.config.ChecklistTemplateSeeder}와 동일하게
 * 마이그레이션 도구 도입 전까지 쓰는 임시 방식이다. dev-login 자체가 꺼져 있으면(운영 등) 이 계정도
 * 만들 필요가 없으므로 같은 스위치를 공유한다.
 *
 * passwordHash는 일부러 null로 둔다 — dev-login 엔드포인트는 비밀번호를 검사하지 않고 email로만
 * 조회하므로 비밀번호가 필요 없고, null이면 {@code LocalAuthService.login}이 소셜 전용 계정과
 * 동일하게 취급해 일반 /auth/login으로는 이 계정에 로그인할 수 없다 — dev-login 스위치가 꺼지면
 * 이 계정으로 들어올 방법 자체가 완전히 사라지도록 하기 위함이다.
 */
@Component
public class AdminAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountSeeder.class);

    private final UserRepository userRepository;

    @Value("${app.dev-login.enabled}")
    private boolean devLoginEnabled;

    @Value("${app.dev-login.email}")
    private String devLoginEmail;

    public AdminAccountSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!devLoginEnabled) {
            return;
        }

        String normalizedEmail = EmailNormalizer.normalize(devLoginEmail);

        // 예전에는 이 이메일에 이미 계정이 있으면 "치유"한다며 비밀번호를 지우고 ADMIN으로
        // 승격시켰다 - DEV_LOGIN_ENABLED가 로컬/dev에서만 켜질 수 있던 시절엔 안전했지만, 운영에서도
        // 이 스위치를 켤 수 있게 되면서(DEV_LOGIN_SECRET로 보호) 이 healing 로직은 secret을 전혀
        // 거치지 않는 권한 상승 경로가 됐다: DEV_LOGIN_EMAIL과 같은 이메일로 실제 가입된 로컬
        // 계정이 있으면 매 기동마다 그 계정을 조용히 ADMIN으로 승격시켰고, JwtAuthenticationFilter가
        // 매 요청 DB에서 role을 다시 읽으므로 그 사용자가 이미 로그인 중이었다면 secret 없이도
        // 그 세션이 즉시 ADMIN 권한을 갖게 됐다. 이 계정이 시더 자신이 예전에 만든 것인지, 우연히
        // 이메일이 겹친 진짜 사용자 계정인지 안전하게 구분할 방법이 없으므로, 이미 존재하면 어떤
        // 이유로든 무조건 건드리지 않고 건너뛴다 - 시딩은 계정이 아직 없을 때만 일어난다.
        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            log.warn(
                    "app.dev-login.email({})에 이미 계정이 존재해 시딩을 건너뜁니다 - 기존 계정은 그대로 둡니다"
                            + "(운영에서 실제 사용자 계정이 이 이메일과 겹쳐 있어도 조용히 ADMIN으로"
                            + " 승격되지 않도록 하기 위함). dev-login으로 로그인하려면 이 이메일에 계정이"
                            + " 없는 상태에서 기동해야 합니다.",
                    normalizedEmail);
            return;
        }

        User admin = User.createLocalUser(normalizedEmail, null, "관리자");
        admin.grantAdminRole();

        try {
            userRepository.save(admin);
        } catch (DataIntegrityViolationException e) {
            // 닉네임 "관리자"가 이미(다른 실제 사용자가 가입 시점에 우연히 골랐거나) 존재하는 값이면
            // User.nickname의 전역 유니크 제약에 걸린다. 이메일 충돌과 달리 이 예외를 그냥 흘려보내면
            // ApplicationRunner의 예외는 앱 기동 자체를 실패시킨다 - dev-login이 시딩 실패 하나로
            // 전체 배포를 막아서는 안 되므로, 이메일 충돌과 동일하게 경고만 남기고 계속 기동한다.
            log.warn(
                    "app.dev-login.email({})용 관리자 계정 시딩 실패 - 닉네임 '관리자'를 이미 다른 사용자가"
                            + " 사용 중입니다. 그 사용자의 닉네임을 바꾸거나 이 계정을 수동으로 만든 뒤 다시"
                            + " 기동해야 dev-login을 쓸 수 있습니다.",
                    normalizedEmail, e);
        }
    }
}
