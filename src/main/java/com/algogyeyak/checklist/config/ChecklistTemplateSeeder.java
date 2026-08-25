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
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 앱이 시작될 때 체크리스트 템플릿을 시드 데이터와 맞춘다. 테이블이 완전히 비어있으면(최초 배포) 전체를
 * 새로 채우고, 이미 데이터가 있으면(운영처럼 이전 버전이 이미 시딩된 경우) content(문항 텍스트)로
 * 매칭해 없는 문항만 추가하고 기존 문항은 이번 시드 설계(순번 등)에 맞춰 재동기화한다 - 그래야 배포
 * 후에도 새로 추가된 시드 문항이 운영 DB에 실제로 반영된다(2026-08-14, 체크리스트 v3 배포 후 신규
 * 6개 문항이 운영에 반영 안 됐던 문제를 계기로 추가됨). 마이그레이션 도구(Flyway 등) 도입 전까지
 * 쓰는 임시 방식이다.
 *
 * 동시성(2026-08-19 추가): 예전엔 "테이블이 비어있으면 saveAll" / "없는 문항만 findAll로 찾아 추가"가
 * 전부 조회 후 쓰기(read-then-write) 방식이었다 - 두 인스턴스가 동시에 기동하면(롤링/블루그린 배포)
 * 둘 다 같은 문항을 "아직 없다"고 읽고 둘 다 저장을 시도해 시드 행이 조용히 중복될 수 있었다.
 * AdminAccountSeeder(email의 진짜 유니크 제약 + DataIntegrityViolationException catch)와 동일한
 * 패턴으로, ChecklistItemTemplate.seedKey에 실제 DB 유니크 제약을 걸고(entity 클래스 주석 참고)
 * 문항 하나하나를 독립된 트랜잭션으로 저장하며 그 예외를 잡는다 - 어느 한 문항이 경쟁에서 졌다고
 * 앱 기동 전체가 실패해서는 안 된다.
 */
