# "내 체크리스트 목록" API + Checklist-Property FK 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **이 프로젝트의 예외:** 이 플랜의 실행자는 태스크를 하나 끝낼 때마다 멈추고, 사용자가 직접 커밋한 뒤 "커밋 완료"라고 알려줄 때까지 다음 태스크를 시작하지 않는다. 실행자가 대신 `git commit`을 실행하지 않는다 (아래 각 태스크의 마지막 스텝 참고).

**Goal:** `Checklist.propertyId`(순수 Long)를 `Property`와의 실제 FK 연관관계로 바꾸고 매물 존재/소유권 검증을 추가한 뒤, 유저의 매물별 체크리스트 현황을 한 번에 보여주는 `GET /checklists` 엔드포인트를 만든다.

**Architecture:** 기존 `Checklist`/`ChecklistService`/`ChecklistController`를 확장한다. 새 엔드포인트는 `PropertyRepository`(이미 있음)로 유저의 ACTIVE 매물을 가져오고, `ChecklistRepository`의 신규 메서드로 유저의 체크리스트 전체를 가져와 `propertyId` 기준으로 매칭해 합친다. 새 테이블/컬럼은 없다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, JUnit5, AssertJ, Mockito, H2(dev).

**참고 스펙:** `Backend/algogyeyak/docs/superpowers/specs/2026-07-27-checklist-overview-list-design.md`

**계획 작성 중 확인한 구현 디테일 (스펙 대비 단순화):** 스펙에는 `ChecklistRepository.findByUserIdAndPropertyId`를 `findByUserIdAndProperty_Id`로 리네임한다고 적었으나, 실제로는 안 바꿔도 된다 — Spring Data가 `PropertyId`를 엔티티에 그런 이름의 필드가 없으면 `property.id`(연관관계 탐색)로 자동 해석한다. 지금 `UserId`도 이미 이 방식으로 `user.id`를 가리키고 있어서 똑같은 동작이다. 그래서 이 플랜에서는 리포지토리 메서드 이름을 바꾸지 않는다 (불필요한 변경 제거).

---

## 사전 확인

- [ ] **Step 0: 테스트 스위트가 지금 전부 통과하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.*"`
Expected: BUILD SUCCESSFUL (전부 PASS)

---

### Task 1: `Checklist.propertyId` → `Property` FK 전환 (엔티티 + 응답 DTO + 기존 테스트)

**Files:**
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/entity/Checklist.java`
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/dto/ChecklistResponse.java`
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/entity/ChecklistTest.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java`

이 태스크는 리팩터링(내부 표현 변경)이라 새 실패 테스트를 먼저 쓰는 대신, 기존 테스트를 새 시그니처에 맞게 고치고 그대로 통과하는지 확인하는 방식으로 진행한다.

- [ ] **Step 1: `Checklist` 엔티티를 `Property` FK를 쓰도록 변경**

`Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/entity/Checklist.java`의 import에 추가:

```java
import com.algogyeyak.property.entity.Property;
```

`propertyId` 필드(29-36번째 줄 근처, 클래스 Javadoc 포함)를 다음으로 교체:

```java
/**
 * 매물별 임장 체크리스트. 한 유저는 같은 매물에 대해 활성 체크리스트를 1개만 가진다
 * (user_id + property_id unique 제약).
 */
```

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;
```

(기존 `@Column(name = "property_id", nullable = false) private Long propertyId;`를 대체 — 컬럼명은 그대로 `property_id`라 DB 스키마/유니크 제약(`uk_checklist_user_property`)에는 영향 없음.)

생성자와 `createFrom`을 다음으로 교체:

```java
    @Builder
    private Checklist(User user, Property property, int templateVersion) {
        this.user = user;
        this.property = property;
        this.templateVersion = templateVersion;
        this.status = ChecklistStatus.NOT_STARTED;
    }

    /**
     * 템플릿 목록을 스냅샷 복사해 체크리스트와 문항들을 함께 생성한다.
     * 이후 템플릿이 새 버전으로 바뀌어도 여기서 만들어진 문항 내용은 그대로 유지된다.
     */
    public static Checklist createFrom(User user, Property property, int templateVersion, List<ChecklistItemTemplate> templates) {
        Checklist checklist = Checklist.builder()
                .user(user)
                .property(property)
                .templateVersion(templateVersion)
                .build();

        for (ChecklistItemTemplate template : templates) {
            checklist.items.add(ChecklistItem.builder()
                    .checklist(checklist)
                    .category(template.getCategory())
                    .content(template.getContent())
                    .guideText(template.getGuideText())
                    .importance(template.getImportance())
                    .itemType(template.getItemType())
                    .code(template.getCode())
                    .displayOrder(template.getDisplayOrder())
                    .build());
        }

        return checklist;
    }
```

