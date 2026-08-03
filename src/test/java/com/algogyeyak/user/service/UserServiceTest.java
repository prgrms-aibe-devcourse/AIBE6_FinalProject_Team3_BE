package com.algogyeyak.user.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.TransactionType;
import com.algogyeyak.user.repository.UserPreferenceRepository;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPreferenceRepository userPreferenceRepository = mock(UserPreferenceRepository.class);
    private final S3PresignService s3PresignService = mock(S3PresignService.class);
    private final UserService userService = new UserService(userRepository, userPreferenceRepository, s3PresignService);

    private User activeUser(Long id) {
        User user = User.createLocalUser("test@example.com", "encoded-hash", "테스트유저");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private ProfileRegisterRequest registerRequest(String nickname) {
        ProfileRegisterRequest request = new ProfileRegisterRequest();
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ReflectionTestUtils.setField(request, "interestRegion", "서울시 강남구");
        ReflectionTestUtils.setField(request, "transactionType", TransactionType.JEONSE);
        return request;
    }

    @Test
    void withdrawThrowsWhenUserIsSuspended() {
        // getActiveUserOrThrow()가 탈퇴 여부만 확인하고 정지 여부는 놓쳐서, 정지된 유저가 기존
        // access token으로 이 API들을 계속 쓸 수 있는 구멍이었다 - JwtAuthenticationFilter가 이제
        // 전역으로 막아주지만, 이 서비스 레이어 자체도 정지 상태를 정확히 인지해야 한다.
        User suspended = activeUser(1L);
        suspended.suspend();
        when(userRepository.findById(1L)).thenReturn(Optional.of(suspended));

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.withdraw(1L));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void registerProfileThrowsWhenProfileAlreadyExists() {
        // 온보딩(최초 등록)은 한 번만 허용되어야 한다 - 이미 UserPreference 행이 있으면 재등록을
        // 막고, 재확인 없이 500이나 다른 코드로 새는 대신 409로 명확히 알려야 한다.
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userPreferenceRepository.existsByUserId(1L)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.registerProfile(1L, registerRequest(null)));

        assertEquals(ErrorCode.USER_PROFILE_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void registerProfileThrowsWhenNicknameAlreadyExists() {
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userPreferenceRepository.existsByUserId(1L)).thenReturn(false);
        when(userRepository.existsByNicknameAndIdNot("중복닉네임", 1L)).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.registerProfile(1L, registerRequest("중복닉네임")));

        assertEquals(ErrorCode.USER_NICKNAME_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void updateMyProfileThrowsWhenNicknameAlreadyExists() {
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("중복닉네임", 1L)).thenReturn(true);

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        ReflectionTestUtils.setField(request, "nickname", "중복닉네임");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.updateMyProfile(1L, request));

        assertEquals(ErrorCode.USER_NICKNAME_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }
}
