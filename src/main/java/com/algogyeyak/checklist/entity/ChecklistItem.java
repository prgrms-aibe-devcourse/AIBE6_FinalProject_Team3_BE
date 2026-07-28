package com.algogyeyak.checklist.entity;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 체크리스트 문항 하나의 상태. 생성 시점에 ChecklistItemTemplate의 내용을 스냅샷으로 복사해두므로,
 * 이후 템플릿이 새 버전으로 바뀌어도 이미 만들어진 체크리스트의 문항 내용은 그대로 유지된다.
 *
 * checked/issueFound/value는 빌더로 직접 설정하지 않는다 — check()/answer()를 통해서만 바뀌어야
 * itemType별 검증과 자동 issueFound 규칙이 항상 같이 적용된다.
 */
@Entity
@Table(name = "checklist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checklist_id", nullable = false)
    private Checklist checklist;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ChecklistItemCode code;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean checked;

    @Column(name = "issue_found", nullable = false)
    private boolean issueFound;

    // "value"는 H2를 포함한 여러 DB의 예약어라 컬럼명을 그대로 쓰면 스키마 생성이 실패한다.
    @Column(name = "item_value")
    private String value;

    @Column(name = "user_note")
    private String userNote;

    @Builder
    private ChecklistItem(
            Checklist checklist,
            ChecklistCategory category,
            String content,
            String guideText,
            ChecklistImportance importance,
            ChecklistItemType itemType,
            ChecklistItemCode code,
            int displayOrder
    ) {
        this.checklist = checklist;
        this.category = category;
        this.content = content;
        this.guideText = guideText;
        this.importance = importance;
        this.itemType = itemType;
        this.code = code;
        this.displayOrder = displayOrder;
        this.checked = false;
        this.issueFound = false;
    }

    /**
     * CHECK 타입 문항의 확인 여부를 바꾼다. 완료(true)든 미확인(false)이든, 이전에 남아있던
     * 사용자 메모(userNote)는 의미가 없어지므로 함께 지운다.
     */
    public void check(boolean checked) {
        if (itemType != ChecklistItemType.CHECK) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이 항목은 확인 방식이 아닙니다.");
        }
        this.checked = checked;
        this.userNote = null;
    }

    /**
     * CHECK 타입 문항을 "미흡"으로 표시하고 메모를 남긴다. 메모는 빈 문자열도 허용한다
     * (미흡 표시만 하고 내용은 나중에 채우는 경우).
     */
    public void markInsufficient(String note) {
        if (itemType != ChecklistItemType.CHECK) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "이 항목은 메모를 남길 수 있는 방식이 아닙니다.");
        }
        if (note == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "메모는 null일 수 없습니다 (빈 문자열은 허용).");
        }
        this.checked = true;
        this.userNote = note;
    }

    /**
     * 값 입력이 필요한 문항(YES_NO/DATE/DOCUMENT_REQUEST)의 응답을 저장한다.
     * itemType에 맞지 않는 값이면 BusinessException(BAD_REQUEST)을 던진다.
     */
    public void answer(String rawValue) {
        switch (itemType) {
            case YES_NO -> answerYesNo(rawValue);
            case DATE -> answerDate(rawValue);
            case DOCUMENT_REQUEST -> answerDocumentRequest(rawValue);
            case CHECK -> throw new BusinessException(ErrorCode.BAD_REQUEST, "이 항목은 값 입력 방식이 아닙니다.");
        }
    }

    private void answerDocumentRequest(String rawValue) {
        if (!"PROVIDED".equals(rawValue) && !"NOT_PROVIDED".equals(rawValue)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "PROVIDED 또는 NOT_PROVIDED 값만 입력할 수 있습니다.");
        }

        // 임대인에게 요청한 서류(확정일자 부여현황/전입세대열람원)를 제공받지 못했으면 자동으로 주의 항목이 된다.
        markAnswered(rawValue, "NOT_PROVIDED".equals(rawValue));
    }

    private void answerDate(String rawValue) {
        try {
            LocalDate.parse(rawValue); // ISO-8601 (yyyy-MM-dd) 형식만 허용
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "날짜 형식(yyyy-MM-dd)이 올바르지 않습니다.");
        }

        markAnswered(rawValue, false);
    }

    private void answerYesNo(String rawValue) {
        if (!"Y".equals(rawValue) && !"N".equals(rawValue)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Y 또는 N 값만 입력할 수 있습니다.");
        }

        // 신탁등기 있음(Y), 소유자-임대인 명의 불일치(N)는 자동으로 주의 항목(issueFound)으로 반영한다.
        boolean triggersIssue = (code == ChecklistItemCode.TRUST_REGISTRATION && "Y".equals(rawValue))
                || (code == ChecklistItemCode.OWNERSHIP_MATCH && "N".equals(rawValue));
        markAnswered(rawValue, triggersIssue);
    }

    private void markAnswered(String rawValue, boolean issueFound) {
        this.value = rawValue;
        this.checked = true;
        this.issueFound = issueFound;
    }

    /**
     * 자동 issueFound 규칙뿐 아니라, 사용자가 "미흡"으로 메모를 남긴 경우도 주의 항목으로 취급한다.
     */
    public boolean hasIssue() {
        return issueFound || userNote != null;
    }
}
