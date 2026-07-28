package com.algogyeyak.user.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.enums.AuthProvider;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_provider_provider_id", columnNames = {"provider", "provider_id"})
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    private static final String WITHDRAWN_NICKNAME_PREFIX = "탈퇴회원_";
    private static final String WITHDRAWN_EMAIL_DOMAIN = "withdrawn.algogyeyak.local";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email; // 소셜 전용 가입 시 null 가능

    private String passwordHash; // 소셜 전용 가입 시 null

    @Column(unique = true, nullable = false)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private User(String email, String passwordHash, String nickname, String profileImageUrl, AuthProvider provider, String providerId, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
        this.profileImageUrl = profileImageUrl;
        this.provider = provider;
        this.providerId = providerId;
        this.role = role;
    }

    public static User createOAuthUser(String email, String nickname, String profileImageUrl, AuthProvider provider, String providerId) {
        return new User(email, null, nickname, profileImageUrl, provider, providerId, Role.USER);
    }

    // 로컬 회원은 provider+provider_id 유니크 제약을 email로 대신 만족시킨다 — 소셜 로그인처럼
    // 별도 식별자가 없고, email 자체가 이미 유니크하므로 조합 제약과 충돌하지 않는다.
    public static User createLocalUser(String email, String passwordHash, String nickname) {
        return new User(email, passwordHash, nickname, null, AuthProvider.LOCAL, email, Role.USER);
    }

    public void updateNickname(@Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.") String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    /**
     * 이미 존재하는 계정(로컬 가입 또는 다른 소셜 제공자)에 새 소셜 로그인 수단을 연결한다.
     * provider/providerId만 이번에 사용한 로그인 수단으로 덮어쓰고, email/닉네임/프로필사진/
     * passwordHash는 그대로 둔다 — 그래야 로컬 로그인(passwordHash 존재 여부로만 판단)이나
     * 이전에 연결했던 다른 소셜 로그인도 계속 사용할 수 있다(다음 로그인 때 email로 재조회되어
     * provider/providerId가 다시 그 시점의 로그인 수단으로 갱신됨).
     *
     * 이 방향(소셜 로그인 → 기존 계정 연결)만 안전하다: OAuth 제공자가 이미 이메일 소유권을
     * 검증했기 때문이다. 반대 방향(로컬 가입 시 이미 존재하는 소셜 계정에 비밀번호를 붙이는 것)은
     * 이메일 소유권 검증 없이 아무나 타인의 이메일로 가입 시도만으로 계정을 탈취할 수 있게 되므로
     * 절대 허용하면 안 된다 — LocalAuthService.signup은 이메일 중복을 그대로 거부해야 한다.
     */
    public void linkProvider(AuthProvider provider, String providerId) {
        this.provider = provider;
        this.providerId = providerId;
    }

    // 소셜 전용으로 가입한 계정이 처음 비밀번호를 설정하거나 기존 비밀번호를 변경할 때 사용한다.
    // 현재 비밀번호 검증(이미 설정되어 있는 경우)은 호출 전에 서비스 레이어(LocalAuthService)에서
    // 끝내고 온다는 전제다.
    public void updatePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    // 개발용 admin 시드 계정(AdminAccountSeeder)에서만 사용한다. 일반 가입 경로(createOAuthUser/
    // createLocalUser)는 항상 Role.USER로 생성되며, 이 메서드 외에는 ADMIN으로 승격할 방법이 없다.
    public void grantAdminRole() {
        this.role = Role.ADMIN;
    }

    public boolean isWithdrawn() {
        return this.status == UserStatus.WITHDRAWN;
    }

    /**
     * 회원 탈퇴 처리
     * - 상태를 WITHDRAWN으로 변경
     * - email, nickname, passwordHash, profileImageUrl을 복원 불가능한 형태로 익명화
     * - email/nickname은 unique 제약이 있어 id 기반으로 유일성을 보장하며 치환
     */
    public void withdraw() {
        if (isWithdrawn()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이미 탈퇴한 사용자입니다.");
        }

        this.status = UserStatus.WITHDRAWN;
        this.nickname = WITHDRAWN_NICKNAME_PREFIX + this.id;
        this.email = (this.email != null) ? "withdrawn_" + this.id + "@" + WITHDRAWN_EMAIL_DOMAIN : null;
        this.passwordHash = null;
        this.profileImageUrl = null;
    }
}
