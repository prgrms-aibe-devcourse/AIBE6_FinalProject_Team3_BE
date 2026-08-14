package com.algogyeyak.user.service;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.dto.PresignedUploadRequest;
import com.algogyeyak.global.s3.dto.PresignedUploadResponse;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import com.algogyeyak.global.s3.util.S3KeyGenerator;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.dto.NicknameCheckResponse;
import com.algogyeyak.user.dto.NicknamePolicy;
import com.algogyeyak.user.dto.ProfileRegisterRequest;
import com.algogyeyak.user.dto.ProfileUpdateRequest;
import com.algogyeyak.user.dto.UserProfileResponse;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserPreference;
import com.algogyeyak.user.repository.UserPreferenceRepository;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String INACTIVE_USER_MESSAGE = "존재하지 않거나 탈퇴한 사용자입니다.";

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final UserSocialAccountRepository userSocialAccountRepository;
    private final ChecklistRepository checklistRepository;
    private final PropertyRepository propertyRepository;
    private final S3PresignService s3PresignService;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public UserService(
            UserRepository userRepository,
            UserPreferenceRepository userPreferenceRepository,
            UserSocialAccountRepository userSocialAccountRepository,
            ChecklistRepository checklistRepository,
            PropertyRepository propertyRepository,
            S3PresignService s3PresignService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.userSocialAccountRepository = userSocialAccountRepository;
        this.checklistRepository = checklistRepository;
        this.propertyRepository = propertyRepository;
        this.s3PresignService = s3PresignService;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UserProfileResponse getMyProfile(Long userId) {
        User user = getActiveUserOrThrow(userId);
        UserPreference preference = userPreferenceRepository.findByUserId(userId).orElse(null);
        return toResponse(user, preference);
    }

    // userId는 로그인된 사용자가 프로필 수정 화면에서 호출하면 채워지고(본인 제외 검사),
    // 회원가입 화면(로그인 전)에서 호출하면 null이다. existsByNicknameAndIdNot(nickname, null)을
    // 그대로 쓰면 SQL의 "id <> NULL"이 항상 거짓으로 평가돼 무조건 available=true가 나와버리므로,
    // null일 때는 본인 제외 없이 전체 중복만 검사하는 existsByNickname으로 분기해야 한다.
    public NicknameCheckResponse checkNicknameAvailable(Long userId, String nickname) {
        if (!StringUtils.hasText(nickname)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "닉네임을 입력해 주세요.");
        }

        boolean exists = userId != null
                ? userRepository.existsByNicknameAndIdNot(nickname, userId)
                : userRepository.existsByNickname(nickname);
        return NicknameCheckResponse.builder().available(!exists).build();
    }

    // 닉네임은 회원가입(로컬 이메일/비밀번호 signup 또는 소셜 로그인 콜백) 시점에 이미 확정되어
    // 있고, 이후 바꾸는 유일한 경로는 updateMyProfile()(PATCH /users/me)이다 - 프론트도 이 등록
    // 화면엔 닉네임 입력 UI 자체가 없어 항상 기존 값 그대로 넘어온다. 그래서 이 메서드는 닉네임을
    // 다루지 않는다(과거엔 요청에 담긴 닉네임이 기존과 다르면 여기서도 바꿔주는 로직이 있었으나,
    // 실제로 호출될 경로가 없는 죽은 코드라 제거함 - ProfileRegisterRequest.nickname 필드도 함께 제거).
    @Transactional
    public UserProfileResponse registerProfile(Long userId, ProfileRegisterRequest request) {
        User user = getActiveUserOrThrow(userId);

        if (userPreferenceRepository.existsByUserId(userId)) {
            throw new BusinessException(ErrorCode.USER_PROFILE_ALREADY_EXISTS);
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

    /**
     * 알려진 한계(조회 후 저장 방식이라 원자적이지 않음): 같은 유저가 두 탭에서 거의 동시에
     * confirmProfileImageUpload()/resetProfileImage()를 각각 호출하면, 나중에 커밋하는 쪽이
     * profileImageUrl 컬럼을 그대로 덮어써 먼저 커밋된 쪽의 결과가 조용히 사라질 수 있다(진
     * 쪽이 confirm이었다면 그 S3 객체는 이미 PENDING_UPLOAD_TAG가 지워진 뒤라 이후로도 참조하는
     * 행이 없어 영구 orphan이 된다). 본인 계정 내 자기 자신과의 레이스라 다른 사용자에게 영향이
     * 없고, 실제로 두 탭에서 거의 동시에 프로필 사진을 바꾸는 빈도가 매우 낮아 감수하기로 함 -
     * AdminChecklistTemplateService.validateCode와 동일한 판단 기준.
     */
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
            validateNicknameFormat(request.getNickname());
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
        // User.withdraw()가 profileImageUrl을 직접 null로 비우기 전에 먼저 캡처해둔다 - 안 그러면
        // resetProfileImage()/confirmProfileImageUpload()와 달리 이 S3 객체를 정리할 방법이 없어,
        // 탈퇴할 때마다 실제 소유자가 없는 이미지가 영구적으로 S3에 남는다.
        String previousImageUrl = user.getProfileImageUrl();
        user.withdraw();
        deletePreviousProfileImageIfOwned(userId, previousImageUrl);

        // OAuth 연동 정보는 하드 삭제한다 - 남겨두면 (provider, provider_id) unique 제약 때문에
        // 같은 소셜 계정으로 재가입하려는 탈퇴자를 영구히 막게 된다. 다른 도메인이 이 테이블을
        // 참조하지 않아 하드 삭제해도 안전하다.
        userSocialAccountRepository.deleteAllByUserId(userId);

        // 본인 전용 검색 선호도 설정도 하드 삭제한다 - 다른 도메인이 참조하지 않는다.
        userPreferenceRepository.deleteByUserId(userId);

        // 본인 소유 체크리스트도 하드 삭제한다 - 매물별 개인 임장노트라 다른 유저가 참조하지 않고,
        // ChecklistItem은 cascade(orphanRemoval)로 함께 정리된다.
        List<Checklist> checklists = checklistRepository.findAllByUserId(userId);
        checklistRepository.deleteAll(checklists);

        // 매물은 하드 삭제하지 않고 기존 soft-delete(Property.delete())를 그대로 재사용한다 - 다른
        // 유저의 checklist/risk-analysis(PropertyRisk 등)가 이 매물을 참조하고 있어서, row 자체를
        // 지우면 그쪽 데이터가 깨지거나(FK) 허위매물 탐지 이력이 함께 사라진다.
        List<Property> properties = propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, PropertyStatus.ACTIVE);
        properties.forEach(Property::delete);
    }

    // "존재하지 않음"/"탈퇴함"/"정지됨"을 서로 다른 ErrorCode로 구분해서 던진다 - 프론트가 각
    // 상태에 맞는 안내 문구를 보여줄 수 있어야 하기 때문이다.
    private User getActiveUserOrThrow(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, INACTIVE_USER_MESSAGE));
        if (user.isWithdrawn()) {
            throw new BusinessException(ErrorCode.USER_WITHDRAWN);
        }
        if (user.isSuspended()) {
            throw new BusinessException(ErrorCode.USER_SUSPENDED);
        }
        return user;
    }

    // 닉네임 형식은 "실제로 값이 바뀔 때"만 검사한다(registerProfile/updateMyProfile 호출부 참고) -
    // ProfileRegisterRequest/ProfileUpdateRequest에 @Pattern을 걸어 항상 검사하면, OAuth 가입
    // 사용자처럼 이 정책을 거치지 않고 만들어진 기존 닉네임(카카오/구글이 내려준 값 그대로, 공백·
    // 특수문자 포함 가능)을 프론트가 매 요청 그대로 다시 실어 보낼 때마다(닉네임을 안 바꿔도) 검증에
    // 걸려 프로필 등록/수정 자체가 막히는 문제가 있었다 - 닉네임 중복 검사(validateNicknameNotDuplicated)와
    // 같은 이유로 같은 조건에서만 동작해야 한다.
    private void validateNicknameFormat(String nickname) {
        if (!Pattern.matches(NicknamePolicy.PATTERN, nickname)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, NicknamePolicy.MESSAGE);
        }
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
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, INACTIVE_USER_MESSAGE));
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
    // 먼저 등록을 마친 다른 요청(중복 클릭 등)이 있으면 user_id 유니크 제약에 걸릴 수 있다.
    // saveOrFetchExistingPreference()와 달리 여기선 "이미 등록됨"을 성공으로 흡수하지 않고 정확한
    // 409로 알려야 하므로, REQUIRES_NEW(별도 세션)로 분리해 실패해도 폐기되는 세션이 이 임시
    // 트랜잭션뿐이도록 격리하고, 실패 시 재확인해 정확한 에러로 변환한다(changeNickname()과 동일한 이유).
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
