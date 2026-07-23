package com.algogyeyak.checklist.entity;

import com.algogyeyak.user.entity.User;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 매물별 임장 체크리스트. 한 유저는 같은 매물에 대해 활성 체크리스트를 1개만 가진다
 * (user_id + property_id unique 제약).
 *
 * propertyId는 아직 Property 엔티티가 없어서 연관관계 없이 Long으로만 둔다.
 * Property 엔티티가 생기면 매물 존재/접근권한/삭제여부 검증 로직을 이어서 연결한다.
 */
@Entity
@Table(name = "checklists", uniqueConstraints = {
        @UniqueConstraint(name = "uk_checklist_user_property", columnNames = {"user_id", "property_id"})
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Checklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistStatus status;

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private Checklist(User user, Long propertyId, int templateVersion) {
        this.user = user;
        this.propertyId = propertyId;
        this.templateVersion = templateVersion;
        this.status = ChecklistStatus.NOT_STARTED;
    }
}
