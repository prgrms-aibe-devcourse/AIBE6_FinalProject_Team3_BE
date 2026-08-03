package com.algogyeyak.global.s3.service;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Service
public class S3PresignService {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucket;

    public S3PresignService(
            S3Presigner s3Presigner,
            S3Client s3Client,
            @Value("${aws.s3.bucket}") String bucket
    ) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    // 업로드용 presigned URL 발급. contentLength를 서명 대상에 포함시켜, 클라이언트가 실제 PUT 시
    // 신고한 크기와 다른 바이트 수를 보내면 S3가 서명 불일치(403)로 거부하게 만든다 - 그냥 값 검증만으로는
    // presigned URL을 발급받은 뒤 실제로는 더 큰 파일을 올리는 것을 막을 수 없기 때문에 필요하다.
    // (실제로 presigned URL의 X-Amz-SignedHeaders에 content-length가 포함되는 것을 로컬에서 확인함)
    public String generateUploadUrl(String key, String contentType, long contentLength, S3ImagePurpose purpose) {
        validateContentType(contentType, purpose);
        validateContentLength(contentLength, purpose);

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(objectRequest)
                .build();

        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    private void validateContentType(String contentType, S3ImagePurpose purpose) {
        if (contentType == null || !purpose.allowedContentTypes().contains(contentType.toLowerCase())) {
            throw new BusinessException(ErrorCode.FILE_CONTENT_TYPE_NOT_ALLOWED);
        }
    }

    private void validateContentLength(long contentLength, S3ImagePurpose purpose) {
        if (contentLength <= 0 || contentLength > purpose.maxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    // presigned URL로 실제 업로드가 끝났는지 확인한다. 호출한 도메인 서비스는 이 메서드가 예외 없이
    // 반환된 뒤에만 key를 엔티티에 저장해야 한다 - 그래야 "presigned URL만 발급받고 실제로는 업로드
    // 안 한" 죽은 키가 DB에 남는 걸 막을 수 있다. Content-Length는 presign 시점에 이미 서명으로
    // 강제했지만 그건 "그 URL 한 건"에 대한 보장이라, 여기서 실제로 올라간 객체를 한 번 더 확인해
    // 방어적으로 검증하고 어긋나면 즉시 지운다.
    public void confirmUpload(String key, S3ImagePurpose purpose) {
        HeadObjectResponse response = headObjectOrThrow(key);

        boolean contentTypeInvalid = response.contentType() == null
                || !purpose.allowedContentTypes().contains(response.contentType().toLowerCase());
        boolean tooLarge = response.contentLength() == null || response.contentLength() > purpose.maxSizeBytes();

        if (contentTypeInvalid || tooLarge) {
            deleteObject(key);
            throw new BusinessException(contentTypeInvalid ? ErrorCode.FILE_CONTENT_TYPE_NOT_ALLOWED : ErrorCode.FILE_TOO_LARGE);
        }
    }

    // HeadObject는 응답 바디가 없어 S3가 404를 NoSuchKey로 못 내려주고 바디 없는 일반 404로 내려주는
    // 경우가 있어(SDK 버전/상황에 따라 둘 다 관찰됨), NoSuchKeyException과 statusCode 404 둘 다 잡는다.
    private HeadObjectResponse headObjectOrThrow(String key) {
        try {
            return s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_NOT_COMPLETED);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BusinessException(ErrorCode.FILE_UPLOAD_NOT_COMPLETED);
            }
            throw e;
        }
    }

    // 조회 URL 발급. public 대상(PROFILE/PROPERTY)은 버킷 정책으로 이미 익명 read가 열려 있으므로
    // 만료 없는 고정 URL을 바로 리턴하고, private 대상(CONTRACT)은 presigned GET으로만 접근 가능하게
    // 한다 - 호출부(도메인 서비스)는 public 대상의 경우 이 리턴값을 그대로 DB에 영구 저장해도 되고,
    // private 대상은 매번 새로 호출해서 받은 값만 그 순간의 응답에만 써야 한다(저장하면 10분 뒤 만료됨).
    public String generateDownloadUrl(String key, S3ImagePurpose purpose) {
        if (purpose.isPublic()) {
            return s3Client.utilities().getUrl(GetUrlRequest.builder().bucket(bucket).key(key).build()).toString();
        }

        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .getObjectRequest(objectRequest)
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    // 계약서 이미지는 OCR 처리 후 즉시 삭제용으로, 프로필/매물 이미지는 새 이미지로 교체된 이전
    // 이미지를 정리하는 용도로 쓴다.
    public void deleteObject(String key) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
        );
    }

    // 저장된 URL(예: User.profileImageUrl)이 우리 버킷의 객체를 가리키는지 확인하고, 맞으면 key만
    // 뽑아낸다. generateDownloadUrl(public)이 만드는 URL은 virtual-hosted-style
    // (`https://{bucket}.s3.{region}.amazonaws.com/{key}`)이라 host가 `{bucket}.s3.`로 시작하는지로
    // 판별한다 - 구글/카카오 같은 외부 OAuth 프로필 사진 URL은 이 패턴과 안 맞으므로 항상 empty를
    // 반환해, 우리 버킷이 아닌 객체를 절대 삭제 대상으로 착각하지 않게 한다.
    public Optional<String> extractOwnedKey(String url) {
        if (url == null) {
            return Optional.empty();
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || !host.startsWith(bucket + ".s3.") || path == null || path.length() <= 1) {
            return Optional.empty();
        }

        return Optional.of(path.substring(1));
    }
}
