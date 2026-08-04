package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import java.util.List;
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

    @Transactional
    public void delete(Long templateId) {
        checklistItemTemplateRepository.delete(findTemplate(templateId));
    }

    private ChecklistItemTemplate findTemplate(Long templateId) {
        return checklistItemTemplateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }
}
