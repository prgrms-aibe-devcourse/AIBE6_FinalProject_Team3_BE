package com.algogyeyak.contractanalysis.service;

import com.algogyeyak.contractanalysis.dto.ContractHistoryDetailResponse;
import com.algogyeyak.contractanalysis.dto.ContractHistoryResponse;
import com.algogyeyak.contractanalysis.entity.ContractRequest;
import com.algogyeyak.contractanalysis.repository.ContractRequestRepository;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.pagination.PageableUtils;
import com.algogyeyak.global.response.PageResponse;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.repository.PropertyRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContractAnalysisHistoryService {

    private final ContractRequestRepository contractRequestRepository;
    private final PropertyRepository propertyRepository;

    public ContractAnalysisHistoryService(
            ContractRequestRepository contractRequestRepository, PropertyRepository propertyRepository
    ) {
        this.contractRequestRepository = contractRequestRepository;
        this.propertyRepository = propertyRepository;
    }

    // 항상 최신순 고정이라(ChecklistService.listMyChecklists와 동일한 이유) 클라이언트가 보낸
    // 정렬은 무시하고 page/size만 반영한 새 Pageable로 리포지토리에 넘긴다.
    // clauses(riskFlag/explanation/question/suggestedText)는 목록이 무거워지지 않도록 여기 포함하지
    // 않는다 - 상세는 getMyContractHistoryDetail()에서 항목 단위로 따로 조회한다.
    public PageResponse<ContractHistoryResponse> listMyContractHistory(Long userId, Pageable pageable) {
        PageableUtils.validateMaxSize(pageable);
        Pageable fixedSortPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<ContractRequest> page = contractRequestRepository.findAllByUserId(userId, fixedSortPageable);

        // 페이지당 최대 매물 수만큼만 IN 조회 1번으로 묶어 가져온다(항목마다 매물을 따로 조회하는
        // N+1을 피하기 위함) - propertyId가 null인 이력(매물과 연결되지 않은 분석)도 있을 수 있어
        // null은 걸러낸다. findAllById는 이미 삭제(status=DELETED)된 매물도 행 자체는 남아있어 함께
        // 반환하므로, 삭제된 매물이어도 이력 화면에서는 매물명이 계속 표시된다.
        List<Long> propertyIds = page.getContent().stream()
                .map(ContractRequest::getPropertyId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> propertyTitleById = propertyRepository.findAllById(propertyIds).stream()
                .collect(Collectors.toMap(Property::getId, Property::getTitle));

        return PageResponse.from(
                page,
                contractRequest -> ContractHistoryResponse.from(
                        contractRequest, propertyTitleById.get(contractRequest.getPropertyId())
                )
        );
    }

    // clauses는 LAZY 연관관계라 트랜잭션(읽기 전용) 안에서 DTO로 다 매핑해야 컨트롤러로
    // 넘어간 뒤 LazyInitializationException 없이 접근할 수 있다.
    public ContractHistoryDetailResponse getMyContractHistoryDetail(Long userId, Long id) {
        ContractRequest contractRequest = contractRequestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_ANALYSIS_HISTORY_NOT_FOUND));

        if (!contractRequest.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONTRACT_ANALYSIS_FORBIDDEN, "본인의 계약 분석 이력만 조회할 수 있습니다.");
        }

        String propertyTitle = contractRequest.getPropertyId() != null
                ? propertyRepository.findById(contractRequest.getPropertyId()).map(Property::getTitle).orElse(null)
                : null;

        return ContractHistoryDetailResponse.from(contractRequest, propertyTitle);
    }
}
