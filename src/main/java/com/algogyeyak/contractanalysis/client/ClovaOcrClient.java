package com.algogyeyak.contractanalysis.client;

import com.algogyeyak.contractanalysis.client.dto.ClovaOcrResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Naver Clova OCR(General) API 연동 클라이언트.
 */
public interface ClovaOcrClient {

    /**
     * @param image  인식 대상 이미지
     * @param format Clova가 요구하는 이미지 포맷 코드 (jpg, png 등)
     */
    ClovaOcrResponse recognize(MultipartFile image, String format);
}
