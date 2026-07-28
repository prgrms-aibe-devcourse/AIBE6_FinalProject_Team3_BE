package com.algogyeyak.checklist.entity;

import com.algogyeyak.property.entity.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;

/**
 * 체크리스트 문항 템플릿. 실제 체크리스트(Checklist/ChecklistItem)는 여기서 내용을 스냅샷으로
 * 복사해서 만들어지므로, 템플릿을 새 버전으로 바꿔도 이미 생성된 체크리스트에는 영향이 없다.
 *
 * 매물유형·거래유형별 분기는 아직 두지 않는다 — 지금은 버전 하나 = 전체 매물 공통.
 * 실제로 타입별 문항이 달라져야 하면 그때 필터 컬럼을 추가한다.
 */
@Entity
@Table(name = "checklist_item_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItemTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ChecklistItemCode code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistCategory category;

    @Column(nullable = false, length = 200)
    private String content;

    @Column(name = "guide_text")
    private String guideText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistImportance importance;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private ChecklistItemType itemType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    // 이 문항을 보여줄 매물유형을 콤마로 구분해 담는다(예: "OFFICETEL,MULTI_FAMILY"). null이면 전체 매물유형에 적용.
    // 매물유형별로 템플릿 전체를 나누지 않고, 일부 문항만 얇게 필터링하기 위한 용도라 별도 컬렉션 테이블 없이 문자열로 둔다.
    @Column(name = "applicable_property_types")
    private String applicablePropertyTypes;

    @Builder
    private ChecklistItemTemplate(
            int version,
            ChecklistItemCode code,
            ChecklistCategory category,
            String content,
            String guideText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            int displayOrder,
            boolean active,
            String applicablePropertyTypes
    ) {
        this.version = version;
        this.code = code;
        this.category = category;
        this.content = content;
        this.guideText = guideText;
        this.importance = importance;
        this.itemType = itemType;
        this.displayOrder = displayOrder;
        this.active = active;
        this.applicablePropertyTypes = applicablePropertyTypes;
    }

    public boolean isApplicableTo(PropertyType propertyType) {
        if (applicablePropertyTypes == null) {
            return true;
        }
        return Arrays.asList(applicablePropertyTypes.split(",")).contains(propertyType.name());
    }
}
