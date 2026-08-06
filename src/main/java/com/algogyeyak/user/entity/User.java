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
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

// @DynamicUpdate: 기본값(정적 UPDATE, 매핑된 모든 컬럼을 포함)이면 한 트랜잭션이 필드 하나만
// 바꿔도 그 시점 스냅샷의 나머지 모든 컬럼 값을 그대로 다시 써넣는다 - 동시에 다른 트랜잭션이
// 다른 필드를 바꿔 먼저 커밋했다면, 나중에 커밋하는 쪽이 그 변경을 조용히 되돌려버린다(예: 관리자가
// role을 바꾸는 사이 사용자가 본인 탈퇴(withdraw)를 먼저 커밋하면, 관리자 쪽 UPDATE가 익명화된
// email/nickname/passwordHash/status를 탈퇴 전 값으로 되돌려 PII를 되살릴 수 있었다). 실제로
// 변경된 컬럼만 UPDATE에 포함시켜 이 클래스를 막는다.
@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicUpdate
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
    // createLocalUser)는 항상 Role.USER로 생성된다 - 가입 시점에 스스로 ADMIN이 될 방법은 이
    // 메서드 하나뿐이다. (가입 이후에는 이미 ADMIN인 다른 관리자가 changeRole()로 승격시켜줄 수
    // 있다 - 관리자 페이지의 일반 권한 변경 액션, AdminUserService.updateRole 참고.)
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
    // suspend()/activate()와 동일한 이유로 탈퇴한 사용자는 대상에서 제외한다 - 탈퇴 처리로 이미
    // 익명화(닉네임/이메일 대체)된 계정이라 권한을 바꿔봐야 그 계정으로 로그인할 방법 자체가 없고,
    // 형제 메서드(suspend/activate)만 이 가드가 있고 changeRole()만 빠져 있던 불일치였다.
    public void changeRole(Role role) {
        if (isWithdrawn()) {
            throw new BusinessException(ErrorCode.ADMIN_INVALID_ROLE_TRANSITION, "탈퇴한 사용자는 권한을 변경할 수 없습니다.");
        }
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
