package com.algogyeyak.checklist.config;

import com.algogyeyak.checklist.entity.ChecklistItemTemplate;
import com.algogyeyak.checklist.entity.ChecklistItemTemplateImage;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateImageRepository;
import com.algogyeyak.checklist.repository.ChecklistItemTemplateRepository;
import com.algogyeyak.global.s3.service.S3PresignService;
import com.algogyeyak.global.s3.util.S3ImagePurpose;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱이 시작될 때 체크리스트 템플릿을 시드 데이터와 맞춘다. 테이블이 완전히 비어있으면(최초 배포) 전체를
 * 새로 채우고, 이미 데이터가 있으면(운영처럼 이전 버전이 이미 시딩된 경우) content(문항 텍스트)로
 * 매칭해 없는 문항만 추가하고 기존 문항은 이번 시드 설계(순번 등)에 맞춰 재동기화한다 - 그래야 배포
 * 후에도 새로 추가된 시드 문항이 운영 DB에 실제로 반영된다(2026-08-14, 체크리스트 v3 배포 후 신규
 * 6개 문항이 운영에 반영 안 됐던 문제를 계기로 추가됨). 마이그레이션 도구(Flyway 등) 도입 전까지
 * 쓰는 임시 방식이다.
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
    @Transactional
    public void run(ApplicationArguments args) {
        List<ChecklistItemTemplate> desired = ChecklistTemplateSeedData.initialTemplates();

        if (checklistItemTemplateRepository.count() == 0) {
            List<ChecklistItemTemplate> saved = checklistItemTemplateRepository.saveAll(desired);
            attachImages(saved);
            return;
        }

        resyncToLatestSeed(desired);
    }

    // 이미 시딩된 DB(운영 등)에 새 시드 버전이 배포됐을 때, 아직 없는 문항만 추가하고 기존 문항은 이번
    // 시드 설계(카테고리·순번 등)에 맞춰 재동기화한다. 문항 식별은 content(문항 텍스트)로 한다 - 시드
    // 데이터에서 문항이 추가될 때 기존 문항의 텍스트를 바꾸는 경우는 없어서 이 매칭이 안전하다.
    // 이미지도 아직 안 붙어있는 문항(신규 추가된 문항 + 예전에 이미지 연결 기능이 없던 시절 시딩된
    // 기존 문항 둘 다)에만 뒤늦게 붙여서, 재배포될 때마다 중복으로 쌓이지 않게 한다.
    private void resyncToLatestSeed(List<ChecklistItemTemplate> desired) {
        Map<String, ChecklistItemTemplate> existingByContent = checklistItemTemplateRepository.findAll().stream()
                .collect(Collectors.toMap(ChecklistItemTemplate::getContent, Function.identity(), (a, b) -> a));

        List<ChecklistItemTemplate> missing = new ArrayList<>();
        List<ChecklistItemTemplate> resyncedWithoutImages = new ArrayList<>();
        for (ChecklistItemTemplate wanted : desired) {
            ChecklistItemTemplate existing = existingByContent.get(wanted.getContent());
            if (existing == null) {
                missing.add(wanted);
                continue;
            }
            existing.resyncFromSeed(wanted.getCategory(), wanted.getContent(), wanted.getGuideText(), wanted.getHelperText(),
                    wanted.getImportance(), wanted.getItemType(), wanted.getOptions(), wanted.getCode(),
                    wanted.getDisplayOrder(), wanted.getApplicablePropertyTypes(), wanted.getVersion());
            if (existing.getImages().isEmpty()) {
                resyncedWithoutImages.add(existing);
            }
        }

        List<ChecklistItemTemplate> savedMissing = missing.isEmpty()
                ? List.of()
                : checklistItemTemplateRepository.saveAll(missing);

        List<ChecklistItemTemplate> needsImages = new ArrayList<>(savedMissing);
        needsImages.addAll(resyncedWithoutImages);
        attachImages(needsImages);
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
