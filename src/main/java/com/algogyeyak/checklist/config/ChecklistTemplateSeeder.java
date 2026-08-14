package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateImageRepository;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱이 시작될 때 체크리스트 템플릿이 비어있으면 초기 시드 데이터를 채워 넣는다.
 * 마이그레이션 도구(Flyway 등) 도입 전까지 쓰는 임시 방식이다.
 */
@Component
@RequiredArgsConstructor
public class ChecklistTemplateSeeder implements ApplicationRunner {

    // (2026-08-14 임시) 문항별 예시 이미지의 S3 key. ChecklistTemplateSeedData의 문항 content와
    // 마찬가지로 환경별 설정값이 아니라 이 앱이 처음 시작할 때 채워 넣는 콘텐츠(seed data)라서
    // application.yml이 아니라 여기(코드)에 둔다 - S3 콘솔에 폴더 구분 없이 번호로만 올려둔
    // 파일들을 문항 content로 매칭한다. 전체 URL이 아니라 key만 갖고 있고, 실제 URL은
    // S3PresignService.generateDownloadUrl()로 조회 시점에 만든다(프로필/매물 이미지와 동일한 방식).
    // 관리자 페이지에 이미지 관리 기능이 생기면 이 하드코딩은 제거한다.
    private static final Map<String, List<String>> TEMPLATE_IMAGE_KEYS_BY_CONTENT = Map.of(
            "벽면·천장·바닥에 누수 흔적이나 곰팡이가 없나요?", List.of(
                    "checklist-template-images/1.jpg",
                    "checklist-template-images/2.jpg",
                    "checklist-template-images/3.jpg"
            ),
            "콘센트·전기 배선은 정상 작동하나요?", List.of(
                    "checklist-template-images/4.jpg",
                    "checklist-template-images/5.jpg"
            ),
            "차단기함 상태를 확인했나요?", List.of(
                    "checklist-template-images/6.jpg"
            ),
            "소화기·화재감지기가 비치되어 있나요?", List.of(
                    "checklist-template-images/7.jpg"
            )
    );

    private final ChecklistItemTemplateRepository checklistItemTemplateRepository;
    private final ChecklistItemTemplateImageRepository checklistItemTemplateImageRepository;
    private final S3PresignService s3PresignService;

    @Override
    public void run(ApplicationArguments args) {
        if (checklistItemTemplateRepository.count() > 0) {
            return;
        }

        List<ChecklistItemTemplate> saved =
                checklistItemTemplateRepository.saveAll(ChecklistTemplateSeedData.initialTemplates());
        attachImages(saved);
    }

    private void attachImages(List<ChecklistItemTemplate> savedTemplates) {
        for (ChecklistItemTemplate template : savedTemplates) {
            List<String> imageKeys = TEMPLATE_IMAGE_KEYS_BY_CONTENT.get(template.getContent());
            if (imageKeys == null) {
                continue;
            }
            int displayOrder = 1;
            for (String imageKey : imageKeys) {
                String imageUrl = s3PresignService.generateDownloadUrl(imageKey, S3ImagePurpose.CHECKLIST_TEMPLATE);
                checklistItemTemplateImageRepository.save(ChecklistItemTemplateImage.builder()
                        .template(template)
                        .imageUrl(imageUrl)
                        .displayOrder(displayOrder)
                        .build());
                displayOrder++;
            }
        }
    }
}
