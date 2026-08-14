package com.algogyeyak.checklist.entity;

import com.algogyeyak.property.entity.Property;
import com.algogyeyak.user.entity.User;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 매물별 임장 체크리스트. 한 유저는 같은 매물에 대해 활성 체크리스트를 1개만 가진다
 * (user_id + property_id unique 제약).
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistStatus status;

    // @OrderBy 없이는 JPA가 DB에 items 조회 순서를 보장하지 않는다 - ChecklistResponse의
    // DISPLAY_ORDER_COMPARATOR는 안정 정렬(stable sort)이라, category/importance/displayOrder가
    // 전부 같은 두 문항(관리자가 실수로 같은 displayOrder를 중복 지정한 경우)의 상대 순서를 이
    // 컬렉션의 원래 fetch 순서로만 판가름한다 - @OrderBy가 없으면 그 순서가 요청마다 달라져
    // 화면에서 두 문항이 뒤바뀌어 보일 수 있었다. id 기준으로 고정해 최소한 항상 같은 순서가 나오게 한다.
    @OrderBy("id ASC")
    @OneToMany(mappedBy = "checklist", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChecklistItem> items = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    private Checklist(User user, Property property, int templateVersion) {
        this.user = user;
        this.property = property;
        this.templateVersion = templateVersion;
        this.status = ChecklistStatus.NOT_STARTED;
    }

    /**
     * 템플릿 목록을 스냅샷 복사해 체크리스트와 문항들을 함께 생성한다.
     * 이후 템플릿이 새 버전으로 바뀌어도 여기서 만들어진 문항 내용은 그대로 유지된다.
     */
    public static Checklist createFrom(User user, Property property, int templateVersion, List<ChecklistItemTemplate> templates) {
        Checklist checklist = Checklist.builder()
                .user(user)
                .property(property)
                .templateVersion(templateVersion)
                .build();

        for (ChecklistItemTemplate template : templates) {
            checklist.items.add(ChecklistItem.builder()
                    .checklist(checklist)
                    .template(template)
                    .category(template.getCategory())
                    .content(template.getContent())
                    .guideText(template.getGuideText())
                    .helperText(template.getHelperText())
                    .importance(template.getImportance())
                    .itemType(template.getItemType())
                    .code(template.getCode())
                    .displayOrder(template.getDisplayOrder())
                    .build());
        }

        return checklist;
    }

    /**
     * 문항 상태가 바뀔 때마다 호출해 전체 진행 상태를 다시 계산한다.
     * 체크된 항목이 하나도 없으면 NOT_STARTED, 필수(REQUIRED) 항목을 모두 체크했으면 COMPLETED,
     * 그 외에는 IN_PROGRESS.
     */
    public void refreshStatus() {
        boolean noneChecked = items.stream().noneMatch(ChecklistItem::isChecked);
        if (noneChecked) {
            this.status = ChecklistStatus.NOT_STARTED;
            return;
        }

        boolean allRequiredChecked = items.stream()
                .filter(item -> item.getImportance() == ChecklistImportance.REQUIRED)
                .allMatch(ChecklistItem::isChecked);
        this.status = allRequiredChecked ? ChecklistStatus.COMPLETED : ChecklistStatus.IN_PROGRESS;
    }

    /**
     * 등급/점수 없이 확인 완료도(개수)와 주의 항목 수로만 결과를 표현한다.
     * 아직 시작 전(NOT_STARTED)이면 누락 개수 대신 시작 안내 메시지를 담아 반환한다.
     */
    public ChecklistResult computeResult() {
        int totalCount = items.size();
        int checkedCount = (int) items.stream().filter(ChecklistItem::isChecked).count();
        int requiredMissingCount = (int) items.stream()
                .filter(item -> item.getImportance() == ChecklistImportance.REQUIRED && !item.isChecked())
                .count();
        int issueCount = (int) items.stream().filter(ChecklistItem::hasIssue).count();
        String message = status == ChecklistStatus.NOT_STARTED ? "체크리스트를 시작해보세요" : null;

        return new ChecklistResult(status, checkedCount, totalCount, requiredMissingCount, issueCount, message);
    }
}