@Component
@RequiredArgsConstructor
public class ChecklistTemplateSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChecklistTemplateSeeder.class);

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
        resyncToLatestSeed(ChecklistTemplateSeedData.initialTemplates());
    }

    // 이미 시딩된 DB(운영 등)에 새 시드 버전이 배포됐을 때, 아직 없는 문항만 추가하고 기존 문항은 이번
    // 시드 설계(카테고리·순번 등)에 맞춰 재동기화한다. 문항 식별은 content(문항 텍스트)로 한다 - 시드
    // 데이터에서 문항이 추가될 때 기존 문항의 텍스트를 바꾸는 경우는 없어서 이 매칭이 안전하다. 테이블이
    // 완전히 비어있는 최초 배포도 이 로직 하나로 처리된다(existingByContent가 빈 맵일 뿐이라 desired
    // 전부가 자연스럽게 "없는 문항"으로 분류됨) - 예전처럼 count()==0을 별도 분기로 두지 않는다.
    // 이미지도 아직 안 붙어있는 문항(신규 추가된 문항 + 예전에 이미지 연결 기능이 없던 시절 시딩된
    // 기존 문항 둘 다)에만 뒤늦게 붙여서, 재배포될 때마다 중복으로 쌓이지 않게 한다.
    //
    // findAllWithImages()로 읽은 뒤 이 메서드 안에서 개별 save()를 호출하는 전 과정이 하나의
    // @Transactional로 묶여 있지 않다는 점이 핵심이다 - 문항 하나를 저장하다 seedKey 유니크 제약에
    // 걸려도(다른 인스턴스가 먼저 심었음) 그 실패가 다른 문항들의 저장/갱신에 전혀 영향을 주지 않는다.
    private void resyncToLatestSeed(List<ChecklistItemTemplate> desired) {
        Map<String, ChecklistItemTemplate> existingByContent = checklistItemTemplateRepository.findAllWithImages().stream()
                .collect(Collectors.toMap(ChecklistItemTemplate::getContent, Function.identity(), (a, b) -> a));

        List<ChecklistItemTemplate> insertedMissing = new ArrayList<>();
        List<ChecklistItemTemplate> resyncedWithoutImages = new ArrayList<>();
        for (ChecklistItemTemplate wanted : desired) {
            ChecklistItemTemplate existing = existingByContent.get(wanted.getContent());
            if (existing == null) {
                insertIfNotAlreadySeededConcurrently(wanted).ifPresent(insertedMissing::add);
                continue;
            }
            existing.resyncFromSeed(wanted.getCategory(), wanted.getContent(), wanted.getGuideText(), wanted.getHelperText(),
                    wanted.getImportance(), wanted.getItemType(), wanted.getOptions(), wanted.getCode(),
                    wanted.getDisplayOrder(), wanted.getApplicablePropertyTypes(), wanted.getVersion());
            // 예전 버전에서 시딩됐던 행은 seedKey가 아직 비어있을 수 있어(이 컬럼 자체가 나중에
            // 추가됨), 재동기화하는 김에 함께 채워 넣는다 - 그래야 다음 재배포부터 이 행도 유니크
            // 제약의 보호를 받는다.
            existing.markAsSeeded();
            checklistItemTemplateRepository.save(existing);
            if (existing.getImages().isEmpty()) {
                resyncedWithoutImages.add(existing);
            }
        }

        List<ChecklistItemTemplate> needsImages = new ArrayList<>(insertedMissing);
        needsImages.addAll(resyncedWithoutImages);
        attachImages(needsImages);
    }

    /**
     * 시드 문항을 실제로 새로 삽입한다. ChecklistItemTemplate.seedKey의 유니크 제약이 동시성
     * 가드다 - 두 인스턴스가 동시에 기동하며 둘 다 이 문항을 위 existingByContent에서 "없다"고
     * 판단해 여기까지 왔더라도, 먼저 커밋한 쪽만 성공하고 나중 쪽은 DataIntegrityViolationException을
     * 받는다. AdminAccountSeeder와 동일한 이유로 이 예외 하나 때문에 ApplicationRunner 전체(=앱
     * 기동)가 실패해서는 안 되므로 경고만 남기고 계속 진행한다 - 어차피 먼저 커밋한 인스턴스가 이미
     * 그 행(과 그 인스턴스 자신의 attachImages() 호출을 통한 예시 이미지)을 만들어 뒀으므로 안전하게
     * 건너뛸 수 있다.
     */
    private Optional<ChecklistItemTemplate> insertIfNotAlreadySeededConcurrently(ChecklistItemTemplate wanted) {
        wanted.markAsSeeded();
        try {
            return Optional.ofNullable(checklistItemTemplateRepository.save(wanted));
        } catch (DataIntegrityViolationException e) {
            log.warn(
                    "체크리스트 시드 문항('{}') 저장 실패 - 동시 기동 중인 다른 인스턴스가 이미 같은 문항을"
                            + " 심었을 수 있습니다(이 경우 정상 - 그 인스턴스가 이미지까지 붙였을 것입니다)."
                            + " 원인은 cause 메시지를 확인하세요.",
                    wanted.getContent(), e);
            return Optional.empty();
        }
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
                insertImageIfNotAlreadyAttachedConcurrently(template, imageUrl, displayOrder);
                displayOrder++;
            }
        }
    }

    // 여기서도 template 저장과 같은 문제가 있다 - "이 문항엔 아직 이미지가 없다"는 판단이
    // resyncToLatestSeed()의 findAllWithImages() 조회 결과 하나로 이뤄지는데, 두 인스턴스가 동시에
    // 같은 결과를 읽으면 둘 다 같은 문항에 같은 이미지 세트를 중복 삽입하려 시도할 수 있다.
    // ChecklistItemTemplateImage의 (template_id, display_order) 유니크 제약이 그 가드다 - 먼저
    // 커밋한 쪽만 성공하고, 나머지는 이 예외를 잡아 조용히 건너뛴다(이미 다른 인스턴스가 같은
    // 이미지를 붙여뒀으므로 안전하게 건너뛸 수 있다).
    private void insertImageIfNotAlreadyAttachedConcurrently(ChecklistItemTemplate template, String imageUrl, int displayOrder) {
        try {
            checklistItemTemplateImageRepository.save(ChecklistItemTemplateImage.builder()
                    .template(template)
                    .imageUrl(imageUrl)
                    .displayOrder(displayOrder)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.warn(
                    "체크리스트 시드 이미지(문항='{}', 순서={}) 저장 실패 - 동시 기동 중인 다른 인스턴스가"
                            + " 이미 같은 이미지를 붙였을 수 있습니다(이 경우 정상). 원인은 cause 메시지를 확인하세요.",
                    template.getContent(), displayOrder, e);
        }
    }
}
