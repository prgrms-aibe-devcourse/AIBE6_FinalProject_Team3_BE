package com.algogyeyak.marketdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실거래가 비교 정책값. 반경/면적오차/표본기준/조회기간은 M1 기간 국토부 API 실측 데이터로
 * 재검증 예정이라고 팀 논의에서 명시된 값들이라, 재배포 없이 튜닝할 수 있도록 하드코딩
 * 대신 설정값(application.yml market-data.comparison.*)으로 뺐다.
 *
 * @Component를 붙이지 않는다 - 붙이면 컴포넌트 스캔이 이 record를 일반 빈으로 취급해 생성자의
 * int/double 파라미터를 (프로퍼티 바인딩이 아니라) 빈 자동주입 대상으로 찾다가
 * NoSuchBeanDefinitionException("No qualifying bean of type 'int'")로 실패한다.
 * 대신 AlgogyeyakApplication에 붙인 @ConfigurationPropertiesScan이 이 클래스를 찾아
 * 프로퍼티 바인딩 전용 빈으로 등록해준다.
 */
@ConfigurationProperties(prefix = "market-data.comparison")
public record MarketComparisonProperties(
        int radiusNearMeters,
        int radiusFarMeters,
        int minSampleCount,
        double areaErrorRate,
        int lookbackMonths,
        // MarketComparisonService.compare(Property) 결과를 Redis에 얼마나 들고 있을지(propertyId
        // 기준). 매물 수정 시에는 PropertyService.update()가 캐시를 명시적으로 비우고 재계산하므로,
        // 이 TTL은 "수정 없이도 새 실거래 데이터가 반영되기까지 최대 얼마나 기다릴지"를 결정하는
        // 안전망 성격이다.
        int cacheTtlMinutes
) {
}
