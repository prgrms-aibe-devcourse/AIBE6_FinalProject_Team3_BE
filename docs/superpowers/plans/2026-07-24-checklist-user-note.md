# 체크리스트 CHECK 항목 "완료/미흡 + 메모" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CHECK 타입 체크리스트 문항에 "완료/미흡 + 선택 메모" 상호작용을 추가하고, 그 전제조건인 `PATCH /checklists/{checklistId}/items/{itemId}` 엔드포인트를 새로 구현한다.

**Architecture:** 엔티티 레벨(`ChecklistItem`/`Checklist`)에 상태 전이 로직을 두고, 서비스가 소유권 검증 후 위임, 컨트롤러는 얇게 유지한다. 응답 DTO 조립 시점에 "서버 자동 감지 issueFound"와 "사용자 미흡 표시"를 합쳐서 클라이언트에는 하나의 신호로 내려준다.

**Tech Stack:** Spring Boot 4.1.0, Java 21, JPA, JUnit 5 + AssertJ + Mockito (기존 컨벤션 그대로).

**참고 문서:** [`docs/superpowers/specs/2026-07-24-checklist-user-note-design.md`](../specs/2026-07-24-checklist-user-note-design.md)

---

## File Structure

| 파일 | 작업 |
|---|---|
| `src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java` | 수정 — `userNote` 필드, `markInsufficient()`, `check()`/`markAnswered()` 개선 |
| `src/main/java/com/algogyeyak/checklist/entity/Checklist.java` | 수정 — `refreshStatus()` 추가 |
| `src/main/java/com/algogyeyak/checklist/dto/ChecklistItemResponse.java` | 수정 — `userNote` 필드, `issueFound` 계산 로직 |
| `src/main/java/com/algogyeyak/checklist/dto/ChecklistItemUpdateRequest.java` | 신규 |
| `src/main/java/com/algogyeyak/checklist/service/ChecklistService.java` | 수정 — `updateChecklistItem()` 추가 |
| `src/main/java/com/algogyeyak/checklist/controller/ChecklistController.java` | 수정 — PATCH 엔드포인트 추가 |
| `src/test/java/com/algogyeyak/checklist/entity/ChecklistItemTest.java` | 수정 — 신규 테스트 추가 |
| `src/test/java/com/algogyeyak/checklist/entity/ChecklistTest.java` | 수정 — `refreshStatus()` 테스트 추가 |
| `src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java` | 수정 — `updateChecklistItem()` 테스트 추가 |
| `src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java` | 수정 — PATCH 테스트 추가 |

모든 경로는 `Backend/algogyeyak/`에서부터의 상대 경로다. 명령어는 이 디렉터리에서 실행한다.

---

### Task 1: `issueFound` 누적 버그 수정

