package com.algogyeyak.auth.oauth;

import com.algogyeyak.auth.util.EmailNormalizer;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.entity.UserSocialAccount;
import com.algogyeyak.user.repository.UserRepository;
import com.algogyeyak.user.repository.UserSocialAccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository userSocialAccountRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public CustomOAuth2UserService(
            UserRepository userRepository,
            UserSocialAccountRepository userSocialAccountRepository,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userSocialAccountRepository = userSocialAccountRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        return processOAuth2User(registrationId, oAuth2User);
    }

    /**
     * provider가 이미 응답한 {@link OAuth2User} 속성으로 가입 여부를 확인하고 User를 생성/재사용한다.
     * {@code super.loadUser(...)}(실제 HTTP 호출)와 분리해둔 덕에, 이 메서드는 속성 Map만 있으면
     * 테스트에서 HTTP 목킹 없이 바로 호출해 검증할 수 있다.
     */
    OAuth2User processOAuth2User(String registrationId, OAuth2User oAuth2User) {
        OAuth2UserInfo userInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, oAuth2User.getAttributes());
        AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase());

        String nickname = userInfo.getNickname() != null
                ? userInfo.getNickname()
                : provider.name().toLowerCase() + "_" + userInfo.getProviderId();

        FindOrCreateResult result = findOrCreateUser(provider, userInfo, nickname);

        return new CustomOAuth2User(result.user(), oAuth2User.getAttributes(), result.linked());
    }

    // linked: 새 계정 생성이 아니라 기존 계정(로컬 가입 또는 다른 소셜)에 이번 로그인 수단을 막
    // 연결한 경우 true — OAuth2AuthenticationSuccessHandler가 이 값을 보고 프론트에 안내를 띄운다.
    private record FindOrCreateResult(User user, boolean linked) {
    }

    // 재로그인 시 기존 회원의 닉네임/프로필 사진은 OAuth 제공자 값으로 덮어쓰지 않는다.
    // 최초 가입 이후에는 프로필 등록/수정 화면에서 관리하는 값이 우선하므로 그대로 재사용한다.
    //
    // UserSocialAccount가 "이 유저가 실제로 연동해둔 모든 소셜 계정"의 유일한 소스다.
    private FindOrCreateResult findOrCreateUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname) {
        Optional<UserSocialAccount> bySocialAccount =
                userSocialAccountRepository.findByProviderAndProviderId(provider, userInfo.getProviderId());
        if (bySocialAccount.isPresent()) {
            User user = bySocialAccount.get().getUser();
            rejectIfBlocked(user);
            return new FindOrCreateResult(user, false);
        }

        Optional<User> linked = linkToExistingAccountByEmail(provider, userInfo);
        if (linked.isPresent()) {
            return new FindOrCreateResult(linked.get(), true);
        }

        return createUser(provider, userInfo, nickname);
    }

    // 로컬 로그인(LocalAuthService.login)/refresh(RefreshTokenService.rotate)는 이미 탈퇴·정지 계정을
    // 거부하는데, 소셜 로그인만 이 검사가 빠져 있으면 정지된 계정도 새 토큰을 계속 발급받을 수 있다.
    //
    // 에러 코드는 일반 실패("oauth_login_failed")와 동일하게 둔다 - 로컬 로그인/토큰 필터는
    // "탈퇴/정지된 계정" 사유를 노출하지 않고 전부 같은 실패로 응답하는데(AUTH_INVALID_CREDENTIALS 등,
    // 계정 존재 여부 비노출 원칙), 소셜 로그인만 "account_blocked"로 구체적인 사유를 알려주면
    // 그 계정이 실제로 존재하고 정지 상태라는 것이 새어나가 정책이 어긋난다.
    private void rejectIfBlocked(User user) {
        if (user.isWithdrawn() || user.isSuspended()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("oauth_login_failed", "로그인할 수 없는 계정입니다.", null));
        }
    }

    /**
     * 이 provider+providerId로는 처음 로그인하는 경우, 같은 이메일로 이미 존재하는 계정(로컬 가입
     * 또는 다른 소셜 제공자)이 있는지 찾아 연결한다. {@link #findVerifiedEmailMatch}가 검증된
     * 이메일에 대해서만 결과를 주므로 이 방향의 자동 연동은 안전하다 — 반대로 로컬 가입이 기존
     * 소셜 계정에 비밀번호를 붙이는 것은 이메일 소유권 검증이 없어 계정 탈취로 이어질 수 있어
     * 절대 허용하지 않는다({@link com.algogyeyak.auth.service.LocalAuthService#signup}은 이메일
     * 중복을 거부).
     */
    private Optional<User> linkToExistingAccountByEmail(AuthProvider provider, OAuth2UserInfo userInfo) {
        return findVerifiedEmailMatch(userInfo).map(user -> {
            rejectIfBlocked(user);
            linkNewSocialAccount(user, provider, userInfo.getProviderId());
            return user;
        });
    }

    // createUser()와 동일한 이유(같은 세션에서 유니크 제약 위반 후 그 세션으로 쿼리를 이어가면
    // Hibernate가 AssertionFailure를 던짐)로 INSERT를 REQUIRES_NEW로 분리한다. 동시에 같은 계정을
    // 같은 provider로 연동하려는 요청이 겹치는 극히 드문 레이스만 대비하는 것이라, 이미 연동이
    // 끝나 있으면(경쟁에서 진 쪽) 그냥 그 결과를 받아들이고 넘어간다.
    private void linkNewSocialAccount(User user, AuthProvider provider, String providerId) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                    userSocialAccountRepository.saveAndFlush(UserSocialAccount.of(user, provider, providerId)));
        } catch (DataIntegrityViolationException e) {
            // UserSocialAccount는 uk_social_provider_provider_id(같은 소셜 계정이 서로 다른 User
            // 둘에게 동시에 연결될 수 없음)와 uk_social_user_provider(한 User가 같은 provider를
            // 두 개 연동할 수 없음) 두 유니크 제약을 갖는다 - 이 둘을 구분하지 않고 provider+providerId
            // 존재 여부만 보면, 후자로 걸린 경우(이 User가 같은 provider의 다른 providerId를 이미
            // 연동해둔 극히 드문 데이터 불일치)를 "아직 연동 안 됨"으로 오판해 raw
            // DataIntegrityViolationException을 그대로 던지게 된다.
            //
            // "이미 연동됨"으로 판단할 때도 그 연동이 실제로 지금 로그인시키려는 이 user의 것인지
            // 확인해야 한다 - 존재 여부만 보고 통과시키면, 극히 드문 레이스로 이 provider+providerId가
            // 다른 user에게 먼저 붙어버린 경우에도 조용히 성공 처리해 잘못된 계정에 로그인시킬 수 있다.
            //
            // 이 재확인도 바깥(loadUser()) 트랜잭션에서 그냥 실행하면 안 된다 - MySQL 기본
            // REPEATABLE READ에서는 바깥 트랜잭션이 경쟁 요청의 커밋 전 스냅샷을 이미 고정하고
            // 있을 수 있어, 실제로는 방금 연동이 끝났는데도 이 재확인이 stale한 "아직 없음"을
            // 돌려줄 수 있다 - REQUIRES_NEW(별도 세션)로 분리해 항상 최신 커밋 상태를 보게 한다.
            SocialAccountConflictType conflictType = requiresNewTransactionTemplate.execute(status -> {
                Optional<UserSocialAccount> bySocialAccount =
                        userSocialAccountRepository.findByProviderAndProviderId(provider, providerId);
                if (bySocialAccount.isPresent()) {
                    boolean belongsToThisUser = bySocialAccount.get().getUser().getId().equals(user.getId());
                    return belongsToThisUser
                            ? SocialAccountConflictType.ALREADY_LINKED_TO_THIS_USER
                            : SocialAccountConflictType.LINKED_TO_DIFFERENT_USER;
                }
                if (userSocialAccountRepository.existsByUserIdAndProvider(user.getId(), provider)) {
                    return SocialAccountConflictType.USER_ALREADY_HAS_DIFFERENT_ACCOUNT_FOR_PROVIDER;
                }
                return SocialAccountConflictType.UNRECOVERABLE;
            });

            switch (conflictType) {
                case ALREADY_LINKED_TO_THIS_USER -> {
                    // 이미 이 user에게 연동이 끝나 있으면(경쟁에서 진 쪽) 그냥 그 결과를 받아들이고 넘어간다.
                }
                case LINKED_TO_DIFFERENT_USER, USER_ALREADY_HAS_DIFFERENT_ACCOUNT_FOR_PROVIDER ->
                        throw new OAuth2AuthenticationException(new OAuth2Error(
                                "social_account_conflict", "이 계정에는 이미 다른 소셜 계정이 연동되어 있습니다.", null), e);
                case UNRECOVERABLE -> throw e;
            }
        }
    }

    private enum SocialAccountConflictType {
        ALREADY_LINKED_TO_THIS_USER,
        LINKED_TO_DIFFERENT_USER,
        USER_ALREADY_HAS_DIFFERENT_ACCOUNT_FOR_PROVIDER,
        UNRECOVERABLE
    }

    /**
     * OAuth 제공자가 이메일 소유권을 검증해준 경우에만 이메일로 기존 계정을 찾는다. 검증되지 않은
     * 이메일(Kakao의 {@code is_email_verified=false}, 아직 인증 전인 Google 계정 등)은 그 이메일의
     * 실제 소유자가 아니어도 주장할 수 있는 값이라, 자동 연동은 물론 아래 {@link #createUser}의
     * 유니크 제약 충돌 복구에도 사용하면 안 된다 — 그러지 않으면 검증 안 된 이메일 하나로 남의
     * 계정에 로그인하게 될 수 있다.
     */
    private Optional<User> findVerifiedEmailMatch(OAuth2UserInfo userInfo) {
        if (!userInfo.isEmailVerified()) {
            return Optional.empty();
        }
        String email = EmailNormalizer.normalize(userInfo.getEmail());
        return email == null ? Optional.empty() : userRepository.findByEmail(email);
    }

    private FindOrCreateResult createUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname) {
        return createUser(provider, userInfo, nickname, true);
    }

    // allowNicknameFallback: 닉네임 충돌로 복구 재시도를 한 번 했다면(아래 참고) 다시 재귀 호출할 때
    // false로 넘겨 무한 재시도를 막는다 - 그 재시도에서 쓰는 provider+providerId 조합 닉네임은 이
    // 메서드 진입 시 이미 provider+providerId 재조회를 한 번 거치므로, 그마저 충돌한다면 원인은
    // 닉네임이 아니라 진짜 동시 레이스(같은 provider+providerId로 동시 첫 로그인)일 수밖에 없다.
    private FindOrCreateResult createUser(
            AuthProvider provider, OAuth2UserInfo userInfo, String nickname, boolean allowNicknameFallback) {
        // 검증되지 않은 이메일은 저장하지 않고 null로 둔다. 저장해버리면, 나중에 이 이메일의 실제
        // 소유자가 검증된 OAuth(다른 provider 포함)로 로그인할 때 findVerifiedEmailMatch가 "이미
        // 존재하는 계정"으로 착각해 이 row에 연동해버린다 — 검증 안 된 이메일로 아무나 먼저 만들어둔
        // 계정에 진짜 소유자가 합쳐지는 계정 탈취로 이어질 수 있다. null이면 findByEmail로 절대
        // 찾을 수 없으니 이 위험 자체가 차단된다. 로컬 가입/로그인과 동일한 정규화를 거치는 것도
        // 마찬가지 이유(대소문자만 다른 이메일이 다른 계정으로 취급되는 것 방지)다.
        String email = userInfo.isEmailVerified() ? EmailNormalizer.normalize(userInfo.getEmail()) : null;
        User newUser = User.createOAuthUser(email, nickname, userInfo.getProfileImageUrl());

        try {
            // INSERT를 별도(REQUIRES_NEW) 트랜잭션/세션에서 시도한다. 같은 세션에서 saveAndFlush가
            // 유니크 제약 위반으로 실패한 뒤 그 세션으로 쿼리를 이어가면 Hibernate가
            // "세션이 예외 이후 flush됨(AssertionFailure)"을 던진다 — RefreshTokenService.issue()에서
            // 실제 H2로 재현 확인한 것과 동일한 문제라 같은 방식으로 격리한다. 실패해도 폐기되는
            // 세션이 이 임시 트랜잭션뿐이도록 분리해, 바깥(loadUser()) 트랜잭션의 세션은 정상 상태로 남는다.
            // User와 그 첫 UserSocialAccount는 항상 함께 존재해야 하므로 같은 REQUIRES_NEW 트랜잭션
            // 안에서 같이 커밋/롤백되게 한다.
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                userRepository.saveAndFlush(newUser);
                userSocialAccountRepository.saveAndFlush(UserSocialAccount.of(newUser, provider, userInfo.getProviderId()));
            });
            return new FindOrCreateResult(newUser, false);
        } catch (DataIntegrityViolationException e) {
            // 같은 provider+providerId로 동시에 첫 로그인이 들어와 유니크 제약에 걸린 경우, 또는
            // 검증된 이메일로 동시에 가입/연동이 먼저 커밋된 경우 — 먼저 커밋된 쪽의 row를 그대로
            // 사용한다(드문 동시 레이스 대비). 이 복구 조회도 바깥(loadUser()) 트랜잭션에서 그냥
            // 실행하면 안 된다 - MySQL 기본 REPEATABLE READ에서는 바깥 트랜잭션이 경쟁 요청의 커밋
            // 전 스냅샷을 이미 고정하고 있을 수 있어, 방금 커밋된 winner row를 stale snapshot
            // 때문에 못 볼 수 있다. REQUIRES_NEW(별도 세션)로 분리해 항상 최신 커밋 상태를 보게 한다.
            RecoveredWinner recovered = requiresNewTransactionTemplate.execute(status -> {
                Optional<User> bySocialAccount = userSocialAccountRepository
                        .findByProviderAndProviderId(provider, userInfo.getProviderId())
                        .map(UserSocialAccount::getUser);
                if (bySocialAccount.isPresent()) {
                    return new RecoveredWinner(bySocialAccount.get(), true);
                }
                return findVerifiedEmailMatch(userInfo).map(user -> new RecoveredWinner(user, false)).orElse(null);
            });

            if (recovered != null) {
                // 동시 레이스로 복구된 winner도 다른 모든 경로와 동일하게 정지/탈퇴 여부를 확인해야
                // 한다 - 그렇지 않으면 이 레이스 케이스만 그 검사를 우회하게 된다.
                rejectIfBlocked(recovered.user());

                if (recovered.alreadyLinkedToThisProvider()) {
                    return new FindOrCreateResult(recovered.user(), false);
                }

                // provider+providerId로는 아직 연동되지 않은 상태로(검증된 이메일로만) 기존 계정을
                // 찾은 경우 - 이 provider를 실제로 연동해야 한다. 안 그러면 user_social_accounts에
                // 이 provider가 영영 안 남아, 다음 로그인마다 이 유니크 제약 위반 → 복구를 매번
                // 반복하게 된다.
                linkNewSocialAccount(recovered.user(), provider, userInfo.getProviderId());
                return new FindOrCreateResult(recovered.user(), true);
            }

            // provider+providerId도, 검증된 이메일도 못 찾았다면 이 유니크 제약 위반은 사실 닉네임
            // 충돌일 가능성이 높다 - User 테이블의 유니크 제약은 email/nickname/(provider,providerId)
            // 뿐이다. OAuth 가입은 로컬 가입과 달리 유저가 닉네임을 미리 고르거나 중복 확인을 거칠
            // 기회가 없으므로, provider가 매번 내려주는 닉네임이 다른 유저와 우연히 겹치기만 해도
            // 이 계정은 재시도해도 항상 같은 닉네임으로 다시 시도해 영원히 가입이 불가능해진다 -
            // 아래에서 이 원인을 확인하지 않으면 "이미 사용 중인 이메일입니다"라는 잘못된 메시지로
            // 영구 차단되는 실제 유저가 생긴다. provider+providerId로 만든 닉네임(getNickname()이
            // null일 때 이미 쓰는 것과 같은 fallback)은 이 유저에게만 유일하므로, 그 값으로 한 번만
            // 재시도한다.
            boolean nicknameConflict = allowNicknameFallback && Boolean.TRUE.equals(
                    requiresNewTransactionTemplate.execute(status -> userRepository.existsByNickname(nickname)));
            if (nicknameConflict) {
                String fallbackNickname = provider.name().toLowerCase() + "_" + userInfo.getProviderId();
                return createUser(provider, userInfo, fallbackNickname, false);
            }

            // 그마저도 아니면(검증 안 된 이메일이라 위 조회가 애초에 empty를 준 경우 포함) 이 예외를
            // raw로 흘려보내는 대신 AuthenticationException으로 감싼다 — 그래야
            // OAuth2LoginAuthenticationFilter가 이를 잡아 OAuth2AuthenticationFailureHandler로
            // 정상적으로 프론트에 에러 리다이렉트를 보내고, 서블릿까지 예외가 올라가 500으로
            // 크래시하는 것을 막는다.
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("email_conflict", "이미 사용 중인 이메일입니다.", null), e);
        }
    }

    private record RecoveredWinner(User user, boolean alreadyLinkedToThisProvider) {
    }
}
