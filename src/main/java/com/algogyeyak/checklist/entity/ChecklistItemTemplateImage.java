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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문항 템플릿에 딸린 예시 이미지(참고용). ChecklistItem으로 스냅샷 복사되지 않고 항상 템플릿을
 * 통해 조회한다 - guideText/helperText와 달리 시점 고정이 필요한 콘텐츠가 아니라서, 관리자가
 * 이미지를 교체하면(예: AI 생성 이미지 → 실제 사진) 이미 만들어진 체크리스트에도 그대로 반영되는
 * 편이 낫다고 판단했다.
 */
@Entity
@Table(name = "checklist_item_template_images")
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
