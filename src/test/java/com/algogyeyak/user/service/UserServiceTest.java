package com.algogyeyak.user.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.TransactionType;
import com.algogyeyak.user.repository.UserPreferenceRepository;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserPreferenceRepository userPreferenceRepository = mock(UserPreferenceRepository.class);
    private final UserSocialAccountRepository userSocialAccountRepository = mock(UserSocialAccountRepository.class);
    private final ChecklistRepository checklistRepository = mock(ChecklistRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final S3PresignService s3PresignService = mock(S3PresignService.class);
    private final UserService userService = new UserService(
            userRepository, userPreferenceRepository, userSocialAccountRepository, checklistRepository,
            propertyRepository, s3PresignService, mock(PlatformTransactionManager.class));

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

        assertEquals(ErrorCode.USER_SUSPENDED, exception.getErrorCode());
    }

    @Test
    void withdrawThrowsWithDistinctErrorCodeWhenUserAlreadyWithdrawn() {
        // "존재하지 않음"과 "이미 탈퇴함"이 예전엔 같은 ErrorCode.NOT_FOUND로 뭉뚱그려져 있었다 -
        // 프론트가 두 경우를 구분해 안내할 수 있도록 별도 코드로 분리한 회귀 테스트.
        User withdrawn = activeUser(1L);
        withdrawn.withdraw();
        when(userRepository.findById(1L)).thenReturn(Optional.of(withdrawn));

        BusinessException exception = assertThrows(BusinessException.class, () -> userService.withdraw(1L));

        assertEquals(ErrorCode.USER_WITHDRAWN, exception.getErrorCode());
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
    void updateMyProfileChangesNicknameWhenAvailable() {
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("새닉네임", 1L)).thenReturn(false);

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        ReflectionTestUtils.setField(request, "nickname", "새닉네임");

        UserProfileResponse response = userService.updateMyProfile(1L, request);

        assertEquals("새닉네임", response.getNickname());
    }

    @Test
    void updateMyProfileRecoversWithNicknameConflictWhenConcurrentChangeWinsTheRace() {
        // 사전 검사(existsByNicknameAndIdNot)를 통과한 이후에도, 커밋 전에 동시에 같은 닉네임으로
        // 바꾼 다른 요청이 먼저 커밋되면 유니크 제약에 걸릴 수 있다 - 이때 500 대신 정확한
        // USER_NICKNAME_ALREADY_EXISTS 409로 복구되어야 한다(UserService.changeNickname 참고).
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNicknameAndIdNot("경쟁닉네임", 1L)).thenReturn(false, true);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        ProfileUpdateRequest request = new ProfileUpdateRequest();
        ReflectionTestUtils.setField(request, "nickname", "경쟁닉네임");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userService.updateMyProfile(1L, request));

        assertEquals(ErrorCode.USER_NICKNAME_ALREADY_EXISTS, exception.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void withdrawDeletesOwnedProfileImage() {
        // User.withdraw()가 profileImageUrl을 직접 null로 비우기 때문에, resetProfileImage()와
        // 달리 이 정리를 명시적으로 해주지 않으면 탈퇴할 때마다 소유자 없는 이미지가 S3에 영구적으로
        // 남는 회귀 테스트.
        User user = activeUser(1L);
        user.updateProfileImageUrl("https://bucket.s3.ap-northeast-2.amazonaws.com/profile-images/1/old.jpg");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(s3PresignService.extractOwnedKey("https://bucket.s3.ap-northeast-2.amazonaws.com/profile-images/1/old.jpg"))
                .thenReturn(Optional.of("profile-images/1/old.jpg"));

        userService.withdraw(1L);

        verify(s3PresignService).deleteReplacedObject("profile-images/1/old.jpg");
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
        verify(s3PresignService).deleteReplacedObject("profile-images/1/old.jpg");
    }

    @Test
    void resetProfileImageIsNoOpWhenAlreadyDefault() {
        // 이미 기본 이미지(profileImageUrl == null) 상태에서 호출해도 에러 없이 그대로 성공해야
        // 한다(멱등) - 클라이언트가 상태를 미리 알 필요 없이 안전하게 호출할 수 있게 하기 위함.
        User user = activeUser(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.resetProfileImage(1L);

        assertNull(response.getProfileImageUrl());
        verify(s3PresignService, never()).deleteReplacedObject(any());
    }

    @Test
    void checkNicknameAvailableExcludesSelfWhenAuthenticated() {
        when(userRepository.existsByNicknameAndIdNot("닉네임", 1L)).thenReturn(false);

        NicknameCheckResponse response = userService.checkNicknameAvailable(1L, "닉네임");

        assertTrue(response.isAvailable());
        verify(userRepository, never()).existsByNickname(any());
    }

    @Test
    void checkNicknameAvailableChecksGloballyWhenAnonymous() {
        // 회원가입 화면처럼 로그인 전(userId == null)에 호출되는 경우 - existsByNicknameAndIdNot에
        // null을 그대로 넘기면 SQL의 "id <> NULL"이 항상 거짓이 되어 무조건 available=true로 잘못
        // 판정하므로, 이 경로는 본인 제외 없이 전체 중복만 확인하는 existsByNickname을 타야 한다.
        when(userRepository.existsByNickname("중복닉네임")).thenReturn(true);

        NicknameCheckResponse response = userService.checkNicknameAvailable(null, "중복닉네임");

        assertFalse(response.isAvailable());
        verify(userRepository, never()).existsByNicknameAndIdNot(any(), any());
    }
}
