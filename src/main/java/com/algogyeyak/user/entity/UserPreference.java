package com.algogyeyak.user.entity;

import com.algogyeyak.user.enums.CurrentStage;
import com.algogyeyak.user.enums.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

// User와 동일한 이유(User.java 참고) - updateMyProfile()에서 필드별로 골라 갱신하는 구조라, 두
// 필드를 각각 다른 탭/요청에서 동시에 바꾸면 정적 UPDATE는 나중에 커밋하는 쪽이 먼저 커밋된
// 필드를 조용히 되돌릴 수 있다.
@Entity
@Table(name = "user_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DynamicUpdate
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
