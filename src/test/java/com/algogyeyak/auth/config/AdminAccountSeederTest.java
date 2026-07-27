package com.algogyeyak.auth.config;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
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
    @DisplayName("이미 안전한 상태(비밀번호 없음, ADMIN)의 계정이 있으면 다시 저장하지 않는다")
    void doesNotResaveWhenExistingAdminIsAlreadyHealthy() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createLocalUser("admin@algogyeyak.local", null, "관리자");
        existing.grantAdminRole();
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("예전 버그로 비밀번호가 남아있는 admin 계정을 발견하면 비밀번호를 제거하고 다시 저장한다")
    void healsExistingAdminAccountThatStillHasAPassword() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createLocalUser("admin@algogyeyak.local", "leftover-hash", "관리자");
        existing.grantAdminRole();
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        assertNull(existing.getPasswordHash());
        verify(userRepository).save(existing);
    }

    @Test
    @DisplayName("같은 이메일의 계정이 있지만 ADMIN이 아니면 ADMIN으로 승격시켜 저장한다")
    void promotesExistingAccountToAdminWhenRoleMismatches() throws Exception {
        enableWith("admin@algogyeyak.local");
        User existing = User.createOAuthUser("admin@algogyeyak.local", "관리자", null, AuthProvider.KAKAO, "999");
        when(userRepository.findByEmail("admin@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        assertEquals(Role.ADMIN, existing.getRole());
        verify(userRepository).save(existing);
    }
}
