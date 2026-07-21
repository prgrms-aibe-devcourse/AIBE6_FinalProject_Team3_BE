package com.algogyeyak.property.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.algogyeyak.property.client.dto.KakaoAddressSearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * KakaoAddressClientImpl 단위테스트.
 * RestTemplate을 mock으로 대체해 실제 Kakao API 호출 없이
 * 성공 / 검색결과 없음 / API 호출 실패 3가지 케이스를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class KakaoAddressClientImplTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;

    private KakaoAddressClientImpl kakaoAddressClient;

    @BeforeEach
    void setUp() {
        kakaoAddressClient = new KakaoAddressClientImpl(restTemplate);
        ReflectionTestUtils.setField(kakaoAddressClient, "kakaoRestApiKey", "test-api-key");
    }

    @Test
    void resolve_성공하면_주소와_좌표를_반환한다() throws Exception {
        String json = """
                {
                  "meta": { "total_count": 1 },
                  "documents": [
                    {
                      "address_name": "서울특별시 강남구 역삼동 123-45",
                      "x": "127.031393491745",
                      "y": "37.4995539438207",
                      "road_address": { "address_name": "서울특별시 강남구 테헤란로 123" }
                    }
                  ]
                }
                """;
        KakaoAddressSearchResponse response = OBJECT_MAPPER.readValue(json, KakaoAddressSearchResponse.class);

        when(restTemplate.exchange(
                any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoAddressSearchResponse.class)
        )).thenReturn(ResponseEntity.ok(response));

        AddressResolutionResult result = kakaoAddressClient.resolve("서울특별시 강남구 테헤란로 123");

        assertThat(result.isResolved()).isTrue();
        assertThat(result.getRoadAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
        assertThat(result.getJibunAddress()).isEqualTo("서울특별시 강남구 역삼동 123-45");
        assertThat(result.getLatitude()).isEqualTo(37.4995539438207);
        assertThat(result.getLongitude()).isEqualTo(127.031393491745);
    }

    @Test
    void resolve_검색결과가_없으면_unresolved를_반환한다() throws Exception {
        String json = """
                {
                  "meta": { "total_count": 0 },
                  "documents": []
                }
                """;
        KakaoAddressSearchResponse response = OBJECT_MAPPER.readValue(json, KakaoAddressSearchResponse.class);

        when(restTemplate.exchange(
                any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoAddressSearchResponse.class)
        )).thenReturn(ResponseEntity.ok(response));

        AddressResolutionResult result = kakaoAddressClient.resolve("존재하지 않는 주소");

        assertThat(result.isResolved()).isFalse();
    }

    @Test
    void resolve_API_호출이_실패하면_unresolved를_반환한다() {
        when(restTemplate.exchange(
                any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(KakaoAddressSearchResponse.class)
        )).thenThrow(new RestClientException("Kakao API 호출 실패"));

        AddressResolutionResult result = kakaoAddressClient.resolve("서울특별시 강남구 테헤란로 123");

        assertThat(result.isResolved()).isFalse();
    }
}
