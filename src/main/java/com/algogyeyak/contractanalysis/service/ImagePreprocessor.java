package com.algogyeyak.contractanalysis.service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Clova OCR로 보내기 전 이미지를 가볍게 보정한다(그레이스케일 -> 대비 향상 -> 선명화).
 * 텍스트 인식률을 높이기 위한 보조 수단이므로, 디코딩 실패 등 어떤 이유로든 보정에 실패하면
 * 원본 이미지를 그대로 반환한다(fail-open) — 전처리 실패가 OCR 자체를 막아서는 안 된다.
 */
@Slf4j
@Component
public class ImagePreprocessor {

    // 대비: 128(중간 회색)을 기준점으로 스케일링해 중간톤은 거의 유지한 채 명암 차이만 살짝 키운다.
    private static final float CONTRAST_SCALE = 1.15f;
    private static final float CONTRAST_OFFSET = 128f * (1f - CONTRAST_SCALE);

    // 계수 합이 1인 약한 언샤프 커널 — 전체 밝기를 보존하면서 글자 경계만 살짝 강조한다.
    private static final float[] SHARPEN_KERNEL = {
             0f, -0.15f,      0f,
        -0.15f,   1.6f,  -0.15f,
             0f, -0.15f,      0f
    };

    public MultipartFile preprocess(MultipartFile original, String formatName) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original.getBytes()));
            if (source == null) {
                return original;
            }

            BufferedImage processed = sharpen(enhanceContrast(toGrayscale(source)));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(processed, formatName, out)) {
                return original;
            }

            return new InMemoryMultipartFile(
                    original.getName(), original.getOriginalFilename(), original.getContentType(), out.toByteArray());
        } catch (IOException | RuntimeException e) {
            log.warn("OCR 이미지 전처리에 실패해 원본 이미지로 진행합니다", e);
            return original;
        }
    }

    private BufferedImage toGrayscale(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return gray;
    }

    private BufferedImage enhanceContrast(BufferedImage source) {
        RescaleOp op = new RescaleOp(CONTRAST_SCALE, CONTRAST_OFFSET, null);
        return op.filter(source, null);
    }

    private BufferedImage sharpen(BufferedImage source) {
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, SHARPEN_KERNEL), ConvolveOp.EDGE_NO_OP, null);
        return op.filter(source, null);
    }
}
