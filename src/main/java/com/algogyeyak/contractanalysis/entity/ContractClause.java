package com.algogyeyak.contractanalysis.entity;

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
 * ContractRequest에 속한 조항별 분석 결과. 원문(originalText)은 개인정보/계약 원문을
 * DB에 남기지 않는다는 정책상 저장하지 않는다.
 */
@Entity
@Table(name = "contract_clauses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractClause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_request_id", nullable = false)
    private ContractRequest contractRequest;

    @Column(nullable = false)
    private Boolean riskFlag;

    @Column(nullable = false, length = 2000)
    private String explanation;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(nullable = false, length = 2000)
    private String suggestedText;

    @Builder
    private ContractClause(Boolean riskFlag, String explanation, String question, String suggestedText) {
        this.riskFlag = riskFlag;
        this.explanation = explanation;
        this.question = question;
        this.suggestedText = suggestedText;
    }

    void assignContractRequest(ContractRequest contractRequest) {
        this.contractRequest = contractRequest;
    }
}
