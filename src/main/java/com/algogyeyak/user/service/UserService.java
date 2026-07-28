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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;

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
            // 409 Conflict에 대응하는 ErrorCode가 없어 우선 BAD_REQUEST로 처리 — 확인 필요
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 프로필이 등록되어 있습니다.");
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

    @Transactional
    public UserProfileResponse updateMyProfile(Long userId, ProfileUpdateRequest request) {
        User user = getActiveUserOrThrow(userId);

        if (StringUtils.hasText(request.getNickname())
                && !request.getNickname().equals(user.getNickname())) {
            validateNicknameNotDuplicated(userId, request.getNickname());
            user.updateNickname(request.getNickname());
        }

        if (request.getProfileImageUrl() != null) {
            user.updateProfileImageUrl(request.getProfileImageUrl());
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
        // TODO: user_socials(OAuth 연동 정보) 처리 정책 적용 필요 — 확인 필요
        // TODO: 탈퇴한 사용자의 Property/ContractAnalysis 등 연관 데이터 처리 방식 적용 필요 — 확인 필요
    }

    private User getActiveUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않거나 탈퇴한 사용자입니다."));
    }

    private void validateNicknameNotDuplicated(Long userId, String nickname) {
        if (userRepository.existsByNicknameAndIdNot(nickname, userId)) {
            // 마찬가지로 409에 대응하는 코드가 없어 BAD_REQUEST로 처리 — 확인 필요
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 사용 중인 닉네임입니다.");
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
