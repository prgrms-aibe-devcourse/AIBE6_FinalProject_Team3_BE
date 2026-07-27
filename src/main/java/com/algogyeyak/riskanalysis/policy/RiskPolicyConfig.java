package com.algogyeyak.riskanalysis.policy;

public class RiskPolicyConfig {
    private String version;              // 예: "v1.0"
    private int priceOutlierMinPercent;   // 10
    private int priceOutlierMaxPercent;   // 20
    private int jeonseRatioWarnFrom;      // 100
    private int jeonseRatioWarnTo;        // 150
    private int jeonseRatioAlertOver;     // 150
    private boolean multiAccountDetectionEnabled; // 동일계정 다수등록 체크 활성화/비활성화
}
