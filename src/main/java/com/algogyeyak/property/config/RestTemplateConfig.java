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
     * connect 3s/read 5s로 시작했으나, 매물 상세조회 한 번에 국토부 API를 최대 6회(6개월치)
     * 순차 호출하는 market-data 쪽 특성상 요청이 겹치면 정상 응답도 5초를 넘겨 잘려나가는
     * 사례가 있어 여유를 좀 더 뒀다(connect 5s/read 10s). 브라우저로 단발 호출하면 즉시
     * 응답이 오는 걸 확인했으니 API 자체가 느린 게 아니라 우리 쪽 타임아웃이 빠듯했던 것.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
