package com.algogyeyak.riskanalysis.client;

import com.algogyeyak.riskanalysis.dto.PropertyRiskSummary;
import java.util.Map;

// property.service.PropertyService가 매물 목록 배지(위험신호 개수·전세가율)를 채우기 위해 쓰는 조회
// 전용 인터페이스. risk-analysis 쪽에 둔 이유: risk-analysis는 이미 Property 엔티티를 참조하고 있어
// property를 아는 게 새로운 의존이 아니지만, property는 지금 risk-analysis를 전혀 몰라야 하므로
// (risk-analysis-design.md 전수조사 결과 코드 품질 1번) 어댑터를 이쪽에 두는 편이 결합 방향이 일관됨.
public interface PropertyRiskSummaryProvider {

    // userId 소유 매물 전체를 대상으로 배치 조회한다(목록 조회 1회당 매물 수만큼 개별 쿼리하지 않기 위함).
    Map<Long, PropertyRiskSummary> getSummariesByUserId(Long userId);
}