기존 `ChecklistItem.markAnswered()`가 `this.issueFound = this.issueFound || issueFound`로 OR 누적을 하고 있어서, 한 번 `issueFound = true`가 되면 사용자가 답을 정정해도 절대 `false`로 못 돌아간다. 이번에 PATCH 엔드포인트를 새로 여는 시점이라 이 버그가 바로 사용자에게 노출되므로 먼저 고친다.

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java`
- Test: `src/test/java/com/algogyeyak/checklist/entity/ChecklistItemTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistItemTest.java`의 `trustRegistrationYesMarksIssueFound()` 테스트 아래에 추가:

```java
    @Test
    @DisplayName("신탁등기 여부를 Y로 답했다가 N으로 정정하면 주의 항목 표시가 풀린다")
    void correctingAnswerFromYesToNoClearsIssueFound() {
        ChecklistItem item = yesNoItem(ChecklistItemCode.TRUST_REGISTRATION);

        item.answer("Y");
        item.answer("N");

        assertThat(item.isIssueFound()).isFalse();
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistItemTest.correctingAnswerFromYesToNoClearsIssueFound"`
Expected: FAIL — `item.isIssueFound()`가 `true`로 남아있어서 `assertThat(...).isFalse()`에서 실패

- [ ] **Step 3: 최소 구현으로 수정**

`src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java`의 `markAnswered` 메서드(153~157번째 줄 부근)를 교체:

```java
    private void markAnswered(String rawValue, boolean issueFound) {
        this.value = rawValue;
        this.checked = true;
        this.issueFound = issueFound;
    }
```

(기존 `this.issueFound = this.issueFound || issueFound;`를 `this.issueFound = issueFound;`로 — 매번 새로 계산해서 덮어쓴다.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistItemTest"`
Expected: PASS — 새 테스트 포함 기존 테스트 전부 통과 (기존 테스트들은 정정 없이 한 번만 답하는 케이스라 이 변경으로 깨지지 않는다)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java src/test/java/com/algogyeyak/checklist/entity/ChecklistItemTest.java
git commit -m "fix: 체크리스트 답변 정정 시 issueFound가 계속 true로 남는 버그 수정"
```

---

### Task 2: `ChecklistItem`에 `userNote` 필드와 완료/미흡 전이 추가

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java`
- Test: `src/test/java/com/algogyeyak/checklist/entity/ChecklistItemTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistItemTest.java` 맨 아래(마지막 `}` 앞)에 추가:

```java
    @Test
    @DisplayName("CHECK 항목에 markInsufficient()를 호출하면 확인 상태가 되고 메모가 저장된다")
    void markInsufficientSavesNoteAndMarksChecked() {
        ChecklistItem item = checkTypeItem();

        item.markInsufficient("환기구가 막혀있었어요");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isEqualTo("환기구가 막혀있었어요");
    }

    @Test
    @DisplayName("빈 문자열 메모도 허용된다(미흡 표시만, 메모 내용 없음)")
    void markInsufficientAllowsEmptyNote() {
        ChecklistItem item = checkTypeItem();

        item.markInsufficient("");

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isEqualTo("");
    }

    @Test
    @DisplayName("CHECK가 아닌 항목에 markInsufficient()를 호출하면 INVALID_INPUT 예외가 발생한다")
    void markInsufficientRejectsNonCheckType() {
        ChecklistItem item = yesNoItem(null);

        assertThatThrownBy(() -> item.markInsufficient("메모"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT)
                );
    }

    @Test
    @DisplayName("미흡 표시 후 check(true)로 완료 처리하면 메모가 지워진다")
    void completingAfterInsufficientClearsNote() {
        ChecklistItem item = checkTypeItem();
        item.markInsufficient("확인 필요");

        item.check(true);

        assertThat(item.isChecked()).isTrue();
        assertThat(item.getUserNote()).isNull();
    }

    @Test
    @DisplayName("check(false)로 미확인 처리하면 메모도 함께 지워진다")
    void uncheckingClearsNote() {
        ChecklistItem item = checkTypeItem();
        item.markInsufficient("확인 필요");

        item.check(false);

        assertThat(item.isChecked()).isFalse();
        assertThat(item.getUserNote()).isNull();
    }

    @Test
    @DisplayName("CHECK가 아닌 항목에 check()를 호출하면 INVALID_INPUT 예외가 발생한다")
    void checkRejectsNonCheckType() {
        ChecklistItem item = yesNoItem(null);

        assertThatThrownBy(() -> item.check(true))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT)
                );
    }
```

- [ ] **Step 2: 테스트가 실패하는지(컴파일도 안 되는 상태) 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistItemTest"`
Expected: 컴파일 실패 — `markInsufficient()`, `getUserNote()` 메서드가 아직 없음

- [ ] **Step 3: 최소 구현**

`src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java`에서 `value` 필드 선언(76~78번째 줄 부근) 바로 아래에 필드 추가:

```java
    @Column(name = "user_note")
    private String userNote;
```

`check(boolean checked)` 메서드(106~108번째 줄 부근)를 교체:

```java
    /**
     * CHECK 타입 문항의 확인 여부를 바꾼다. 완료(true)든 미확인(false)이든, 이전에 남아있던
     * 사용자 메모(userNote)는 의미가 없어지므로 함께 지운다.
     */
    public void check(boolean checked) {
        if (itemType != ChecklistItemType.CHECK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 항목은 확인 방식이 아닙니다.");
        }
        this.checked = checked;
        this.userNote = null;
    }

    /**
     * CHECK 타입 문항을 "미흡"으로 표시하고 메모를 남긴다. 메모는 빈 문자열도 허용한다
     * (미흡 표시만 하고 내용은 나중에 채우는 경우).
     */
    public void markInsufficient(String note) {
        if (itemType != ChecklistItemType.CHECK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 항목은 메모를 남길 수 있는 방식이 아닙니다.");
        }
        this.checked = true;
        this.userNote = note;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistItemTest"`
Expected: PASS — 전체 테스트 통과. `getUserNote()`는 클래스에 이미 있는 `@Getter`(Lombok)가 자동 생성하므로 별도 작성 불필요.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/entity/ChecklistItem.java src/test/java/com/algogyeyak/checklist/entity/ChecklistItemTest.java
git commit -m "feat: CHECK 항목에 완료/미흡(메모) 전이 추가"
```

---

### Task 3: `Checklist.refreshStatus()` 추가

원 스펙(`2026-07-23-checklist-design.md`)에 정의돼 있었지만 PATCH가 없어 미구현 상태였던 상태 재계산 로직. `ChecklistStatus` enum의 기존 주석이 이미 `Checklist#refreshStatus()`를 언급하고 있다.

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/entity/Checklist.java`
- Test: `src/test/java/com/algogyeyak/checklist/entity/ChecklistTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistTest.java` 맨 아래(마지막 `}` 앞)에 추가:

```java
    @Test
    @DisplayName("refreshStatus()는 체크된 항목이 없으면 NOT_STARTED로 유지한다")
    void refreshStatusStaysNotStartedWithNoCheckedItems() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.INDOOR, "누수 확인", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 1)
        );
        Checklist checklist = Checklist.createFrom(user, 10L, 1, templates);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.NOT_STARTED);
    }

    @Test
    @DisplayName("refreshStatus()는 REQUIRED 항목을 모두 체크하면 COMPLETED가 된다")
    void refreshStatusBecomesCompletedWhenAllRequiredChecked() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.INDOOR, "누수 확인(일반)", ChecklistImportance.GENERAL, ChecklistItemType.CHECK, null, 2)
        );
        Checklist checklist = Checklist.createFrom(user, 10L, 1, templates);
        checklist.getItems().get(0).check(true);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.COMPLETED);
    }

    @Test
    @DisplayName("refreshStatus()는 REQUIRED 항목이 남아있으면 IN_PROGRESS가 된다")
    void refreshStatusBecomesInProgressWhenSomeRequiredRemain() {
        User user = testUser();
        List<ChecklistItemTemplate> templates = List.of(
                template(ChecklistCategory.DOCUMENTS, "등기부등본 확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 1),
                template(ChecklistCategory.DOCUMENTS, "계약조건 재확인", ChecklistImportance.REQUIRED, ChecklistItemType.CHECK, null, 2)
        );
        Checklist checklist = Checklist.createFrom(user, 10L, 1, templates);
        checklist.getItems().get(0).check(true);

        checklist.refreshStatus();

        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistTest"`
Expected: 컴파일 실패 — `refreshStatus()` 메서드가 아직 없음

- [ ] **Step 3: 최소 구현**

`src/main/java/com/algogyeyak/checklist/entity/Checklist.java`의 `createFrom` 정적 메서드 아래에 메서드 추가:

```java
    /**
     * 문항 상태가 바뀔 때마다 호출해 전체 진행 상태를 다시 계산한다.
     * 체크된 항목이 하나도 없으면 NOT_STARTED, 필수(REQUIRED) 항목을 모두 체크했으면 COMPLETED,
     * 그 외에는 IN_PROGRESS.
     */
    public void refreshStatus() {
        boolean noneChecked = items.stream().noneMatch(ChecklistItem::isChecked);
        if (noneChecked) {
            this.status = ChecklistStatus.NOT_STARTED;
            return;
        }

        boolean allRequiredChecked = items.stream()
                .filter(item -> item.getImportance() == ChecklistImportance.REQUIRED)
                .allMatch(ChecklistItem::isChecked);
        this.status = allRequiredChecked ? ChecklistStatus.COMPLETED : ChecklistStatus.IN_PROGRESS;
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.entity.ChecklistTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/entity/Checklist.java src/test/java/com/algogyeyak/checklist/entity/ChecklistTest.java
git commit -m "feat: Checklist.refreshStatus() 구현"
```

---

### Task 4: `ChecklistItemResponse`에 `userNote` 반영

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/dto/ChecklistItemResponse.java`

이 DTO는 현재 별도 단위 테스트 파일이 없고 `ChecklistControllerTest`를 통해 간접 검증된다 — Task 7에서 함께 검증한다. 여기서는 구현만 한다.

- [ ] **Step 1: 구현**

`src/main/java/com/algogyeyak/checklist/dto/ChecklistItemResponse.java` 전체를 아래로 교체:

```java
package com.algogyeyak.checklist.dto;

import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistImportance;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemType;

public record ChecklistItemResponse(
        Long id,
        ChecklistCategory category,
        String content,
        String guideText,
        ChecklistImportance importance,
        ChecklistItemType itemType,
        boolean checked,
        boolean issueFound,
        String value,
        String userNote
) {
    public static ChecklistItemResponse from(ChecklistItem item) {
        return new ChecklistItemResponse(
                item.getId(),
                item.getCategory(),
                item.getContent(),
                item.getGuideText(),
                item.getImportance(),
                item.getItemType(),
                item.isChecked(),
                item.isIssueFound() || item.getUserNote() != null,
                item.getValue(),
                item.getUserNote()
        );
    }
}
```

(변경점: `userNote` 필드 추가, `issueFound`를 `item.isIssueFound() || item.getUserNote() != null`로 계산 — 서버 자동 감지와 사용자 미흡 표시를 응답에서 합친다. 엔티티의 `issueFound` 컬럼 자체는 자동 감지 결과만 담고 있어 그대로 둔다.)

- [ ] **Step 2: 타입 체크 겸 빌드**

Run: `./gradlew.bat compileJava`
Expected: 성공 (아직 이 레코드를 생성하는 기존 코드가 없으므로 컴파일만 확인. `ChecklistController`/`ChecklistService`에서 실제로 쓰이는 건 Task 6-7)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/dto/ChecklistItemResponse.java
git commit -m "feat: ChecklistItemResponse에 userNote 필드와 합산된 issueFound 반영"
```

---

### Task 5: `ChecklistItemUpdateRequest` DTO 신규 생성

**Files:**
- Create: `src/main/java/com/algogyeyak/checklist/dto/ChecklistItemUpdateRequest.java`

- [ ] **Step 1: 구현**

```java
package com.algogyeyak.checklist.dto;

/**
 * PATCH /checklists/{checklistId}/items/{itemId} 요청 바디.
 * checked/value/userNote 중 정확히 하나만 채워서 보낸다 (discriminated union).
 * - checked: CHECK 타입의 완료/미확인 전환
 * - value: YES_NO/DATE/DOCUMENT_REQUEST 답변
 * - userNote: CHECK 타입의 "미흡" 표시 + 메모 (빈 문자열 허용)
 */
public record ChecklistItemUpdateRequest(
        Boolean checked,
        String value,
        String userNote
) {
}
```

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew.bat compileJava`
Expected: 성공

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/dto/ChecklistItemUpdateRequest.java
git commit -m "feat: 체크리스트 항목 PATCH 요청 DTO 추가"
```

---

### Task 6: `ChecklistService.updateChecklistItem()` 구현

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`
- Test: `src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistServiceTest.java` 맨 아래(마지막 `}` 앞)에 추가. 기존 파일 상단 import에 아래를 추가해야 한다(이미 있는 것은 중복 추가하지 말 것):

```java
import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistCategory;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.algogyeyak.checklist.entity.ChecklistItemType;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

테스트 메서드:

```java
    private Checklist checklistWithOneCheckItem(User user) {
        ChecklistItemTemplate template = ChecklistItemTemplate.builder()
                .version(1)
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .active(true)
                .build();
        return Checklist.createFrom(user, 10L, 1, List.of(template));
    }

    @Test
    @DisplayName("userNote 요청을 보내면 항목이 미흡으로 표시되고 메모가 저장된다")
    void updateChecklistItemMarksInsufficientWithNote() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        ChecklistItem result = checklistService.updateChecklistItem(
                1L, 100L, 200L, new ChecklistItemUpdateRequest(null, null, "환기구 막힘")
        );

        assertThat(result.isChecked()).isTrue();
        assertThat(result.getUserNote()).isEqualTo("환기구 막힘");
        assertThat(checklist.getStatus()).isEqualTo(ChecklistStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("checked=true 요청을 보내면 완료로 전환되고 메모는 지워진다")
    void updateChecklistItemCompletesAndClearsNote() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        item.markInsufficient("이전 메모");
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        ChecklistItem result = checklistService.updateChecklistItem(
                1L, 100L, 200L, new ChecklistItemUpdateRequest(true, null, null)
        );

        assertThat(result.isChecked()).isTrue();
        assertThat(result.getUserNote()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 체크리스트면 NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenChecklistNotFound() {
        when(checklistRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 999L, 1L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    @DisplayName("본인 소유가 아닌 체크리스트면 FORBIDDEN 예외가 발생한다")
    void updateChecklistItemThrowsWhenNotOwner() {
        User owner = user(1L);
        Checklist checklist = checklistWithOneCheckItem(owner);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(999L, 100L, 1L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
                );
    }

    @Test
    @DisplayName("존재하지 않는 항목이면 NOT_FOUND 예외가 발생한다")
    void updateChecklistItemThrowsWhenItemNotFound() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 100L, 999L, new ChecklistItemUpdateRequest(true, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND)
                );
    }

    @Test
    @DisplayName("checked/value/userNote가 전부 비어있으면 INVALID_INPUT 예외가 발생한다")
    void updateChecklistItemThrowsWhenNoFieldProvided() {
        User user = user(1L);
        Checklist checklist = checklistWithOneCheckItem(user);
        ReflectionTestUtils.setField(checklist, "id", 100L);
        ChecklistItem item = checklist.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", 200L);
        when(checklistRepository.findById(100L)).thenReturn(Optional.of(checklist));

        assertThatThrownBy(() ->
                checklistService.updateChecklistItem(1L, 100L, 200L, new ChecklistItemUpdateRequest(null, null, null))
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT)
                );
    }
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: 컴파일 실패 — `updateChecklistItem()` 메서드가 아직 없음

- [ ] **Step 3: 최소 구현**

`src/main/java/com/algogyeyak/checklist/service/ChecklistService.java`에서 `createChecklist` private 메서드 아래에 추가 (필요한 import는 파일 상단에 함께 추가: `com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest`, `com.algogyeyak.checklist.entity.ChecklistItem`):

```java
    /**
     * 체크리스트 항목 하나를 갱신한다. checked/value/userNote 중 요청에 채워진 필드에 따라
     * 알맞은 엔티티 메서드로 위임하고, 갱신 후 체크리스트 전체 진행 상태를 재계산한다.
     */
    @Transactional
    public ChecklistItem updateChecklistItem(Long userId, Long checklistId, Long itemId, ChecklistItemUpdateRequest request) {
        Checklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "체크리스트를 찾을 수 없습니다."));

        if (!checklist.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 체크리스트만 수정할 수 있습니다.");
        }

        ChecklistItem item = checklist.getItems().stream()
                .filter(candidate -> candidate.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "체크리스트 항목을 찾을 수 없습니다."));

        if (request.checked() != null) {
            item.check(request.checked());
        } else if (request.value() != null) {
            item.answer(request.value());
        } else if (request.userNote() != null) {
            item.markInsufficient(request.userNote());
        } else {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "변경할 값을 하나 이상 보내야 합니다.");
        }

        checklist.refreshStatus();

        return item;
    }
