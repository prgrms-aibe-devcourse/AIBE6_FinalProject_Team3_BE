package com.algogyeyak.user.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void resetProfileImageClearsUrlAndDeletesOwnedS3Object() {
        User user = activeUser(1L);
        user.updateProfileImageUrl("https://bucket.s3.ap-northeast-2.amazonaws.com/profile-images/1/old.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(s3PresignService.extractOwnedKey(user.getProfileImageUrl()))
                .thenReturn(Optional.of("profile-images/1/old.jpg"));

        UserProfileResponse response = userService.resetProfileImage(1L);

        assertNull(response.getProfileImageUrl());
        verify(s3PresignService).deleteObject("profile-images/1/old.jpg");
    }

    @Test
    void resetProfileImageIsNoOpWhenAlreadyDefault() {
        // 이미 기본 이미지(profileImageUrl == null) 상태에서 호출해도 에러 없이 그대로 성공해야
        // 한다(멱등) - 클라이언트가 상태를 미리 알 필요 없이 안전하게 호출할 수 있게 하기 위함.
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.resetProfileImage(1L);

        assertNull(response.getProfileImageUrl());
        verify(s3PresignService, never()).deleteObject(any());
    }
}
