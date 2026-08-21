package com.algogyeyak.contractanalysis.entity;

import com.algogyeyak.contractanalysis.dto.ContractAnalysisClause;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 계약 분석(ContractAnalysisAnalyzeService.analyze()) 성공 결과를 저장하는 이력 레코드.
 * 분석 자체가 성공한 뒤에만 생성되는 불변 기록이라 수정 메서드/updatedAt은 두지 않는다.
 */
@Entity
@Table(name = "contract_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ContractRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "property_id")
    private Long propertyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InputType inputType;

    @Column(length = 1000)
    private String summary;

    @Column(nullable = false)
    private int clauseCount;

    @Column(nullable = false)
    private int riskCount;

    @Column(nullable = false, length = 500)
    private String disclaimer;

    @Column(nullable = false, length = 500)
    private String aiGeneratedNotice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContractRequestStatus status;

    @OrderBy("id ASC")
    @OneToMany(mappedBy = "contractRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ContractClause> clauses = new ArrayList<>();

    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private ContractRequest(
            Long userId,
            Long propertyId,
            InputType inputType,
            String summary,
            String disclaimer,
            String aiGeneratedNotice
    ) {
        this.userId = userId;
        this.propertyId = propertyId;
        this.inputType = inputType;
        this.summary = summary;
        this.disclaimer = disclaimer;
        this.aiGeneratedNotice = aiGeneratedNotice;
        this.status = ContractRequestStatus.COMPLETED;
    }

    /**
     * 분석 응답의 조항 목록으로부터 ContractRequest와 하위 ContractClause들을 함께 생성한다.
     * clauseCount/riskCount는 저장 시점의 clauses 크기·riskFlag 기준으로 여기서 직접 계산한다.
     */
    public static ContractRequest create(
            Long userId,
            Long propertyId,
            InputType inputType,
            String summary,
            String disclaimer,
            String aiGeneratedNotice,
            List<ContractAnalysisClause> analyzedClauses
    ) {
        ContractRequest contractRequest = ContractRequest.builder()
                .userId(userId)
                .propertyId(propertyId)
                .inputType(inputType)
                .summary(summary)
                .disclaimer(disclaimer)
                .aiGeneratedNotice(aiGeneratedNotice)
                .build();

        for (ContractAnalysisClause analyzedClause : analyzedClauses) {
            ContractClause clause = ContractClause.builder()
                    .title(analyzedClause.title())
                    .riskFlag(analyzedClause.riskFlag())
                    .explanation(analyzedClause.explanation())
                    .question(analyzedClause.question())
                    .suggestedText(analyzedClause.suggestedText())
                    .build();
            clause.assignContractRequest(contractRequest);
            contractRequest.clauses.add(clause);
        }

        contractRequest.clauseCount = analyzedClauses.size();
        contractRequest.riskCount = (int) analyzedClauses.stream()
                .filter(clause -> Boolean.TRUE.equals(clause.riskFlag()))
                .count();

        return contractRequest;
    }
}
