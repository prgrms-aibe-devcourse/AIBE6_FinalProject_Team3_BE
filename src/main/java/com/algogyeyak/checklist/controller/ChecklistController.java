package com.algogyeyak.checklist.controller;

import com.algogyeyak.auth.jwt.JwtUserPrincipal;
import com.algogyeyak.checklist.dto.ChecklistResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.service.ChecklistService;
import com.algogyeyak.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 체크리스트 관련 엔드포인트가 /properties/{propertyId}/checklists 뿐 아니라
 * /checklists/{id}/... 형태로도 추가될 예정이라, 클래스 레벨 공통 경로를 두지 않고
 * 각 매핑 애노테이션에 전체 경로를 직접 명시한다.
 */
@RestController
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService checklistService;

    /**
     * 임장 체크리스트 생성. 이미 있는 (user, property) 조합이면 새로 만들지 않고 기존 체크리스트를 그대로 반환한다(멱등).
     */
    @PostMapping("/properties/{propertyId}/checklists")
    public ApiResponse<ChecklistResponse> createChecklist(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long propertyId
    ) {
        Checklist checklist = checklistService.createOrGetChecklist(userDetails.userId(), propertyId);
        return ApiResponse.success(ChecklistResponse.from(checklist));
    }
}
