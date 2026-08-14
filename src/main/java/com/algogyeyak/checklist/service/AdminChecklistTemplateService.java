package com.algogyeyak.checklist.service;

import com.algogyeyak.admin.entity.AdminAuditAction;
import com.algogyeyak.admin.service.AdminAuditLogger;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateCreateRequest;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateResponse;
import com.algogyeyak.checklist.dto.AdminChecklistItemTemplateUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistItemCode;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.PropertyType;
import java.util.Arrays;
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
    private final AdminAuditLogger adminAuditLogger;

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
    public AdminChecklistItemTemplateResponse create(Long actorId, AdminChecklistItemTemplateCreateRequest request) {
        // 새로 만드는 문항은 항상 active=true라, 다른 활성 문항과의 code 중복도 그 기준으로 검사한다.
        validateCode(request.code(), request.itemType(), true, null);
        validateApplicablePropertyTypes(request.applicablePropertyTypes());

        // 비활성 문항 중에 활성 문항보다 높은 버전이 남아있을 수 있어(과거에 비활성화된 문항),
        // 전체가 아니라 "현재 활성 문항 집합" 기준으로만 최대 버전을 구한다 - 그래야 새 문항이
        // 지금 실제로 쓰이는 버전과 같은 값을 받는다.
        int version = currentActiveMaxVersion();

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

        ChecklistItemTemplate saved = checklistItemTemplateRepository.save(template);
        adminAuditLogger.log(actorId, AdminAuditAction.CREATE_CHECKLIST_TEMPLATE, saved.getId(),
                Map.of("content", saved.getContent(), "category", saved.getCategory(), "active", saved.isActive()));
        return AdminChecklistItemTemplateResponse.from(saved);
    }

    /**
     * 알려진 한계: 요청 DTO가 필드 일부만 담는 부분 patch가 아니라 항상 전체 스냅샷을 요구한다
     * (프론트가 수정 폼을 열 때 불러온 값을 그대로 다시 보냄). 관리자 두 명이 거의 동시에 같은
     * 문항을 열어 서로 다른 필드를 고치면, 나중에 도착한 요청이 먼저 도착한 요청의 변경을
     * 자기가 불러왔던(더 오래된) 값으로 조용히 덮어쓴다 - 낙관적 락(@Version) 없이는 막을 수
     * 없는 전형적인 lost-update다. AdminUserService.rejectIfLastActiveAdmin과 같은 이유(관리자
     * 전용 화면, 매우 낮은 동시 편집 빈도)로 지금은 감수하고, 실제로 문제가 되면 그때 @Version을
     * 도입한다.
     */
    @Transactional
    public AdminChecklistItemTemplateResponse update(
            Long actorId, Long templateId, AdminChecklistItemTemplateUpdateRequest request) {
        ChecklistItemTemplate template = findTemplate(templateId);
        validateCode(request.code(), request.itemType(), request.active(), templateId);
        validateNotDeactivatingLastActiveTemplate(template, request.active());
        validateApplicablePropertyTypes(request.applicablePropertyTypes());

        String previousContent = template.getContent();
        boolean previousActive = template.isActive();
        // 비활성 상태였던 문항이 다시 active=true가 되는 경우만 재정렬 대상이다 - update()는 비활성
        // 동안 version을 안 건드리므로(그 자체는 의도된 동작, create()의 javadoc 참고), 과거에 다른
        // 버전으로 비활성화됐던 문항을 그대로 재활성화하면 활성 집합의 버전이 뒤섞여
        // ChecklistService.createChecklist()가 그 이후 임의의 버전을 새 유저 체크리스트에 찍게 된다.
        // 재활성화 직전(이 문항 자신은 아직 비활성 상태)의 활성 집합 기준으로 버전을 다시 맞춘다.
        boolean reactivating = !previousActive && request.active();
        Integer versionOnReactivation = reactivating ? currentActiveMaxVersion() : null;

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
        if (versionOnReactivation != null) {
            template.realignVersionOnReactivation(versionOnReactivation);
        }
        adminAuditLogger.log(actorId, AdminAuditAction.UPDATE_CHECKLIST_TEMPLATE, templateId, Map.of(
                "beforeContent", previousContent, "afterContent", template.getContent(),
                "beforeActive", previousActive, "afterActive", template.isActive()));
        return AdminChecklistItemTemplateResponse.from(template);
    }

    private int currentActiveMaxVersion() {
        return checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .mapToInt(ChecklistItemTemplate::getVersion)
                .max()
                .orElse(1);
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

    // ChecklistItemTemplate.isApplicableTo()는 콤마로 구분된 토큰을 PropertyType.name()과 단순
    // 문자열 비교만 하므로, Swagger/직접 API 호출로 오타나 존재하지 않는 값이 들어와도 그 자체로는
    // 에러가 나지 않고 그 문항이 모든 매물유형에서 조용히 노출되지 않게 될 뿐이다(관리자 화면은
    // 체크박스라 정상 입력만 보내지만, API 레벨에는 그 보장이 없었다). 저장 시점에 막아 그
    // 조용한 실패를 방지한다.
    private void validateApplicablePropertyTypes(String applicablePropertyTypes) {
        if (applicablePropertyTypes == null || applicablePropertyTypes.isBlank()) {
            return;
        }
        List<String> tokens = Arrays.stream(applicablePropertyTypes.split(","))
                .map(String::trim)
                // trailing/중복 콤마("OFFICETEL,")가 만드는 빈 토큰은 오타가 아니라 구분자 사용
                // 습관의 문제일 뿐이라, 존재하지 않는 매물유형과 같은 취급(검증 실패)을 하면 안 된다.
                .filter(token -> !token.isBlank())
                .toList();
        // 필터링 후 남은 토큰이 하나도 없다는 것("," 하나만 있거나 " , "처럼 구분자뿐인 경우)은
        // null(=전체 매물유형에 적용)과 다르다 - isApplicableTo()는 이 non-null 빈 값을 "전체 적용"
        // 으로 봐주지 않고 빈 배열과 어떤 매물유형도 매칭시키지 못해 모든 매물유형에서 조용히
        // 숨겨버린다. 존재하지 않는 매물유형과 동일하게 취급해 저장 자체를 막는다 - 앞서 "빈 토큰은
        // 허용"으로만 고쳤다가 이 케이스를 새로 뚫어버렸던 회귀.
        boolean hasInvalidToken = tokens.isEmpty()
                || tokens.stream().anyMatch(token -> Arrays.stream(PropertyType.values())
                        .noneMatch(propertyType -> propertyType.name().equals(token)));
        if (hasInvalidToken) {
            throw new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_INVALID_PROPERTY_TYPE);
        }
    }

    /**
     * delete()가 마지막 문항 물리 삭제를 막는 것과 같은 이유로, "숨기려면 active=false(수정 API)를
     * 쓰라"는 안내 문구와 달리 그 active=false 자체가 마지막 활성 문항을 0개로 만드는 경로는 막혀
     * 있지 않았다 - 그러면 ChecklistService.createChecklist()가 그 이후 만드는 모든 유저 체크리스트가
     * 문항 0개로 조용히 생성된다. delete()와 동일한 원자성 한계(조회 후 저장 방식)를 그대로 감수한다.
     */
    private void validateNotDeactivatingLastActiveTemplate(ChecklistItemTemplate template, boolean nextActive) {
        if (nextActive || !template.isActive()) {
            return;
        }
        long remainingActiveCount = checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .filter(existing -> !existing.getId().equals(template.getId()))
                .count();
        if (remainingActiveCount == 0) {
            throw new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM);
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
    public void delete(Long actorId, Long templateId) {
        ChecklistItemTemplate template = findTemplate(templateId);
        if (checklistItemTemplateRepository.count() <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_LAST_ITEM);
        }
        // 위 count() 검사는 "테이블 전체가 비어버리는 것"만 막는다 - 비활성 문항이 하나 더 있는
        // 상태에서 마지막 활성 문항을 삭제하면 count()는 통과하지만 활성 문항이 0개가 되어,
        // update()의 validateNotDeactivatingLastActiveTemplate가 막으려던 것과 동일한 문제
        // (ChecklistService.createChecklist()가 문항 0개로 조용히 생성됨)가 다른 경로로 발생한다.
        validateNotDeactivatingLastActiveTemplate(template, false);
        // 삭제 후에는 다시 조회할 수 없으니, 감사 로그에 남길 내용을 삭제 전에 미리 캡처해둔다.
        Map<String, Object> deletedSummary = Map.of("content", template.getContent(), "category", template.getCategory());
        checklistItemTemplateRepository.delete(template);
        adminAuditLogger.log(actorId, AdminAuditAction.DELETE_CHECKLIST_TEMPLATE, templateId, deletedSummary);
    }

    private ChecklistItemTemplate findTemplate(Long templateId) {
        return checklistItemTemplateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_CHECKLIST_TEMPLATE_NOT_FOUND));
    }
}
