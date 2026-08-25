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

@DisplayName("DevTestUserSeeder")
class DevTestUserSeederTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final DevTestUserSeeder seeder = new DevTestUserSeeder(userRepository);

    private void enableWith(String email) {
        ReflectionTestUtils.setField(seeder, "devLoginEnabled", true);
        ReflectionTestUtils.setField(seeder, "devLoginUserEmail", email);
    }

    @Test
    @DisplayName("dev-login이 꺼져 있으면 테스트 계정을 만들지 않는다")
    void doesNotSeedWhenDevLoginDisabled() throws Exception {
        ReflectionTestUtils.setField(seeder, "devLoginEnabled", false);

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("dev-login이 켜져 있고 계정이 없으면 USER 역할로, 비밀번호 없이 생성한다")
    void seedsRegularUserWithoutPasswordWhenEnabledAndMissing() throws Exception {
        enableWith("tester@algogyeyak.local");
        when(userRepository.findByEmail("tester@algogyeyak.local")).thenReturn(Optional.empty());

        seeder.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("tester@algogyeyak.local", saved.getEmail());
        assertNull(saved.getPasswordHash());
        assertEquals(Role.USER, saved.getRole());
    }

    @Test
    @DisplayName("이미 계정이 있으면(역할/상태 무관) 절대 건드리지 않는다")
    void neverTouchesExistingAccountRegardlessOfRole() throws Exception {
        enableWith("tester@algogyeyak.local");
        User existing = User.createLocalUser("tester@algogyeyak.local", "real-password-hash", "실제유저");
        when(userRepository.findByEmail("tester@algogyeyak.local")).thenReturn(Optional.of(existing));

        seeder.run(new DefaultApplicationArguments());

        assertEquals("real-password-hash", existing.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("닉네임 '테스트유저'가 이미 다른 사용자의 것이어도 기동을 실패시키지 않는다")
    void doesNotCrashStartupWhenNicknameAlreadyTaken() throws Exception {
        enableWith("tester@algogyeyak.local");
        when(userRepository.findByEmail("tester@algogyeyak.local")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("nickname unique constraint"));

        seeder.run(new DefaultApplicationArguments());

        verify(userRepository).save(any(User.class));
    }
}
