package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 페이지에서 체크리스트 문항 템플릿을 관리한다. 템플릿은 스냅샷 방식(ChecklistItemTemplate
 * 클래스 javadoc 참고)이라, 여기서의 수정/삭제는 이미 생성된 유저 체크리스트에는 영향을 주지 않고
 * 앞으로 새로 생성되는 체크리스트에만 반영된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminChecklistTemplateService {

    // 각 code가 어떤 itemType과 짝을 이뤄야 자동 issueFound 판정이 실제로 동작하는지(ChecklistItem의
    // answerYesNo/answerDocumentRequest 참고) - code만 붙이고 itemType이 안 맞으면(예: TRUST_REGISTRATION을
    // CHECK 문항에 붙임) 자동 판정 자체가 조용히 무시된다.
    private static final Map<ChecklistItemCode, ChecklistItemType> REQUIRED_ITEM_TYPE_BY_CODE = Map.of(
            ChecklistItemCode.TRUST_REGISTRATION, ChecklistItemType.YES_NO,
            ChecklistItemCode.OWNERSHIP_MATCH, ChecklistItemType.YES_NO,
            ChecklistItemCode.OWNERSHIP_ACQUISITION_DATE, ChecklistItemType.DATE,
            ChecklistItemCode.TAX_DELINQUENCY_NOTICE, ChecklistItemType.CHECK,
            ChecklistItemCode.DATE_OF_CONFIRMATION_REQUEST, ChecklistItemType.DOCUMENT_REQUEST,
            ChecklistItemCode.RESIDENT_REGISTRATION_REQUEST, ChecklistItemType.DOCUMENT_REQUEST
    );

    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;

    public List<AdminChecklistItemTemplateResponse> list() {
        return checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc().stream()
                .map(AdminChecklistItemTemplateResponse::from)
                .toList();
    }

    /**
     * version은 요청으로 받지 않고, 현재 존재하는 문항들 중 가장 높은 버전을 그대로 물려받는다
     * (문항이 하나도 없으면 1로 시작).
     */
    @Transactional
    public AdminChecklistItemTemplateResponse create(AdminChecklistItemTemplateCreateRequest request) {
        // 새로 만드는 문항은 항상 active=true라, 다른 활성 문항과의 code 중복도 그 기준으로 검사한다.
        validateCode(request.code(), request.itemType(), true, null);

        int version = checklistItemTemplateRepository.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(ChecklistItemTemplate::getVersion)
                .max()
                .orElse(1);

        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(version)
                .category(request.category())
                .content(request.content())
                .guideText(request.guideText())
                .helperText(request.helperText())
                .importance(request.importance())
                .itemType(request.itemType())
                .code(request.code())
                .displayOrder(request.displayOrder())
                .active(true)
                .applicablePropertyTypes(request.applicablePropertyTypes())
                .build();

        return AdminChecklistItemTemplateResponse.from(checklistItemTemplateRepository.save(template));
    }

    @Transactional
    public AdminChecklistItemTemplateResponse update(Long templateId, AdminChecklistItemTemplateUpdateRequest request) {
        ChecklistItemTemplate template = findTemplate(templateId);
        validateCode(request.code(), request.itemType(), request.active(), templateId);

        template.update(
                request.category(),
                request.content(),
                request.guideText(),
                request.helperText(),
                request.importance(),
                request.itemType(),
                request.code(),
                request.displayOrder(),
                request.applicablePropertyTypes(),
                request.active()
        );
        return AdminChecklistItemTemplateResponse.from(template);
    }

    /**
     * code는 자동 issueFound 판정이 code당 정확히 1개의 활성 문항을 전제하므로(ChecklistTemplateSeedDataTest
     * 참고), itemType과의 짝이 맞는지 + 다른 활성 문항과 중복되지 않는지 둘 다 검증한다.
     *
     * 알려진 한계(조회 후 저장 방식이라 원자적이지 않음): 관리자 두 명이 동시에 같은 code로
     * 생성/활성화하면 둘 다 이 중복 검사를 통과할 수 있다. 관리자 전용 화면이고 동시 편집 빈도가
     * 매우 낮아 감수하기로 함(2026-08-04) - 실제로 강한 불변식이 필요해지면 DB partial unique
     * index(활성 행에만 적용)나 비관적 락이 필요하지만, 지금은 그 정도의 스키마/락 복잡도를 들일
     * 만큼의 위험이 아니라고 판단했다.
     */
    private void validateCode(ChecklistItemCode code, ChecklistItemType itemType, boolean active, Long excludeTemplateId) {
        if (code == null) {
            return;
        }

        ChecklistItemType requiredItemType = REQUIRED_ITEM_TYPE_BY_CODE.get(code);
        if (requiredItemType != itemType) {
            throw new BusinessException(
                    ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_CODE,
                    "%s 코드는 %s 응답 방식에서만 사용할 수 있습니다.".formatted(code, requiredItemType)
            );
        }

        if (!active) {
            return;
        }

        boolean duplicate = checklistItemTemplateRepository.findByCodeAndActiveTrue(code).stream()
                .anyMatch(existing -> !existing.getId().equals(excludeTemplateId));
        if (duplicate) {
            throw new BusinessException(
                    ErrorCode.ADMIN_CHECKLIST_TEMPLATE_DUPLICATE_CODE,
                    "이미 다른 활성 문항이 %s 코드를 사용하고 있습니다.".formatted(code)
            );
        }
    }

    /**
     * 마지막 남은 문항까지 물리 삭제하면, 앱 재시작 시 ChecklistTemplateSeeder가 "테이블이 비어있다"고
     * 판단해 기본 시드 데이터를 다시 채워 넣는다 - 관리자가 의도적으로 전부 정리했다고 생각한 상태가
     * 재시작 한 번에 되돌아가 버리는 걸 막기 위해, 항상 최소 1개는 남기도록 강제한다. 노출을 끄고 싶으면
     * active=false(수정 API)를 쓰면 된다.
     *
     * 이 count() 검사도 validateCode()와 같은 이유로 원자적이지 않다 - 딱 2개 남은 상태에서 두 관리자가
     * 동시에 삭제를 시도하면 둘 다 통과해 0개가 될 수 있다. 같은 이유(관리자 전용, 저빈도)로 감수한다.
     */
    @Transactional
    public void delete(Long templateId) {
        ChecklistItemTemplate template = findTemplate(templateId);
        if (checklistItemTemplateRepository.count() <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM);
        }
        checklistItemTemplateRepository.delete(template);
    }

    private ChecklistItemTemplate findTemplate(Long templateId) {
        return checklistItemTemplateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }
}
