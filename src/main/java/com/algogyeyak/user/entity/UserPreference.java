package com.algogyeyak.user.entity;

import com.algogyeyak.user.enums.CurrentStage;
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

    @Enumerated(EnumType.STRING)
    private CurrentStage currentStage;

    @Builder
    private UserPreference(User user, String interestRegion, TransactionType transactionType, CurrentStage currentStage) {
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

    public void updateCurrentStage(CurrentStage currentStage) {
        this.currentStage = currentStage;
    }
}