- [ ] **Step 2: `ChecklistResponse`가 `getProperty().getId()`를 쓰도록 변경**

`Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/dto/ChecklistResponse.java` 전체를 다음으로 교체:

```java
package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;

import java.util.List;

public record ChecklistResponse(
        Long id,
        Long propertyId,
        int templateVersion,
        ChecklistStatus status,
        List<ChecklistItemResponse> items
) {
    public static ChecklistResponse from(Checklist checklist) {
        return new ChecklistResponse(
                checklist.getId(),
                checklist.getProperty().getId(),
                checklist.getTemplateVersion(),
                checklist.getStatus(),
                checklist.getItems().stream().map(ChecklistItemResponse::from).toList()
        );
    }
}
```

- [ ] **Step 3: `ChecklistService.createChecklist`가 `Property`를 조회해서 넘기도록 변경**

`Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`의 import에 추가:

```java
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.repository.PropertyRepository;
```

필드에 추가 (`userRepository` 다음 줄):

```java
    private final PropertyRepository propertyRepository;
```

`createChecklist` 메서드를 다음으로 교체:

```java
    private Checklist createChecklist(Long userId, Long propertyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));

        List<ChecklistItemTemplate> templates = checklistItemTemplateRepository.findByActiveTrueOrderByDisplayOrderAsc();
        int templateVersion = templates.isEmpty() ? 0 : templates.get(0).getVersion();

        Checklist checklist = Checklist.createFrom(user, property, templateVersion, templates);
        return checklistRepository.save(checklist);
    }
```

(`createOrGetChecklist`/`getChecklist`/`updateChecklistItem`/`getChecklistResult` 나머지 메서드는 그대로 둔다 — `findByUserIdAndPropertyId` 호출부는 안 바뀐다, 위 "계획 작성 중 확인한 구현 디테일" 참고.)

- [ ] **Step 4: `ChecklistTest`를 새 시그니처에 맞게 수정**

`Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/entity/ChecklistTest.java`에 import 추가:

```java
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import org.springframework.test.util.ReflectionTestUtils;
```

`testUser()` 메서드 다음에 헬퍼 추가:

```java
    private Property testProperty(Long id) {
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }
```

파일 안의 `Checklist.createFrom(user, 10L, 1, templates)` 6곳을 모두 `Checklist.createFrom(user, testProperty(10L), 1, templates)`로 교체.

`createFromCopiesTemplatesIntoItems` 테스트의 다음 줄:

```java
        assertThat(checklist.getPropertyId()).isEqualTo(10L);
```

을 다음으로 교체:

```java
        assertThat(checklist.getProperty().getId()).isEqualTo(10L);
```

- [ ] **Step 5: `ChecklistServiceTest`를 새 시그니처 + `PropertyRepository` 목(mock)에 맞게 수정**

`Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java`에 import 추가:

```java
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
import com.algogyeyak.property.repository.PropertyRepository;
```

필드/생성자 선언을 다음으로 교체:

```java
    private final ChecklistRepository checklistRepository = mock(ChecklistRepository.class);
    private final ChecklistItemTemplateRepository templateRepository = mock(ChecklistItemTemplateRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PropertyRepository propertyRepository = mock(PropertyRepository.class);
    private final ChecklistService checklistService =
            new ChecklistService(checklistRepository, templateRepository, userRepository, propertyRepository);
```

`user(Long id)` 헬퍼 다음에 추가:

```java
    private Property property(Long id, Long ownerId) {
        Property property = Property.builder()
                .userId(ownerId)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", id);
        return property;
    }
```

`returnsExistingChecklistWithoutCreatingNew` 테스트의 첫 줄을 교체:

```java
        Checklist existing = Checklist.createFrom(user(1L), property(10L, 1L), 1, List.of());
```

`createsNewChecklistFromActiveTemplatesWhenNoneExists` 테스트에 매물 조회 스텁 추가 (`when(userRepository.findById(1L))...` 다음 줄에):

```java
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 1L)));
```

`checklistWithOneCheckItem(User user)` 헬퍼의 마지막 줄을 교체:

