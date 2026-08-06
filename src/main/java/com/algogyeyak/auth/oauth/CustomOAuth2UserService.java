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

        return new FindOrCreateResult(createUser(provider, userInfo, nickname), false);
    }

    // 로컬 로그인(LocalAuthService.login)/refresh(RefreshTokenService.rotate)는 이미 탈퇴·정지 계정을
    // 거부하는데, 소셜 로그인만 이 검사가 빠져 있으면 정지된 계정도 새 토큰을 계속 발급받을 수 있다.
    private void rejectIfBlocked(User user) {
        if (user.isWithdrawn() || user.isSuspended()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_blocked", "로그인할 수 없는 계정입니다.", null));
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
            // 이 재조회도 createUser()의 winner 재조회와 같은 이유로 REQUIRES_NEW(새 스냅샷)에서 해야
            // 한다 - 바깥(findOrCreateUser) 트랜잭션은 이미 findByProviderAndProviderId 등을 먼저 읽어
            // 스냅샷을 확보해둔 상태라(MySQL InnoDB 기본 격리수준 REPEATABLE READ 기준), 그 스냅샷으로
            // 재조회하면 방금 경쟁에서 이긴 다른 트랜잭션의 커밋이 안 보여 "이미 연동됨"을 놓치고
            // 실제로는 정상 상황인데도 이 예외가 그대로 흘러나갈 수 있다.
            boolean alreadyLinked = Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status ->
                    userSocialAccountRepository.findByProviderAndProviderId(provider, providerId).isPresent()));
            if (!alreadyLinked) {
                throw e;
            }
        }
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

    private User createUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname) {
        return createUser(provider, userInfo, nickname, true);
    }

    // allowNicknameFallback: 닉네임 충돌로 복구 재시도를 한 번 했다면(아래 참고) 다시 재귀 호출할 때
    // false로 넘겨 무한 재시도를 막는다 - 그 재시도에서 쓰는 provider+providerId 조합 닉네임은 이
    // 메서드 진입 시 이미 provider+providerId 재조회를 한 번 거치므로, 그마저 충돌한다면 원인은
    // 닉네임이 아니라 진짜 동시 레이스(같은 provider+providerId로 동시 첫 로그인)일 수밖에 없다.
    private User createUser(AuthProvider provider, OAuth2UserInfo userInfo, String nickname, boolean allowNicknameFallback) {
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
            return newUser;
        } catch (DataIntegrityViolationException e) {
            // 같은 provider+providerId로 동시에 첫 로그인이 들어와 유니크 제약에 걸린 경우, 또는
            // 검증된 이메일로 동시에 가입/연동이 먼저 커밋된 경우 — 먼저 커밋된 쪽의 row를 그대로
            // 사용한다(드문 동시 레이스 대비). 이 재조회는 REQUIRES_NEW(새 스냅샷)에서 해야 한다 -
            // 이 메서드를 호출한 findOrCreateUser()의 바깥 트랜잭션은 이미 findByProviderAndProviderId
            // 등을 먼저 읽어 스냅샷을 확보해둔 상태라(MySQL InnoDB 기본 격리수준 REPEATABLE READ
            // 기준), 그 스냅샷으로 재조회하면 방금 경쟁에서 이긴 다른 트랜잭션의 커밋이 안 보여
            // winner를 못 찾고, 실제로는 계정이 있는데도 email_conflict로 로그인이 실패할 수 있다.
            Optional<User> winner = requiresNewTransactionTemplate.execute(status ->
                    userSocialAccountRepository.findByProviderAndProviderId(provider, userInfo.getProviderId())
                            .map(UserSocialAccount::getUser)
                            .or(() -> findVerifiedEmailMatch(userInfo)));

            if (winner.isPresent()) {
                User found = winner.get();
                // 동시 레이스로 복구된 winner도 다른 모든 경로와 동일하게 정지/탈퇴 여부를 확인해야
                // 한다 - 그렇지 않으면 이 레이스 케이스만 그 검사를 우회하게 된다.
                rejectIfBlocked(found);
                return found;
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
}
