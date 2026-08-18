package com.algogyeyak.global.s3.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * validatePurposePrefix()는 이번 전수조사 후속 수정의 핵심 방어선이다(다른 purpose, 특히 비공개
 * contract-images/의 key를 공개 purpose로 넘겨 confirm/조회하는 교차 도메인 오용을 막음) - 호출부
 * 대부분이 S3PresignService 자체를 mock으로 대체해 검증하므로, 이 클래스만 별도로 실제 로직을
 * 테스트해 회귀를 잡는다.
 */
class S3PresignServiceTest {

    private final S3Presigner s3Presigner = mock(S3Presigner.class);
    private final S3Client s3Client = mock(S3Client.class);
    private final S3PresignService service = new S3PresignService(s3Presigner, s3Client, "test-bucket");

    @Test
    void confirmUploadRejectsKeyWhosePrefixDoesNotMatchPurpose() {
        // 비공개(CONTRACT) key를 공개(PROPERTY) purpose로 confirm하려는 시도 - 두 purpose의 허용
        // content-type/크기 조건이 겹쳐 실제 S3 객체 검증까지 통과할 수 있는 조합이라, prefix
        // 검증이 없으면 비공개 계약서 이미지가 공개 URL로 노출될 수 있었다.
        String contractKey = S3ImagePurpose.CONTRACT.prefix() + "1/some-uuid.jpg";

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.confirmUpload(contractKey, S3ImagePurpose.PROPERTY));

        assertEquals(ErrorCode.FILE_KEY_ACCESS_DENIED, exception.getErrorCode());
        // prefix 검증이 실제 S3 호출(headObject 등)보다 먼저 걸려야, 신뢰할 수 없는 key로 굳이
        // AWS까지 왕복하지 않는다.
        verifyNoInteractions(s3Client);
    }

    @Test
    void generateDownloadUrlRejectsKeyWhosePrefixDoesNotMatchPurpose() {
        String contractKey = S3ImagePurpose.CONTRACT.prefix() + "1/some-uuid.jpg";

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generateDownloadUrl(contractKey, S3ImagePurpose.PROPERTY));

        assertEquals(ErrorCode.FILE_KEY_ACCESS_DENIED, exception.getErrorCode());
        verifyNoInteractions(s3Client);
    }

    @Test
    void generateDownloadUrlRejectsNullKey() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.generateDownloadUrl(null, S3ImagePurpose.PROPERTY));

        assertEquals(ErrorCode.FILE_KEY_ACCESS_DENIED, exception.getErrorCode());
    }

    @Test
    void generateDownloadUrlAcceptsKeyWithMatchingPrefix() throws MalformedURLException {
        // prefix가 실제로 일치하면 정상적으로 통과해 기존 동작(공개 purpose는 s3Client.utilities()
        // 고정 URL)이 그대로 유지되는지 확인한다 - 위 두 실패 테스트와 짝을 이뤄, prefix 검증이
        // "너무 엄격해서 정상 케이스까지 막는" 회귀도 함께 잡는다.
        String propertyKey = S3ImagePurpose.PROPERTY.prefix() + "1/some-uuid.jpg";
        URL fakeUrl = URI.create("https://test-bucket.s3.ap-northeast-2.amazonaws.com/" + propertyKey).toURL();
        S3Utilities s3Utilities = mock(S3Utilities.class);
        when(s3Client.utilities()).thenReturn(s3Utilities);
        when(s3Utilities.getUrl(any(GetUrlRequest.class))).thenReturn(fakeUrl);

        String result = service.generateDownloadUrl(propertyKey, S3ImagePurpose.PROPERTY);

        assertEquals(fakeUrl.toString(), result);
    }
}
