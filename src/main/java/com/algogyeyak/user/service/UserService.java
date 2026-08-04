package com.algogyeyak.user.service;

import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserPreference;
import com.algogyeyak.user.repository.UserPreferenceRepository;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.dto.PresignedUploadRequest;
import com.algogyeyak.global.s3.dto.PresignedUploadResponse;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.global.s3.util.S3KeyGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final S3PresignService s3PresignService;

    public UserProfileResponse getMyProfile(Long userId) {
        User user = getActiveUserOrThrow(userId);
        UserPreference preference = userPreferenceRepository.findByUserId(userId).orElse(null);
        return toResponse(user, preference);
    }

    public NicknameCheckResponse checkNicknameAvailable(Long userId, String nickname) {
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "닉네임을 입력해 주세요.");
        }

        boolean available = !userRepository.existsByNicknameAndIdNot(nickname, userId);
        return NicknameCheckResponse.builder().available(available).build();
    }

    @Transactional
    public UserProfileResponse registerProfile(Long userId, ProfileRegisterRequest request) {
        User user = getActiveUserOrThrow(userId);

        if (userPreferenceRepository.existsByUserId(userId)) {
            throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_EXISTS);
        }

        if (StringUtils.hasText(request.getNickname())
                && !request.getNickname().equals(user.getNickname())) {
            validateNicknameNotDuplicated(userId, request.getNickname());
            user.updateNickname(request.getNickname());
        }

        UserPreference preference = UserPreference.builder()
                .user(user)
                .interestRegion(request.getInterestRegion())
                .transactionType(request.getTransactionType())
                .currentStage(request.getCurrentStage())
                .build();

        userPreferenceRepository.save(preference);

        return toResponse(user, preference);
    }

    // 업로드용 presigned URL 발급 - S3에 실제로 아무 흔적도 남기지 않으므로 조회 트랜잭션(readOnly)이면 충분하다.
    public PresignedUploadResponse presignProfileImageUpload(Long userId, PresignedUploadRequest request) {
        getActiveUserOrThrow(userId);

        String key = S3KeyGenerator.profileImageKey(userId, request.getFileExtension());
        String uploadUrl = s3PresignService.generateUploadUrl(
                key, request.getContentType(), request.getFileSize(), S3ImagePurpose.PROFILE);

        return new PresignedUploadResponse(uploadUrl, key, S3PresignService.PENDING_UPLOAD_TAG);
    }

    @Transactional
    public UserProfileResponse confirmProfileImageUpload(Long userId, String key) {
        User user = getActiveUserOrThrow(userId);

        if (!S3KeyGenerator.isProfileImageOwnedBy(userId, key)) {
            throw new BusinessException(ErrorCode.FILE_KEY_ACCESS_DENIED);
        }

        s3PresignService.confirmUpload(key, S3ImagePurpose.PROFILE);

        String previousImageUrl = user.getProfileImageUrl();
        user.updateProfileImageUrl(s3PresignService.generateDownloadUrl(key, S3ImagePurpose.PROFILE));
        deletePreviousProfileImageIfOwned(userId, previousImageUrl);

        UserPreference preference = userPreferenceRepository.findByUserId(userId).orElse(null);
        return toResponse(user, preference);
    }

    // 기본(미설정) 프로필 이미지로 되돌린다 - 이미 기본 이미지 상태(profileImageUrl == null)여도
    // 에러 없이 그대로 성공 처리한다(멱등).
    @Transactional
    public UserProfileResponse resetProfileImage(Long userId) {
        User user = getActiveUserOrThrow(userId);

        String previousImageUrl = user.getProfileImageUrl();
        user.updateProfileImageUrl(null);
        deletePreviousProfileImageIfOwned(userId, previousImageUrl);

        UserPreference preference = userPreferenceRepository.findByUserId(userId).orElse(null);
        return toResponse(user, preference);
    }

    // 새 이미지로 교체된 이전 프로필 이미지를 정리한다. 이미 새 이미지 저장은 끝난 뒤라, 여기서
    // 실패해도(권한/네트워크 등) 요청 자체를 실패시키지 않고 로그만 남긴다.
    private void deletePreviousProfileImageIfOwned(Long userId, String previousImageUrl) {
        s3PresignService.extractOwnedKey(previousImageUrl)
                .filter(oldKey -> S3KeyGenerator.isProfileImageOwnedBy(userId, oldKey))
                .ifPresent(oldKey -> {
                    try {
                        s3PresignService.deleteObject(oldKey);
                    } catch (RuntimeException e) {
                        log.warn("이전 프로필 이미지 삭제 실패 - userId={}, key={}", userId, oldKey, e);
                    }
                });
    }

    @Transactional
    public UserProfileResponse updateMyProfile(Long userId, ProfileUpdateRequest request) {
        User user = getActiveUserOrThrow(userId);

        if (StringUtils.hasText(request.getNickname())
                && !request.getNickname().equals(user.getNickname())) {
            validateNicknameNotDuplicated(userId, request.getNickname());
            user.updateNickname(request.getNickname());
        }

        UserPreference preference = userPreferenceRepository.findByUserId(userId)
                .orElseGet(() -> userPreferenceRepository.save(
                        UserPreference.builder().user(user).build()));

        if (request.getInterestRegion() != null) {
            preference.updateInterestRegion(request.getInterestRegion());
        }
        if (request.getTransactionType() != null) {
            preference.updateTransactionType(request.getTransactionType());
        }
        if (request.getCurrentStage() != null) {
            preference.updateCurrentStage(request.getCurrentStage());
        }

        return toResponse(user, preference);
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = getActiveUserOrThrow(userId);
        user.withdraw();
        // TODO: user_social_accounts(OAuth 연동 정보, UserSocialAccount 엔티티) 처리 정책 적용 필요 — 확인 필요
        // TODO: 탈퇴한 사용자의 Property/ContractAnalysis 등 연관 데이터 처리 방식 적용 필요 — 확인 필요
    }

    private User getActiveUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isWithdrawn() && !user.isSuspended())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않거나 탈퇴한 사용자입니다."));
    }

    private void validateNicknameNotDuplicated(Long userId, String nickname) {
        if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
            throw new BusinessException(ErrorCode.USER_NICKNAME_ALREADY_EXISTS);
        }
    }

    private UserProfileResponse toResponse(User user, UserPreference preference) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .profileImageUrl(user.getProfileImageUrl())
                .status(user.getStatus().name())
                .interestRegion(preference != null ? preference.getInterestRegion() : null)
                .transactionType(preference != null && preference.getTransactionType() != null
                        ? preference.getTransactionType().name() : null)
                .currentStage(preference != null ? preference.getCurrentStage() : null)
                .hasPassword(user.getPasswordHash() != null)
                .build();
    }
}
