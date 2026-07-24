package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChecklistService {

    private final ChecklistRepository checklistRepository;
    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;
    private final UserRepository userRepository;

    /**
     * 유저-매물 조합에 이미 체크리스트가 있으면 그대로 반환하고(멱등), 없으면 현재 활성 템플릿으로
     * 새로 생성해 저장한다. 매물 존재/접근권한 검증은 Property 엔티티가 아직 없어 이번 스코프에서 제외한다 (TODO).
     */
    @Transactional
    public Checklist createOrGetChecklist(Long userId, Long propertyId) {
        return checklistRepository.findByUserIdAndPropertyId(userId, propertyId)
                .orElseGet(() -> createChecklist(userId, propertyId));
    }

    private Checklist createChecklist(Long userId, Long propertyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        List<ChecklistItemTemplate> templates = checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc();
        int templateVersion = templates.isEmpty() ? 0 : templates.get(0).getVersion();

        Checklist checklist = Checklist.createFrom(user, propertyId, templateVersion, templates);
        return checklistRepository.save(checklist);
    }
}
