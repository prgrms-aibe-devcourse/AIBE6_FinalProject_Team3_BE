package com.algogyeyak.auth.config;

import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
        Optional<User> existing = userRepository.findByEmail(normalizedEmail);

        if (existing.isPresent()) {
            User user = existing.get();

            // DEV_LOGIN_EMAIL이 실수로 실제 소셜 로그인 사용자의 이메일과 겹치면(공유 DB에 오타 등으로
            // 잘못 설정된 경우) 아래 healing 로직이 그 실제 계정의 비밀번호를 지우고 ADMIN으로
            // 승격시켜버릴 수 있다 — 이 시더가 만드는 계정은 항상 LOCAL이므로, LOCAL이 아닌 계정이
            // 걸리면 절대 건드리지 않고 기동을 실패시켜 설정 오류를 바로 드러낸다.
            if (user.getProvider() != AuthProvider.LOCAL) {
                throw new IllegalStateException(
                        "app.dev-login.email(" + normalizedEmail + ")이 LOCAL이 아닌 기존 계정(provider="
                                + user.getProvider() + ")과 일치합니다 — 실수로 실제 사용자 이메일과 겹쳤을 수 있어"
                                + " 자동으로 ADMIN 승격/비밀번호 초기화를 하지 않고 기동을 중단합니다."
                                + " DEV_LOGIN_EMAIL 설정을 확인하세요.");
            }

            // 예전 버전의 시더가 비밀번호를 넣어 만들어뒀거나, ADMIN이 아닌 상태로 남아있을 수 있다 —
            // 매 기동마다 다시 안전한 상태(비밀번호 없음, ADMIN)로 되돌려 놓는다. 그냥 건너뛰기만
            // 하면 과거 버그의 흔적이 영구 DB에서는 계속 남아있게 된다.
            boolean changed = false;
            if (user.getPasswordHash() != null) {
                user.updatePasswordHash(null);
                changed = true;
            }
            if (user.getRole() != Role.ADMIN) {
                user.grantAdminRole();
                changed = true;
            }
            if (changed) {
                userRepository.save(user);
            }
            return;
        }

        User admin = User.createLocalUser(normalizedEmail, null, "관리자");
        admin.grantAdminRole();
        userRepository.save(admin);
    }
}
