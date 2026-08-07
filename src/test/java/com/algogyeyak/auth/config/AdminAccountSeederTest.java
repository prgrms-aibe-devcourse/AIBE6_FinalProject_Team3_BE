package com.algogyeyak.auth.config;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminAccountSeeder")
class AdminAccountSeederTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminAccountSeeder seeder = new AdminAccountSeeder(userRepository);

    private void enableWith(String email) {
        ReflectionTestUtils.setField(seeder, "devLoginEnabled", true);
        ReflectionTestUtils.setField(seeder, "devLoginEmail", email);
    }

    @Test
    @DisplayName("dev-login이 꺼져 있으면 admin 계정을 만들지 않는다")
    void doesNotSeedWhenDevLoginDisabled() throws Exception {
        ReflectionTestUtils.setField(seeder, "devLoginEnabled", false);

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("dev-login이 켜져 있고 admin 계정이 없으면 ADMIN 역할로, 비밀번호 없이 생성한다")
    void seedsAdminUserWithoutPasswordWhenEnabledAndMissing() throws Exception {
        enableWith("admin@algogyeyak.local");
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.empty());

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin@algogyeyak.local", saved.getEmail());
        // dev-login은 비밀번호를 검사하지 않고, passwordHash가 없어야 일반 /auth/login으로는
        // 이 계정에 로그인할 수 없다 — dev-login 스위치가 꺼지면 접근 경로 자체가 사라져야 하므로.
        assertNull(saved.getPasswordHash());
        assertEquals(Role.ADMIN, saved.getRole());
    }

    @Test
    @DisplayName("설정된 이메일은 정규화(trim+lowercase)한 뒤 조회/생성한다")
    void normalizesConfiguredEmailBeforeUse() throws Exception {
        enableWith("  Admin@Algogyeyak.Local  ");
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.empty());

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("admin@algogyeyak.local", captor.getValue().getEmail());
    }

    @Test
    @DisplayName("이미 안전한 상태(비밀번호 없음, ADMIN)의 계정이 있으면 손대지 않는다")
    void doesNotTouchExistingAccountThatIsAlreadyHealthy() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
        existing.grantAdminRole();
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    // 회귀 테스트 - 예전 버전은 이 이메일에 이미 있는 로컬 계정을 "치유"한답시고 비밀번호를 지우고
    // ADMIN으로 승격시켰다. DEV_LOGIN_ENABLED가 운영에서도 켜질 수 있게 되면서(DEV_LOGIN_SECRET로
    // 보호) 그 healing 로직은 secret을 전혀 거치지 않는 권한 상승 경로가 됐다 - 실제 사용자가
    // 우연히/의도적으로 이 이메일로 가입했다면 매 기동마다 조용히 ADMIN으로 승격되고, 이미 로그인
    // 중이었다면(JwtAuthenticationFilter가 매 요청 DB에서 role을 다시 읽으므로) 그 세션이 즉시
    // ADMIN 권한을 갖는다. 이제는 이미 존재하는 계정을 어떤 상태든 절대 건드리지 않아야 한다.
    @Test
    @DisplayName("같은 이메일에 비밀번호가 있는 일반(USER) 로컬 계정이 있어도 절대 승격/치유하지 않는다")
    void neverPromotesOrHealsExistingAccountRegardlessOfPasswordOrRole() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createLocalUser("admin@algogyeyak.local", "real-user-password-hash", "실제유저");
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        assertEquals(Role.USER, existing.getRole());
        assertEquals("real-user-password-hash", existing.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
    }

    // 회귀 테스트 - 닉네임 "관리자"를 이미 다른 실제 사용자가 쓰고 있으면 User.nickname의 전역
    // 유니크 제약에 걸려 save()가 DataIntegrityViolationException을 던진다. ApplicationRunner에서
    // 예외가 그대로 새어나가면 앱 기동 자체가 실패하므로, 이메일 충돌과 동일하게 경고만 남기고
    // 기동은 계속돼야 한다.
    @Test
    @DisplayName("닉네임 '관리자'가 이미 다른 사용자의 것이어도 기동을 실패시키지 않는다")
    void doesNotCrashStartupWhenNicknameAlreadyTaken() throws Exception {
        enableWith("admin@algogyeyak.local");
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("nickname unique constraint"));

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("같은 이메일의 계정이 소셜 로그인으로 가입돼 있어도 절대 건드리지 않는다(기동은 실패시키지 않는다)")
    void skipsWithoutThrowingWhenMatchingAccountIsOAuthUser() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createOAuthUser("admin@algogyeyak.local", "관리자", null);
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        assertEquals(Role.USER, existing.getRole());
        verify(userRepository, never()).save(any(User.class));
    }
}
