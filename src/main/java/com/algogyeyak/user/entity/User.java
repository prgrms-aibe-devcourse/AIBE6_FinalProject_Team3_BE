package com.algogyeyak.user.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
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
    private User(String email, String nickname, String profileImageUrl, AuthProvider provider, String providerId, Role role) {
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
        return new User(email, nickname, profileImageUrl, provider, providerId, Role.USER);
    }

    public void updateProfile(String nickname, String profileImageUrl) {
        this.nickname = nickname;
    }

    public void updateNickname(@Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.") String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
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
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 탈퇴한 사용자입니다.");
        }

        this.status = UserStatus.WITHDRAWN;
        this.nickname = WITHDRAWN_NICKNAME_PREFIX + this.id;
        this.email = (this.email != null) ? "withdrawn_" + this.id + "@" + WITHDRAWN_EMAIL_DOMAIN : null;
        this.passwordHash = null;
        this.profileImageUrl = null;
    }
}
