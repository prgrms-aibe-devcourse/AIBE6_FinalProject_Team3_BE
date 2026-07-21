package com.algogyeyak.user.entity;

import com.algogyeyak.user.enums.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    private String interestRegion;

    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    // currentStage 값의 종류(예: "자취처음"/"취업준비" 등)가 명세서에 구체적으로
    // 나열되어 있지 않아 우선 String으로 처리 — 확인 필요
    private String currentStage;

    @Builder
    private UserPreference(User user, String interestRegion, TransactionType transactionType, String currentStage) {
        this.user = user;
        this.interestRegion = interestRegion;
        this.transactionType = transactionType;
        this.currentStage = currentStage;
    }

    public void updateInterestRegion(String interestRegion) {
        this.interestRegion = interestRegion;
    }

    public void updateTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public void updateCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }
}
