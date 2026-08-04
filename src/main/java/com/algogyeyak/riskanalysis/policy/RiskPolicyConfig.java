package com.algogyeyak.riskanalysis.policy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "risk-policy")
@Getter
@Setter
public class RiskPolicyConfig {
    private String version;              // 예: "v1.0"
    private int priceAnomalyPercent;     // 10
    private int jeonseRatioWarnFrom;      // 100
    private int jeonseRatioWarnTo;        // 150
    private int jeonseRatioAlertOver;     // 150
    private boolean multiAccountDetectionEnabled; // 동일계정 다수등록 체크 활성화/비활성화
    private int sameAccountThresholdCount;        // 동일계정 다수등록 판단 개수 임계값 (예: 3)
    private int sameAccountWindowDays;             // 동일계정 다수등록 판단 기간(일) (예: 7)
    private int shortTermRelistingWindowDays;              // 짧은 주기 재등록 판단 기간(일) (예: 30)
    private int shortTermRelistingPriceTolerancePercent;   // 재등록 판단 시 가격 유사도 허용 오차(%) (예: 5)
    private int shortTermRelistingAreaTolerancePercent;    // 재등록 판단 시 면적 유사도 허용 오차(%) (예: 10)
}
