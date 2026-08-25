package com.algogyeyak.checklist.service;

import com.algogyeyak.checklist.dto.ChecklistResponse;
import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.repository.ChecklistRepository;
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ChecklistServiceTest는 Mockito 목 저장소라 createOrGetChecklist()의 "없으면 insert" 로직이 실제
 * DB 유니크 제약(uk_checklist_user_property)과 동시성 아래서도 안전한지는 검증하지 못한다 - 같은
 * 유저-매물 조합에 대한 요청 두 개가(체크리스트 시작 버튼 더블클릭, 프론트 useEffect 중복 호출
 * 등으로) 정말 동시에 들어오면 둘 다 "기존 행 없음"을 보고 동시에 insert를 시도해
 * DataIntegrityViolationException이 실제로 날 수 있다(k6 03-race-conditions.js의 checklistCreate
 * 시나리오로 실제 재현 확인함). 이 테스트는 실제 H2 DB + 실제 리포지토리로 createOrGetChecklist()가
 * insert를 REQUIRES_NEW 트랜잭션으로 격리해 이 경쟁을 실제로 막는지 확인한다
 * (FakeListingSignalServiceConcurrencyTest와 동일한 검증 방식).
 */
@SpringBootTest
class ChecklistServiceConcurrencyTest {

    @Autowired
    private ChecklistService checklistService;

    @Autowired
    private ChecklistRepository checklistRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    @Test
    @DisplayName("동일 유저-매물 조합에 대한 createOrGetChecklist() 두 번이 동시에 들어와도 유니크 제약 위반 없이 정확히 1건만 생성된다")
    @Timeout(15)
    void concurrentCreateOrGetChecklistForSameUserPropertyDoesNotViolateUniqueConstraint() throws Exception {
        User user = userRepository.saveAndFlush(
                User.createOAuthUser("concurrency-test@example.com", "동시성테스트유저", "http://img"));
        Property property = propertyRepository.saveAndFlush(Property.builder()
                .userId(user.getId())
                .title("동시성 테스트 매물")
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(100_000_000L)
                .area(30.0)
                .build());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<Checklist> a = executor.submit(() -> checklistService.createOrGetChecklist(user.getId(), property.getId()));
        Future<Checklist> b = executor.submit(() -> checklistService.createOrGetChecklist(user.getId(), property.getId()));
        // get()이 예외를 던지지 않는 것 자체가 "유니크 제약 위반이 두 스레드 중 어느 쪽에서도 새어
        // 나오지 않았다"는 확인이다 - 잠금이 없던 원래 코드에서는 둘 중 하나가 여기서
        // DataIntegrityViolationException(500)으로 실패했다.
        Checklist resultA = a.get(10, TimeUnit.SECONDS);
        Checklist resultB = b.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resultA.getId()).as("두 요청 모두 같은 체크리스트를 받아야 한다(경쟁에서 진 쪽도 승자를 그대로 반환)")
                .isEqualTo(resultB.getId());
        assertThat(checklistRepository.findByUserIdAndPropertyId(user.getId(), property.getId())).isPresent();

        // ChecklistController.createChecklist()가 실제로 호출하는 것과 동일한 코드 경로 - items 안의
        // 각 item.getTemplate().getImages()까지 지연 로딩을 트리거한다. 두 Future 모두 이미 get()으로
        // 완료돼 각자의 REQUIRES_NEW 세션(경쟁에서 졌다면 복구용 세션)이 닫힌 뒤이므로, 여기서 예외 없이
        // 통과해야 재조회 경로가 items.template.images까지 미리 초기화해뒀다는 뜻이다(둘 중 어느 쪽이
        // 승자/패자인지는 레이스 타이밍에 따라 달라 매번 다르므로 양쪽 다 확인한다).
        assertThatCode(() -> ChecklistResponse.from(resultA))
                .as("경쟁에서 진 쪽이 반환한 엔티티도 컨트롤러의 응답 변환(items.template.images 접근)이 예외 없이 끝나야 한다")
                .doesNotThrowAnyException();
        assertThatCode(() -> ChecklistResponse.from(resultB))
                .as("경쟁에서 진 쪽이 반환한 엔티티도 컨트롤러의 응답 변환(items.template.images 접근)이 예외 없이 끝나야 한다")
                .doesNotThrowAnyException();
    }
}