```java
        return Checklist.createFrom(user, property(10L, 1L), 1, List.of(template));
```

- [ ] **Step 6: `ChecklistControllerTest`를 새 시그니처에 맞게 수정**

`Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java`에 import 추가:

```java
import com.algogyeyak.property.entity.Property;
import com.algogyeyak.property.entity.PropertyType;
import com.algogyeyak.property.entity.TransactionType;
```

`createChecklistReturnsChecklistForAuthenticatedUser`와 `getChecklistReturnsChecklistForAuthenticatedUser` 두 테스트에 공통으로 있는 다음 줄:

```java
        Checklist checklist = Checklist.createFrom(user, 10L, 1, List.of(template));
```

를 각각 다음으로 교체 (두 테스트 모두 바로 위에 있는 `template` 변수 선언 다음 줄에 추가):

```java
        Property property = Property.builder()
                .userId(1L)
                .propertyType(PropertyType.OFFICETEL)
                .transactionType(TransactionType.JEONSE)
                .deposit(10_000_000L)
                .area(20.0)
                .build();
        ReflectionTestUtils.setField(property, "id", 10L);
        Checklist checklist = Checklist.createFrom(user, property, 1, List.of(template));
```

- [ ] **Step 7: 테스트 실행해서 전부 통과하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.*"`
Expected: BUILD SUCCESSFUL (전부 PASS)

- [ ] **Step 8: 여기서 멈추고 사용자에게 알린다**

변경 요약을 알리고 사용자가 직접 `git add`/`git commit`을 진행한다. 사용자가 "커밋 완료"라고 확인해줄 때까지 Task 2로 넘어가지 않는다.

---

### Task 2: 체크리스트 생성 시 매물 존재/소유권 검증 추가 (TDD)

