package com.algogyeyak.property.client;

import com.algogyeyak.property.client.dto.KakaoAddressSearchResponse;
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
public class KakaoAddressClientImpl implements KakaoAddressClient {

    private static final String KAKAO_ADDRESS_SEARCH_URL =
            "https://dapi.kakao.com/v2/local/search/address.json";

    private final RestTemplate restTemplate;

    @Value("${kakao.rest-api-key}")
    private String kakaoRestApiKey;

    @Override
    public AddressResolutionResult resolve(String address) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(KAKAO_ADDRESS_SEARCH_URL)
                    .queryParam("query", address)
                    .build()
                    .encode()
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoRestApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<KakaoAddressSearchResponse> response = restTemplate.exchange(
                    uri, HttpMethod.GET, entity, KakaoAddressSearchResponse.class
            );

            return toResult(address, response.getBody());

        } catch (RestClientException e) {
            log.error("Kakao 주소 검색 API 호출 실패 - address: {}, error: {}", address, e.getMessage());
            return AddressResolutionResult.unresolved();
        }
    }

    private AddressResolutionResult toResult(String address, KakaoAddressSearchResponse body) {
        List<KakaoAddressSearchResponse.Document> documents =
                body != null ? body.getDocuments() : null;

        if (documents == null || documents.isEmpty()) {
            log.warn("Kakao 주소 검색 결과 없음 - address: {}", address);
            return AddressResolutionResult.unresolved();
        }

        KakaoAddressSearchResponse.Document doc = documents.get(0);
        String jibunAddress = doc.getAddressName();
        String roadAddress = doc.getRoadAddress() != null
                ? doc.getRoadAddress().getAddressName()
                : null;

        try {
            return AddressResolutionResult.builder()
                    .resolved(true)
                    .jibunAddress(jibunAddress)
                    .roadAddress(roadAddress)
                    .latitude(Double.parseDouble(doc.getLatitude()))
                    .longitude(Double.parseDouble(doc.getLongitude()))
                    .build();
        } catch (NumberFormatException e) {
            log.error("Kakao 좌표 파싱 실패 - address: {}", address);
            return AddressResolutionResult.unresolved();
        }
    }
}
