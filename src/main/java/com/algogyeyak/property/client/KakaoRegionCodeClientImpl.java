package com.algogyeyak.property.client;

import com.algogyeyak.property.client.dto.KakaoRegionCodeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoRegionCodeClientImpl implements KakaoRegionCodeClient {

    private static final String COORD_TO_REGION_CODE_URL =
            "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json";
    private static final String LEGAL_DONG_REGION_TYPE = "B";

    private final RestTemplate restTemplate;

    @Value("${kakao.rest-api-key}")
    private String kakaoRestApiKey;

    @Override
    public RegionCodeResult resolve(double latitude, double longitude) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(COORD_TO_REGION_CODE_URL)
                    .queryParam("x", longitude)
                    .queryParam("y", latitude)
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoRestApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<KakaoRegionCodeResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, entity, KakaoRegionCodeResponse.class
            );

            return toResult(response.getBody());

        } catch (RestClientException e) {
            log.error("Kakao 좌표to행정구역 API 호출 실패 - lat: {}, lng: {}, error: {}", latitude, longitude, e.getMessage());
            return RegionCodeResult.unresolved();
        }
    }

    private RegionCodeResult toResult(KakaoRegionCodeResponse body) {
        List<KakaoRegionCodeResponse.Document> documents = body != null ? body.getDocuments() : null;
        if (documents == null) {
            return RegionCodeResult.unresolved();
        }

        return documents.stream()
                .filter(doc -> LEGAL_DONG_REGION_TYPE.equals(doc.getRegionType()))
                .findFirst()
                .map(doc -> {
                    String code = doc.getCode();
                    if (code == null || code.length() < 5) {
                        return RegionCodeResult.unresolved();
                    }
                    return RegionCodeResult.builder()
                            .resolved(true)
                            .legalDongCode(code)
                            .lawdCd(code.substring(0, 5))
                            .regionName(doc.getAddressName())
                            .build();
                })
                .orElseGet(RegionCodeResult::unresolved);
    }
}
