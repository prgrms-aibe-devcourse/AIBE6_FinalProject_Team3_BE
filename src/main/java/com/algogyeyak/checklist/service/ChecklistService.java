package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistResult;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyStatus;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistService {

    private final ChecklistRepository checklistRepository;
    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;
    private final UserRepository userRepository;
    private final PropertyRepository propertyRepository;

    /**
     * 유저-매물 조합에 이미 체크리스트가 있으면 그대로 반환하고(멱등), 없으면 현재 활성 템플릿으로
     * 새로 생성해 저장한다.
     */
    @Transactional
    public Checklist createOrGetChecklist(Long userId, Long propertyId) {
        return checklistRepository.findByUserIdAndPropertyId(userId, propertyId)
                .orElseGet(() -> createChecklist(userId, propertyId));
    }

    private Checklist createChecklist(Long userId, Long propertyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        List<ChecklistItemTemplate> activeTemplates = checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc();
        int templateVersion = activeTemplates.isEmpty() ? 0 : activeTemplates.get(0).getVersion();
        List<ChecklistItemTemplate> applicableTemplates = activeTemplates.stream()
                .filter(template -> template.isApplicableTo(property.getPropertyType()))
                .toList();

        Checklist checklist = Checklist.createFrom(user, property, templateVersion, applicableTemplates);
        return checklistRepository.save(checklist);
    }

    /**
     * 체크리스트 항목 하나를 갱신한다. checked/value/userNote 중 요청에 채워진 필드에 따라
     * 알맞은 엔티티 메서드로 위임하고, 갱신 후 체크리스트 전체 진행 상태를 재계산한다.
     */
    @Transactional
    public ChecklistItem updateChecklistItem(Long userId, Long checklistId, Long itemId, ChecklistItemUpdateRequest request) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "체크리스트를 찾을 수 없습니다."));

        if (!checklist.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 체크리스트만 수정할 수 있습니다.");
        }

        if (checklist.getProperty().isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "체크리스트를 찾을 수 없습니다.");
        }

        ChecklistItem item = checklist.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "체크리스트 항목을 찾을 수 없습니다."));

        if (request.checked() != null) {
            item.check(request.checked());
        } else if (request.value() != null) {
            item.answer(request.value());
        } else if (request.userNote() != null) {
            item.markInsufficient(request.userNote());
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "변경할 값을 하나 이상 보내야 합니다.");
        }

        checklist.refreshStatus();

        return item;
    }

    /**
     * 매물의 체크리스트를 문항까지 포함해 조회한다. 없으면 생성하지 않고 CHECKLIST_NOT_FOUND를 던진다
     * (생성은 createOrGetChecklist가 담당). "생성" 흐름과 동일하게 매물을 먼저 조회(존재+삭제+소유권 확인)한
     * 뒤 체크리스트를 찾아, "매물 없음(PROPERTY_NOT_FOUND)"과 "체크리스트 없음(CHECKLIST_NOT_FOUND)"을
     * 명확히 구분한다.
     */
    public Checklist getChecklist(Long userId, Long propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }

        return checklistRepository.findByPropertyId(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHECKLIST_NOT_FOUND));
    }

    /**
     * 체크리스트 결과(확인 완료도, 필수 확인 누락 수, 주의 항목 수)를 조회한다.
     * 등급/점수는 없고, 아직 시작 전이면 시작 안내 메시지가 담긴 결과를 그대로 반환한다.
     */
    public ChecklistResult getChecklistResult(Long userId, Long checklistId) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "체크리스트를 찾을 수 없습니다."));

        if (!checklist.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 체크리스트만 조회할 수 있습니다.");
        }

        if (checklist.getProperty().isDeleted()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "체크리스트를 찾을 수 없습니다.");
        }

        return checklist.computeResult();
    }

    /**
     * 로그인 유저의 매물 전체 + 매물별 체크리스트 현황을 반환한다. 체크리스트를 아직 시작 안 한
     * 매물도 포함되며(checklistId=null, status=NOT_STARTED), 삭제된 매물은 제외한다.
     */
    public List<ChecklistOverviewResponse> listMyChecklists(Long userId) {
        List<Property> properties = propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, PropertyStatus.ACTIVE);
        Map<Long, Checklist> checklistsByPropertyId = checklistRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(checklist -> checklist.getProperty().getId(), checklist -> checklist));

        return properties.stream()
                .map(property -> ChecklistOverviewResponse.from(property, checklistsByPropertyId.get(property.getId())))
                .toList();
    }
}
