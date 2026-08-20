package com.algogyeyak.checklist.entity;

import com.algogyeyak.property.entity.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    // guideText가 짧은 실무 안내라면, helperText는 부동산 지식이 없는 사용자도 이해할 수 있게 풀어쓴 설명이다.
    @Column(name = "helper_text", columnDefinition = "TEXT")
    private String helperText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChecklistImportance importance;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private ChecklistItemType itemType;

    // MULTIPLE_CHOICE 타입 문항의 선택지를 콤마로 구분해 담는다(예: "가스보일러,기름보일러,전기보일러,지역난방").
    // ChecklistItem.options와 동일한 형식 - 체크리스트 생성 시 그대로 스냅샷 복사된다.
    private String options;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    // 이 문항을 보여줄 매물유형을 콤마로 구분해 담는다(예: "OFFICETEL,MULTI_FAMILY"). null이면 전체 매물유형에 적용.
    // 매물유형별로 템플릿 전체를 나누지 않고, 일부 문항만 얇게 필터링하기 위한 용도라 별도 컬렉션 테이블 없이 문자열로 둔다.
    @Column(name = "applicable_property_types")
    private String applicablePropertyTypes;

    // 예시 이미지(참고용). ChecklistItem으로 스냅샷 복사되지 않고 항상 이 템플릿을 통해 조회한다 -
    // 자세한 이유는 ChecklistItemTemplateImage 클래스 주석 참고.
    @OrderBy("displayOrder ASC")
    @OneToMany(mappedBy = "template")
    private List<ChecklistItemTemplateImage> images = new ArrayList<>();

    // ChecklistTemplateSeeder가 심은 행인지 구분하는 값(markAsSeeded() 참고) - content와 같은
    // 값을 담지만 일부러 별도 컬럼으로 둔다. 관리자 페이지에서 만드는 일반 문항(AdminChecklistTemplateService)은
    // content에 어떤 값이 와도 이 컬럼을 채우지 않으므로(null), content 자체에 전역 유니크 제약을
    // 거는 것과 달리 관리자가 우연히 시드 문항과 같은 문구를 입력해도 걸리지 않는다. 오직 이 컬럼에만
    // 유니크 제약을 걸어, 앱 인스턴스 두 개가 동시에 기동하며 같은 시드 문항을 각자 "아직 없다"고
    // 판단해 둘 다 저장을 시도해도 DB가 하나만 통과시키고 나머지는 제약 위반으로 걸러낸다
    // (ChecklistTemplateSeeder 클래스 주석 참고 - AdminAccountSeeder와 동일한 패턴).
    @Column(name = "seed_key", unique = true, length = 200)
    private String seedKey;

    @Builder
    private ChecklistItemTemplate(
            int version,
            ChecklistItemCode code,
            ChecklistCategory category,
            String content,
            String guideText,
            String helperText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            String options,
            int displayOrder,
            boolean active,
            String applicablePropertyTypes
    ) {
        this.version = version;
        this.code = code;
        this.category = category;
        this.content = content;
        this.guideText = guideText;
        this.helperText = helperText;
        this.importance = importance;
        this.itemType = itemType;
        this.options = options;
        this.displayOrder = displayOrder;
        this.active = active;
        this.applicablePropertyTypes = applicablePropertyTypes;
    }

    /**
     * ChecklistTemplateSeeder 전용. 시더가 이 행을 실제로 저장(신규 삽입 또는 재동기화)하기 직전에
     * 호출해 seedKey를 content와 동일하게 채운다 - 시드 데이터(ChecklistTemplateSeedData)의 수십 개
     * builder 호출 지점을 전부 고칠 필요 없이, 시더가 저장 직전에 이 메서드 하나만 호출하면 된다.
     * update()(관리자 페이지 수정)는 이 값을 건드리지 않는다 - 관리자가 시드 문항의 문구를 고쳐도
     * "원래 시더가 심은 행"이라는 표식 자체는 남아 있어야, 다른 인스턴스가 같은 시드 문항을 다시
     * 심으려는 시도를 유니크 제약으로 계속 막을 수 있다.
     */
    public void markAsSeeded() {
        this.seedKey = this.content;
    }

    public boolean isApplicableTo(PropertyType propertyType) {
        if (applicablePropertyTypes == null) {
            return true;
        }
        // 관리자 페이지에서 자유 입력으로 들어올 수 있어(예: "OFFICETEL, MULTI_FAMILY"), 토큰마다
        // 앞뒤 공백을 제거한 뒤 비교해야 공백 때문에 매칭이 조용히 실패하는 걸 막을 수 있다.
        return Arrays.stream(applicablePropertyTypes.split(","))
                .map(String::trim)
                .anyMatch(token -> token.equals(propertyType.name()));
    }

    /**
     * 관리자 페이지에서 문항 내용을 수정한다. version은 여기서 바꾸지 않는다 - 단순 문구/순서 수정과
     * "새 템플릿 버전을 낸다"는 서로 다른 작업이고, 이미 생성된 체크리스트는 스냅샷이라 어차피 영향받지 않는다.
     */
    public void update(
            ChecklistCategory category,
            String content,
            String guideText,
            String helperText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            String options,
            ChecklistItemCode code,
            int displayOrder,
            String applicablePropertyTypes,
            boolean active
    ) {
        this.category = category;
        this.content = content;
        this.guideText = guideText;
        this.helperText = helperText;
        this.importance = importance;
        this.itemType = itemType;
        this.options = options;
        this.code = code;
        this.displayOrder = displayOrder;
        this.applicablePropertyTypes = applicablePropertyTypes;
        this.active = active;
    }

    /**
     * 시더가 이미 시딩된 DB(운영 등)를 새 시드 버전으로 재동기화할 때 쓴다 - update()와 달리 version도
     * 함께 갱신한다(단순 문구 수정이 아니라 실제로 새 시드 버전을 반영하는 작업이라서). active는
     * 건드리지 않는다 - 관리자가 수동으로 비활성화했을 수 있는데 시더 재동기화가 그걸 되돌리면 안 된다.
     */
    public void resyncFromSeed(
            ChecklistCategory category,
            String content,
            String guideText,
            String helperText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            String options,
            ChecklistItemCode code,
            int displayOrder,
            String applicablePropertyTypes,
            int version
    ) {
        this.category = category;
        this.content = content;
        this.guideText = guideText;
        this.helperText = helperText;
        this.importance = importance;
        this.itemType = itemType;
        this.options = options;
        this.code = code;
        this.displayOrder = displayOrder;
        this.applicablePropertyTypes = applicablePropertyTypes;
        this.version = version;
    }

    /**
     * 비활성 → 활성으로 재활성화되는 경우에만 호출한다. 비활성 상태 동안에는 버전을 건드리지 않으므로
     * (update() javadoc 참고), 과거에 다른 버전으로 비활성화됐던 문항을 그대로 재활성화하면 활성
     * 문항 집합의 버전이 뒤섞인다 - 재활성화 시점의 활성 집합 기준 버전으로 다시 맞춰준다.
     */
    public void realignVersionOnReactivation(int version) {
        this.version = version;
    }
}
