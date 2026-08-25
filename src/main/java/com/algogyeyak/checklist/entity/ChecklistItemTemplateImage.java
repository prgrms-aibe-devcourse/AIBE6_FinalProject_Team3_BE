package com.algogyeyak.checklist.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 문항 템플릿에 딸린 예시 이미지(참고용). ChecklistItem으로 스냅샷 복사되지 않고 항상 템플릿을
 * 통해 조회한다 - guideText/helperText와 달리 시점 고정이 필요한 콘텐츠가 아니라서, 관리자가
 * 이미지를 교체하면(예: AI 생성 이미지 → 실제 사진) 이미 만들어진 체크리스트에도 그대로 반영되는
 * 편이 낫다고 판단했다.
 *
 * <p>(template_id, display_order) 유니크 제약: ChecklistTemplateSeeder가 두 인스턴스 동시 기동 시
 * 같은 문항에 같은 이미지 세트를 중복 삽입하는 걸 막는 가드다 - 시더는 "이 문항에 이미지가 아직
 * 없다"를 findAllWithImages() 조회 결과로 판단하는데, 두 인스턴스가 동시에 같은 결과를 읽으면 둘
 * 다 같은 문항에 같은 순서(1,2,3...)로 이미지를 붙이려 시도한다. 이 제약이 있으면 먼저 커밋한
 * 쪽만 성공하고 나머지는 DataIntegrityViolationException으로 걸러진다(ChecklistTemplateSeeder
 * 참고). 부수 효과로 AdminChecklistTemplateService.addImage()의 이미 알려진 "관리자 두 명 동시
 * 추가 시 표시순서 충돌" 레이스도 (조용한 순서 뒤섞임 대신) 즉시 실패로 바뀐다 - 그 메서드 문서에
 * 이미 "실제로 문제가 되면 이 제약을 추가한다"고 예고돼 있던 대응이다.
 */
@Entity
@Table(
        name = "checklist_item_template_images",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_checklist_item_template_image_template_display_order",
                columnNames = {"template_id", "display_order"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItemTemplateImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ChecklistItemTemplate template;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Builder
    private ChecklistItemTemplateImage(ChecklistItemTemplate template, String imageUrl, int displayOrder) {
        this.template = template;
        this.imageUrl = imageUrl;
        this.displayOrder = displayOrder;
    }
}
