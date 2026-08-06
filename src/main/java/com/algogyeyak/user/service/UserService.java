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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final S3PresignService s3PresignService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UserService(
            UserRepository userRepository,
            UserPreferenceRepository userPreferenceRepository,
            S3PresignService s3PresignService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.s3PresignService = s3PresignService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
            changeNickname(user, userId, request.getNickname());
        }

        UserPreference preference = UserPreference.builder()
                .user(user)
                .interestRegion(request.getInterestRegion())
                .transactionType(request.getTransactionType())
                .currentStage(request.getCurrentStage())
                .build();

        savePreferenceOrThrowIfAlreadyRegistered(userId, preference);

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
                        s3PresignService.deleteReplacedObject(oldKey);
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
            changeNickname(user, userId, request.getNickname());
        }

        UserPreference preference = userPreferenceRepository.findByUserId(userId)
                .orElseGet(() -> saveOrFetchExistingPreference(userId, user));

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

    // 사전 검사(validateNicknameNotDuplicated) 통과 이후에도, 커밋 전에 동시에 같은 닉네임으로
    // 바꾼 다른 요청이 먼저 커밋되면 유니크 제약에 걸릴 수 있다. 이 UPDATE를 바깥 트랜잭션과 같은
    // 세션에서 그냥 저장하면 유니크 제약 위반 시 바깥 트랜잭션 세션 전체가 더 이상 쓸 수 없는
    // 상태가 되어 버리므로, CustomOAuth2UserService.createUser / LocalAuthService.createUser와
    // 동일한 이유로 REQUIRES_NEW(별도 세션)로 분리해 실패해도 폐기되는 세션이 이 임시
    // 트랜잭션뿐이도록 격리하고, 실패 시 진짜 원인(닉네임 중복)을 재확인해 정확한 에러로 변환한다.
    private void changeNickname(User user, Long userId, String newNickname) {
        validateNicknameNotDuplicated(userId, newNickname);
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                User managed = userRepository.findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않거나 탈퇴한 사용자입니다."));
                managed.updateNickname(newNickname);
                userRepository.saveAndFlush(managed);
            });
        } catch (DataIntegrityViolationException e) {
            // 이 재확인도 바깥 트랜잭션에서 그냥 실행하면 안 된다 - MySQL 기본 REPEATABLE READ에서는
            // 바깥 트랜잭션이 경쟁 요청의 커밋 전 스냅샷을 이미 고정하고 있을 수 있어, 실제로는
            // 닉네임이 선점됐는데도 이 재확인이 stale한 "아직 안 겹침" 결과를 돌려줄 수 있다.
            // 그러면 의도한 409 대신 원래 예외가 그대로 다시 던져져 500으로 샌다 - 재확인도
            // REQUIRES_NEW(별도 세션)로 분리해 항상 그 시점의 최신 커밋 상태를 보게 한다.
            boolean nicknameTaken = Boolean.TRUE.equals(
                    requiresNewTransactionTemplate.execute(status -> userRepository.existsByNicknameAndIdNot(newNickname, userId)));
            if (nicknameTaken) {
                throw new BusinessException(ErrorCode.USER_NICKNAME_ALREADY_EXISTS);
            }
            throw e;
        }
        user.updateNickname(newNickname);
    }

    // registerProfile()의 사전 검사(existsByUserId) 통과 이후에도, 커밋 전에 동시에 같은 유저로
    // 먼저 등록을 마친 다른 요청(중복 클릭 등)이 있으면 user_id 유니크 제약에 걸릴 수 있다 -
    // changeNickname()과 동일한 이유로 INSERT/재확인 둘 다 REQUIRES_NEW로 분리한다.
    private void savePreferenceOrThrowIfAlreadyRegistered(Long userId, UserPreference preference) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> userPreferenceRepository.saveAndFlush(preference));
        } catch (DataIntegrityViolationException e) {
            boolean alreadyRegistered = Boolean.TRUE.equals(
                    requiresNewTransactionTemplate.execute(status -> userPreferenceRepository.existsByUserId(userId)));
            if (alreadyRegistered) {
                throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_EXISTS);
            }
            throw e;
        }
    }

    // updateMyProfile()의 이 경로는 "미리 온보딩하지 않은 기존 유저도 그냥 되게 하자"는 편의
    // 처리라, registerProfile()과 달리 유니크 제약에 걸려도 에러로 막을 이유가 없다 - 동시에
    // 같은 유저로 처음 preference를 만드는 레이스에서 진 쪽은, 이긴 쪽이 방금 커밋한 행을 그냥
    // 재사용한다. 재조회도 REQUIRES_NEW로 해야 한다(changeNickname()과 동일한 이유).
    private UserPreference saveOrFetchExistingPreference(Long userId, User user) {
        UserPreference candidate = UserPreference.builder().user(user).build();
        try {
            return requiresNewTransactionTemplate.execute(status -> userPreferenceRepository.saveAndFlush(candidate));
        } catch (DataIntegrityViolationException e) {
            return requiresNewTransactionTemplate.execute(status -> userPreferenceRepository.findByUserId(userId)
                    .orElseThrow(() -> e));
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