**Files:**
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistServiceTest.java`의 `createsNewChecklistFromActiveTemplatesWhenNoneExists` 테스트 다음에 3개 테스트 추가:

```java
    @Test
    @DisplayName("존재하지 않는 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void createChecklistThrowsWhenPropertyNotFound() {
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("삭제된 매물이면 PROPERTY_NOT_FOUND 예외가 발생한다")
    void createChecklistThrowsWhenPropertyDeleted() {
        Property deletedProperty = property(10L, 1L);
        deletedProperty.delete();
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(deletedProperty));

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 매물이면 PROPERTY_ACCESS_DENIED 예외가 발생한다")
    void createChecklistThrowsWhenNotPropertyOwner() {
        when(checklistRepository.findByUserIdAndPropertyId(1L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(propertyRepository.findById(10L)).thenReturn(Optional.of(property(10L, 999L)));

        assertThatThrownBy(() -> checklistService.createOrGetChecklist(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.PROPERTY_ACCESS_DENIED)
                );
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: `createChecklistThrowsWhenPropertyDeleted`, `createChecklistThrowsWhenNotPropertyOwner` FAIL (삭제/소유권 검증이 아직 없어서 정상 생성됨). `createChecklistThrowsWhenPropertyNotFound`는 Step 3 이전 상태(Task 1에서 이미 존재 검증을 추가했음)라 이미 PASS일 수 있음 — 그래도 나머지 2개가 실패하는지 확인.

- [ ] **Step 3: 최소 구현 추가**

`ChecklistService.java`의 `createChecklist` 메서드 안, `propertyRepository.findById(...)` 다음 줄에 추가:

```java
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROPERTY_NOT_FOUND));
        if (property.isDeleted()) {
            throw new BusinessException(ErrorCode.PROPERTY_NOT_FOUND);
        }
        if (!property.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.PROPERTY_ACCESS_DENIED);
        }
```

(기존 `Property property = propertyRepository.findById(propertyId).orElseThrow(...)` 한 줄을 위 블록으로 교체.)

- [ ] **Step 4: 테스트 실행해서 전부 통과하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: BUILD SUCCESSFUL (전부 PASS)

- [ ] **Step 5: 여기서 멈추고 사용자에게 알린다**

사용자가 "커밋 완료"라고 확인해줄 때까지 Task 3으로 넘어가지 않는다.

---

### Task 3: `GET /checklists` — 리포지토리 + DTO + 서비스 (TDD)

**Files:**
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/repository/ChecklistRepository.java`
- Create: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/dto/ChecklistOverviewResponse.java`
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java`

- [ ] **Step 1: `ChecklistRepository`에 유저 전체 조회 메서드 추가**

`Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/repository/ChecklistRepository.java` 전체를 다음으로 교체:

```java
package com.algogyeyak.checklist.repository;

import com.algogyeyak.checklist.entity.Checklist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistRepository extends JpaRepository<Checklist, Long> {

    // 유저-매물 조합당 활성 체크리스트는 1개뿐이므로, 생성 요청이 멱등인지 확인할 때 사용한다.
    Optional<Checklist> findByUserIdAndPropertyId(Long userId, Long propertyId);

    // "내 체크리스트 목록"(GET /checklists) 조회에서, 유저의 매물 목록과 매칭하기 위해 한 번에 가져온다.
    List<Checklist> findAllByUserId(Long userId);
}
```

- [ ] **Step 2: 실패하는 서비스 테스트 작성**

`ChecklistServiceTest.java`에 (Task 2에서 추가한 3개 테스트 다음에) 추가:

```java
    @Test
    @DisplayName("매물마다 체크리스트 유무에 따라 상태를 매칭해 목록을 반환한다")
    void listMyChecklistsMatchesEachPropertyWithItsChecklist() {
        Property started = property(10L, 1L);
        Property notStarted = property(20L, 1L);
        when(propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(1L, PropertyStatus.ACTIVE))
                .thenReturn(List.of(started, notStarted));

        Checklist checklist = checklistWithOneCheckItem(user(1L));
        ReflectionTestUtils.setField(checklist, "id", 100L);
        checklist.getItems().get(0).check(true);
        checklist.refreshStatus();
        when(checklistRepository.findAllByUserId(1L)).thenReturn(List.of(checklist));

        List<ChecklistOverviewResponse> result = checklistService.listMyChecklists(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).propertyId()).isEqualTo(10L);
        assertThat(result.get(0).checklistId()).isEqualTo(100L);
        assertThat(result.get(0).status()).isEqualTo(ChecklistStatus.COMPLETED);
        assertThat(result.get(1).propertyId()).isEqualTo(20L);
        assertThat(result.get(1).checklistId()).isNull();
        assertThat(result.get(1).status()).isEqualTo(ChecklistStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("매물이 하나도 없으면 빈 목록을 반환한다")
    void listMyChecklistsReturnsEmptyListWhenNoProperties() {
        when(propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(1L, PropertyStatus.ACTIVE))
                .thenReturn(List.of());
        when(checklistRepository.findAllByUserId(1L)).thenReturn(List.of());

        List<ChecklistOverviewResponse> result = checklistService.listMyChecklists(1L);

        assertThat(result).isEmpty();
    }
```

import 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.property.entity.PropertyStatus;
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: FAIL — `ChecklistOverviewResponse`/`listMyChecklists`가 아직 없어서 컴파일 에러.

- [ ] **Step 4: `ChecklistOverviewResponse` DTO 작성**

`Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/dto/ChecklistOverviewResponse.java` 새로 생성:

```java
package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.Checklist;
import com.algogyeyak.checklist.entity.ChecklistStatus;
import com.algogyeyak.property.entity.Property;

/**
 * "내 체크리스트 목록"(GET /checklists) 응답 원소 하나. 매물 하나당 정확히 1개씩 나오며,
 * 아직 체크리스트를 시작 안 한 매물은 checklistId=null, status=NOT_STARTED로 채워진다.
 */
public record ChecklistOverviewResponse(
        Long propertyId,
        Long checklistId,
        String roadAddress,
        String jibunAddress,
        String propertyType,
        String transactionType,
        ChecklistStatus status
) {
    public static ChecklistOverviewResponse from(Property property, Checklist checklist) {
        var address = property.getAddress();
        return new ChecklistOverviewResponse(
                property.getId(),
                checklist != null ? checklist.getId() : null,
                address != null ? address.getRoadAddress() : null,
                address != null ? address.getJibunAddress() : null,
                property.getPropertyType().name(),
                property.getTransactionType().name(),
                checklist != null ? checklist.getStatus() : ChecklistStatus.NOT_STARTED
        );
    }
}
```

- [ ] **Step 5: `ChecklistService.listMyChecklists` 구현**

`ChecklistService.java` import에 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import com.algogyeyak.property.entity.PropertyStatus;
import java.util.stream.Collectors;
import java.util.Map;
```

클래스 맨 끝(`getChecklistResult` 메서드 다음)에 추가:

```java
    /**
     * 로그인 유저의 매물 전체 + 매물별 체크리스트 현황을 반환한다. 체크리스트를 아직 시작 안 한
     * 매물도 포함되며(checklistId=null, status=NOT_STARTED), 삭제된 매물은 제외한다.
     */
    public List<ChecklistOverviewResponse> listMyChecklists(Long userId) {
        List<Property> properties = propertyRepository.findAllByUserIdAndStatusOrderByCreatedAtDesc(userId, PropertyStatus.ACTIVE);
        Map<Long, Checklist> checklistsByPropertyId = checklistRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toMap(checklist -> checklist.getProperty().getId(), checklist -> checklist));

        return properties.stream()
                .map(property -> ChecklistOverviewResponse.from(property, checklistsByPropertyId.get(property.getId())))
                .toList();
    }
```

- [ ] **Step 6: 테스트 실행해서 전부 통과하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: BUILD SUCCESSFUL (전부 PASS)

- [ ] **Step 7: 여기서 멈추고 사용자에게 알린다**

사용자가 "커밋 완료"라고 확인해줄 때까지 Task 4로 넘어가지 않는다.

---

### Task 4: `GET /checklists` — 컨트롤러 (TDD)

**Files:**
- Modify: `Backend/algogyeyak/src/main/java/com/algogyeyak/checklist/controller/ChecklistController.java`
- Modify: `Backend/algogyeyak/src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java`

- [ ] **Step 1: 실패하는 컨트롤러 테스트 작성**

`ChecklistControllerTest.java`의 마지막 테스트(`getChecklistRejectsRequestWithoutToken`) 다음에 추가:

```java
    @Test
    @DisplayName("인증된 사용자가 내 체크리스트 목록을 조회하면 매물별 현황을 반환한다")
    void listMyChecklistsReturnsOverviewForAuthenticatedUser() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);
        ChecklistOverviewResponse started = new ChecklistOverviewResponse(
                10L, 100L, "서울특별시 강남구 테헤란로 123", null, "OFFICETEL", "JEONSE", ChecklistStatus.IN_PROGRESS
        );
        ChecklistOverviewResponse notStarted = new ChecklistOverviewResponse(
                20L, null, "서울특별시 마포구 월드컵로 1", null, "MULTI_FAMILY", "MONTHLY_RENT", ChecklistStatus.NOT_STARTED
        );
        when(checklistService.listMyChecklists(1L)).thenReturn(List.of(started, notStarted));

        mockMvc.perform(get("/checklists")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].propertyId").value(10))
                .andExpect(jsonPath("$.data[0].checklistId").value(100))
                .andExpect(jsonPath("$.data[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data[1].propertyId").value(20))
                .andExpect(jsonPath("$.data[1].checklistId").doesNotExist())
                .andExpect(jsonPath("$.data[1].status").value("NOT_STARTED"));
    }

    @Test
    @DisplayName("인증 토큰 없이 내 체크리스트 목록을 조회하면 401을 반환한다")
    void listMyChecklistsRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/checklists"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
```

import 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.controller.ChecklistControllerTest"`
Expected: FAIL — `GET /checklists` 라우트가 없어서 404.

- [ ] **Step 3: 컨트롤러에 엔드포인트 추가**

`ChecklistController.java` import에 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistOverviewResponse;
import java.util.List;
```

클래스 맨 끝(`getChecklistResult` 메서드 다음)에 추가:

```java
    /**
     * 내 매물 전체 + 매물별 체크리스트 현황을 조회한다.
     */
    @GetMapping("/checklists")
    public ApiResponse<List<ChecklistOverviewResponse>> listMyChecklists(
            @AuthenticationPrincipal JwtUserPrincipal userDetails
    ) {
        return ApiResponse.success(checklistService.listMyChecklists(userDetails.userId()));
    }
```

- [ ] **Step 4: 테스트 실행해서 전부 통과하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.*"`
Expected: BUILD SUCCESSFUL (전부 PASS)

- [ ] **Step 5: 전체 테스트 스위트 실행 (다른 도메인에 영향 없는지 확인)**

Run: `./gradlew.bat test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 여기서 멈추고 사용자에게 알린다**

사용자가 "커밋 완료"라고 확인해주면 이 플랜은 종료. (Frontend 플랜은 별도 문서: `Frontend/docs/superpowers/plans/2026-07-27-checklist-overview-list.md`)
