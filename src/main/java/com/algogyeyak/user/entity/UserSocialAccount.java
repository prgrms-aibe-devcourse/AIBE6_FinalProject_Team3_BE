package com.algogyeyak.user.entity;

import com.algogyeyak.user.enums.AuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 한 User가 동시에 여러 소셜 제공자를 연동할 수 있게 하는 연결 테이블. "이 유저가 실제로 연동해둔
 * 모든 소셜 계정 목록"의 유일한 소스다 — {@code User}는 더 이상 provider/providerId를 갖지 않는다.
 *
 * LOCAL(이메일/비밀번호)은 여기 포함하지 않는다 — User 자체가 이미 email+passwordHash로 로컬 계정임을
 * 충분히 표현하고, 외부 provider_id 같은 게 애초에 없기 때문이다.
 */
@Entity
@Table(name = "user_social_accounts", uniqueConstraints = {
        // 같은 소셜 계정(provider+providerId)이 서로 다른 User 두 명에게 동시에 연결될 수 없다.
        @UniqueConstraint(name = "uk_social_provider_provider_id", columnNames = {"provider", "provider_id"}),
        // 한 User가 같은 provider를 두 개 이상 연동할 수는 없다(구글 계정 두 개를 동시에 연동하는 것 등).
        @UniqueConstraint(name = "uk_social_user_provider", columnNames = {"user_id", "provider"})
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    private UserSocialAccount(User user, AuthProvider provider, String providerId) {
        this.user = user;
        this.provider = provider;
        this.providerId = providerId;
    }

    public static UserSocialAccount of(User user, AuthProvider provider, String providerId) {
        return new UserSocialAccount(user, provider, providerId);
    }
}
