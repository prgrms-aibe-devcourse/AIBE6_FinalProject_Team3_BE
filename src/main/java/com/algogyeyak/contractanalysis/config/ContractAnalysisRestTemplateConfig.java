package com.algogyeyak.contractanalysis.config;

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
 * Gemini(GeminiClientImpl)/Clova OCR(ClovaOcrClientImpl) 전용 RestTemplate. property.config.
 * RestTemplateConfig의 공용 restTemplate 빈(카카오/국토부가 계속 쓰는 빈, 여기서는 건드리지 않음)과
 * 분리하는 이유: Gemini 응답이 느릴 때(9~10초 이상, 최대 40초까지 기다림) 설정된 넉넉한 타임아웃을
 * 공용 빈을 쓰면 카카오/국토부 호출까지 같이 떠안게 되고, 최악의 경우 둘 다 같은 커넥션 풀을
 * 공유해 톰캣 스레드가 장시간 묶인다.
 *
 * Gemini/Clova는 각각 단일 호스트(generativelanguage.googleapis.com / *.apigw.ntruss.com)만
 * 호출하므로 maxTotal == defaultMaxPerRoute로 둬도 실질적인 차이가 없다 - 그래도 두 값을 각각
 * 명시해 향후 라우트가 늘어나도(둘 다 아닐 가능성이 높지만) 의도가 코드에 드러나게 한다.
 *
 * SimpleClientHttpRequestFactory(공용 빈이 쓰는 HttpURLConnection 기반 팩토리)는 커넥션 풀
 * 크기라는 개념 자체가 없어(요청마다 별도 연결) 여기서는 Apache HttpClient5 +
 * PoolingHttpClientConnectionManager로 maxTotal/defaultMaxPerRoute를 직접 설정한다.
 *
 * 타임아웃은 ConnectionConfig.socketTimeout(연결의 SO_TIMEOUT)으로 설정했다 - RequestConfig.
 * responseTimeout이 아니라 이 값을 쓴 이유는, 두 클라이언트(GeminiClientImpl/ClovaOcrClientImpl)의
 * 기존 타임아웃 판별 로직이 java.net.SocketTimeoutException을 잡도록 되어 있는데,
 * ConnectionConfig.socketTimeout 초과는 (SimpleClientHttpRequestFactory의 read timeout과
 * 동일하게) 순수 소켓 read가 그대로 SocketTimeoutException을 던지기 때문이다. connect 단계
 * 타임아웃은 반대로 ConnectTimeoutException(SocketTimeoutException이 아님)으로 던져지므로,
 * 두 클라이언트의 catch 절에 이 예외도 함께 추가했다.
 */
@Configuration
public class ContractAnalysisRestTemplateConfig {

    @Bean
    public RestTemplate geminiRestTemplate() {
        return pooledRestTemplate(20, 20, 10, 40);
    }

    @Bean
    public RestTemplate clovaRestTemplate() {
        return pooledRestTemplate(10, 10, 3, 8);
    }

    private RestTemplate pooledRestTemplate(
            int maxTotal, int defaultMaxPerRoute, int connectTimeoutSeconds, int socketTimeoutSeconds
    ) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(defaultMaxPerRoute);
        connectionManager.setDefaultConnectionConfig(
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(connectTimeoutSeconds))
                        .setSocketTimeout(Timeout.ofSeconds(socketTimeoutSeconds))
                        .build()
        );

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
