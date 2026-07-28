package com.algogyeyak.property.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    /**
     * 공통 비기능요구사항 - "외부 API 호출에는 응답 시간 제한을 적용합니다."
     *
     * connect 3s/read 5s로 시작했으나 두 가지 이유로 늘어났다:
     * 1. 매물 상세조회 한 번에 국토부 API를 최대 6회(6개월치) 순차 호출하는 market-data 쪽
     *    특성상 요청이 겹치면 정상 응답도 5초를 넘겨 잘려나가는 사례가 있었음 (connect 5s/read 10s)
     * 2. Gemini API의 계약 조항 분석 응답이 9~10초 이상 소요되는 경우가 있어
     *    더 여유를 두었음 (connect 10s/read 40s)
     *
     * 이 RestTemplate은 Clova OCR, 카카오 주소검색, 국토부 실거래가, Gemini 4개 외부 API가
     * 공유하는 공용 빈이라, 그중 가장 넉넉한 값(connect 10s/read 40s)으로 통일한다.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(40))
                .build();
    }
}
