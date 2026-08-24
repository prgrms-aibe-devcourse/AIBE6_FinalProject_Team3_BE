package com.algogyeyak.property.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 주소검색/지역코드, 국토부 실거래가 API 전용 RestTemplate.
 *
 * 원래 이 빈은 Clova OCR/Gemini까지 포함한 4개 외부 API가 공유하는 공용 빈이었다. Gemini 응답이
 * 9~10초 이상 걸리는 경우가 있어(최대 40초까지 기다림) 타임아웃을 넉넉하게 잡았는데, 그 값을
 * 공용 빈으로 카카오/국토부까지 그대로 물려받으면서 (1) 응답이 느린 외부 API 하나 때문에 원래
 * 빠른 카카오/국토부 호출까지 같은 톰캣 스레드를 오래 붙잡는 위험, (2) 아래 이유로 커넥션 풀링이
 * 아예 없어 호출마다 TCP 연결을 새로 맺는 오버헤드가 있었다(2026-08-21 멘토링 피드백).
 * Gemini/Clova는 {@code ContractAnalysisRestTemplateConfig}로 이미 분리됐고(#276), 이 빈은
 * 이제 카카오/국토부 전용이라 타임아웃을 원래 값(connect 5s/read 10s - 매물 상세조회 한 번에
 * 국토부 API를 최대 6회 순차 호출하는 market-data 특성상 5s는 부족했던 이력이 있어 10s로 둠)으로
 * 되돌리고, HttpClient5 + {@link PoolingHttpClientConnectionManager}로 교체해 커넥션 풀링을
 * 적용한다(#284).
 *
 * User-Agent 헤더는 이 빈이 아니라 호출부({@code MolitRentClientImpl}/{@code MolitTradeClientImpl})가
 * {@link org.springframework.http.HttpHeaders}로 매 요청마다 명시적으로 설정한다(팩토리 레벨
 * {@code setRequestProperty}가 아님) - 국토부 API(data.go.kr)가 커스텀 User-Agent 없는 요청을
 * 차단하는 문제(2026-08-04 확인)와는 무관한 요청 단위 헤더라, HttpClient5로 팩토리를 바꿔도
 * 그대로 전달된다(Gemini/Clova 전용 빈에서 이미 같은 방식으로 검증됨).
 */
@Configuration
public class RestTemplateConfig {

    private static final int MAX_TOTAL_CONNECTIONS = 30;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 15;
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int SOCKET_TIMEOUT_SECONDS = 10;

    @Bean
    public RestTemplate restTemplate() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(MAX_TOTAL_CONNECTIONS);
        connectionManager.setDefaultMaxPerRoute(MAX_CONNECTIONS_PER_ROUTE);
        connectionManager.setDefaultConnectionConfig(
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                        .setSocketTimeout(Timeout.ofSeconds(SOCKET_TIMEOUT_SECONDS))
                        .build()
        );

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