```

클래스 상단의 `@Transactional(readOnly = true)`는 클래스 레벨에 그대로 두고, 이 메서드에만 메서드 레벨 `@Transactional`을 붙인다 (기존 `createOrGetChecklist`와 동일한 패턴).

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.service.ChecklistServiceTest"`
Expected: PASS — 신규 6개 + 기존 2개 전부 통과

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/service/ChecklistService.java src/test/java/com/algogyeyak/checklist/service/ChecklistServiceTest.java
git commit -m "feat: ChecklistService.updateChecklistItem() 구현"
```

---

### Task 7: `ChecklistController`에 PATCH 엔드포인트 추가

**Files:**
- Modify: `src/main/java/com/algogyeyak/checklist/controller/ChecklistController.java`
- Test: `src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`ChecklistControllerTest.java` 상단 import에 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
```

(`ObjectMapper`, 두 번째 `@Autowired`는 이미 클래스에 있을 수 있으니 중복되지 않게 확인 후 추가. 기존 파일에 `@Autowired private MockMvc mockMvc;`, `@Autowired private JwtProvider jwtProvider;`가 이미 있다.)

클래스에 필드 추가:

```java
    @Autowired
    private ObjectMapper objectMapper;
```

테스트 메서드 (마지막 `}` 앞에 추가):

```java
    @Test
    @DisplayName("인증된 사용자가 항목을 미흡으로 표시하면 갱신된 항목을 반환한다")
    void updateChecklistItemMarksInsufficient() throws Exception {
        String token = jwtProvider.createAccessToken(1L, "test@example.com", Role.USER);

        ChecklistItem item = ChecklistItem.builder()
                .category(ChecklistCategory.INDOOR)
                .content("누수 확인")
                .importance(ChecklistImportance.GENERAL)
                .itemType(ChecklistItemType.CHECK)
                .displayOrder(1)
                .build();
        ReflectionTestUtils.setField(item, "id", 200L);
        ReflectionTestUtils.setField(item, "checked", true);
        ReflectionTestUtils.setField(item, "userNote", "환기구 막힘");

        when(checklistService.updateChecklistItem(eq(1L), eq(100L), eq(200L), any(ChecklistItemUpdateRequest.class)))
                .thenReturn(item);

        mockMvc.perform(patch("/checklists/100/items/200")
                        .cookie(new jakarta.servlet.http.Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE_NAME, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChecklistItemUpdateRequest(null, null, "환기구 막힘"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.checked").value(true))
                .andExpect(jsonPath("$.data.userNote").value("환기구 막힘"))
                .andExpect(jsonPath("$.data.issueFound").value(true));
    }

    @Test
    @DisplayName("인증 토큰 없이 항목 수정을 요청하면 401을 반환한다")
    void updateChecklistItemRejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(patch("/checklists/100/items/200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChecklistItemUpdateRequest(true, null, null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }
```

이 테스트는 `import static org.mockito.ArgumentMatchers.any;`, `import org.springframework.http.MediaType;`, `import com.algogyeyak.checklist.entity.ChecklistCategory;`, `import com.algogyeyak.checklist.entity.ChecklistImportance;` 등이 필요하다 — 기존 파일에 없는 것만 추가한다 (`eq`는 이미 import되어 있음, `ChecklistCategory`/`ChecklistImportance`도 이미 import되어 있음). `ReflectionTestUtils`는 이미 import되어 있다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.controller.ChecklistControllerTest"`
Expected: 컴파일 실패 또는 404 — PATCH 엔드포인트가 아직 없음

- [ ] **Step 3: 최소 구현**

`src/main/java/com/algogyeyak/checklist/controller/ChecklistController.java`를 다음과 같이 수정. 상단 import에 추가:

```java
import com.algogyeyak.checklist.dto.ChecklistItemResponse;
import com.algogyeyak.checklist.dto.ChecklistItemUpdateRequest;
import com.algogyeyak.checklist.entity.ChecklistItem;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

`createChecklist` 메서드 아래에 추가:

```java
    /**
     * 체크리스트 항목 하나를 갱신한다 (완료/미확인 전환, YES_NO/DATE/DOCUMENT_REQUEST 답변, 또는 CHECK 항목의 미흡+메모 표시).
     */
    @PatchMapping("/checklists/{checklistId}/items/{itemId}")
    public ApiResponse<ChecklistItemResponse> updateChecklistItem(
            @AuthenticationPrincipal JwtUserPrincipal userDetails,
            @PathVariable Long checklistId,
            @PathVariable Long itemId,
            @RequestBody ChecklistItemUpdateRequest request
    ) {
        ChecklistItem item = checklistService.updateChecklistItem(userDetails.userId(), checklistId, itemId, request);
        return ApiResponse.success(ChecklistItemResponse.from(item));
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew.bat test --tests "com.algogyeyak.checklist.controller.ChecklistControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/algogyeyak/checklist/controller/ChecklistController.java src/test/java/com/algogyeyak/checklist/controller/ChecklistControllerTest.java
git commit -m "feat: 체크리스트 항목 PATCH 엔드포인트 추가"
```

---

### Task 8: 전체 검증

**Files:** 없음 (검증 전용)

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew.bat test`
Expected: 전체 통과, 실패 0건

- [ ] **Step 2: 빌드**

Run: `./gradlew.bat build`
Expected: 성공

- [ ] **Step 3: Commit**

코드 변경이 없으므로 커밋할 것 없음 (검증만).

---

## Self-Review 메모

- **스펙 커버리지**: 설계 문서의 결정 사항 1~6 및 "구현 중 다듬은 부분"(완료=checked:true 재사용) 전부 Task 2, 6, 7에 반영됨. "검토했으나 채택하지 않은 것"(추가 컬럼)은 의도적으로 구현하지 않음 — Task 없음, 정상.
- **추가 발견**: 원 체크리스트 스펙에 있었지만 미구현이던 `Checklist.refreshStatus()`(Task 3)와 `issueFound` 누적 버그(Task 1)를 이번 PATCH 구현 시점에 함께 처리 — PATCH가 이 두 가지를 직접 건드리는 첫 진입점이라 뒤로 미루면 같은 버그를 새 코드에서 다시 마주치게 된다.
- **타입 일관성**: `ChecklistItemUpdateRequest`(checked/value/userNote) → `ChecklistService.updateChecklistItem()`의 분기 로직 → `ChecklistController`의 PATCH 핸들러까지 필드명이 동일하게 유지됨을 확인.
- **인증/권한**: 기존 `ChecklistController.createChecklist()`와 동일하게 `@AuthenticationPrincipal JwtUserPrincipal`로 인증 처리, 서비스 레벨에서 `checklist.getUser().getId()`와 비교해 소유권 검증 — 기존 컨벤션과 일치.
