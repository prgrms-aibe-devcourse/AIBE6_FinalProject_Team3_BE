package com.algogyeyak.user.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
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
@Table(name = "users")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private User(String email, String passwordHash, String nickname, String profileImageUrl, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
        this.profileImageUrl = profileImageUrl;
        this.role = role;
    }

    public static User createOAuthUser(String email, String nickname, String profileImageUrl) {
        return new User(email, null, nickname, profileImageUrl, Role.USER);
    }

    public static User createLocalUser(String email, String passwordHash, String nickname) {
        return new User(email, passwordHash, nickname, null, Role.USER);
    }

    public void updateNickname(@Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.") String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
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

    public boolean isSuspended() {
        return this.status == UserStatus.SUSPENDED;
    }

    // 관리자 페이지 전용. 탈퇴한 사용자는 이미 익명화되어 되돌릴 수 없는 상태라 정지/활성화 대상에서 제외한다.
    public void suspend() {
        if (isWithdrawn()) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "탈퇴한 사용자는 정지할 수 없습니다.");
        }
        this.status = UserStatus.SUSPENDED;
    }

    public void activate() {
        if (isWithdrawn()) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_STATUS_TRANSITION, "탈퇴한 사용자는 활성화할 수 없습니다.");
        }
        this.status = UserStatus.ACTIVE;
    }

    // grantAdminRole()과 달리 ADMIN->USER 강등도 허용한다 (관리자 페이지의 일반 권한 변경 액션).
    public void changeRole(Role role) {
        this.role = role;
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
