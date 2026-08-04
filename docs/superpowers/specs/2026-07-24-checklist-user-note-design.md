# 체크리스트 CHECK 항목 "완료/미흡 + 메모" 설계

## 배경 / 출처

- 원 스펙: `2026-07-23-checklist-design.md` — CHECK 타입 문항은 지금 `checked`(bool) 하나만 가지고 있어서, 사용자가 "확인은 했는데 뭔가 이상하다"는 의견을 남길 방법이 없음
- FE 쪽에서 `/properties/[id]/checklist` 화면을 실제로 눌러보다가 발견: CHECK 항목이 "확인하기" 버튼 하나뿐이라 어색하다는 피드백에서 시작
- 담당: 본인 (체크리스트 도메인 BE/FE 모두)
- 상태: **확정 — 구현 계획(writing-plans) 진행 가능**

## 범위

CHECK 타입 문항에만 적용한다. YES_NO/DATE/DOCUMENT_REQUEST는 이미 자체 값(Y/N, 날짜, 제공여부)이 있어서 이번 변경 대상이 아니다.

## 결정 사항 (브레인스토밍 중 확정)

1. **CHECK 항목의 상호작용을 "완료"/"미흡" 2버튼으로 바꾼다.** 기존 "확인하기" 단일 버튼(단순 checked 토글) 대체.
2. **"미흡" 선택 시 텍스트 메모를 남길 수 있다.** 사진 첨부는 이번 스코프에서 제외 (별도 업로드/스토리지 인프라가 필요해 훨씬 큰 작업 — 나중에 필요하면 별도 스펙으로).
3. **메모는 선택 입력이다.** "미흡"을 눌러도 메모를 안 쓰고 넘어갈 수 있다.
4. **사용자가 "미흡"으로 표시한 항목은 서버가 자동 감지하는 주의 항목(`issueFound`)과 합쳐서 "주의 항목" 카운트 하나로 보여준다.** 별도 카운트로 나누지 않는다.
5. **데이터 모델**: `ChecklistItem`에 `userNote: String?`(nullable) 필드를 추가한다. `null`이면 "미흡 아님", 문자열(빈 문자열 포함)이면 "미흡 표시+ 메모". 응답의 `issueFound`는 `(서버 자동 규칙 감지) OR (userNote != null)`로 계산 — 사용자에게는 하나의 "주의 항목" 신호로만 보이지만, 내부적으로는 "서버가 판단한 객관적 사실"과 "사용자가 남긴 주관적 표시"를 계속 구분해서 저장한다 (원 스펙의 issueFound 의미를 사용자 입력으로 오염시키지 않기 위함).
6. **"완료"로 되돌리면 메모도 같이 지운다.** `userNote`를 다시 `null`로.

### 구현 중 다듬은 부분 (동작은 동일, 계약만 정리)

`{ userNote: string | null }` 하나로 완료/미흡을 다 표현하려고 했으나, JSON에서 "필드를 안 보냄"과 "명시적으로 null을 보냄"을 서버가 구분하기 까다롭다(Jackson이 record에 역직렬화할 때 두 경우가 똑같이 `null`로 들어옴). 그래서 "완료"는 기존 `{ checked: true }` 요청을 재사용하고, `userNote`는 항상 문자열(빈 문자열 허용, `null` 없음)로만 보내는 것으로 계약을 정리한다 — 사용자 경험은 동일하다.

## 검토했으나 채택하지 않은 것

브레인스토밍 중 "컬럼을 더 추가해야 하지 않을까" 하는 의문이 있었다 — 메모 작성/수정 시각, "미흡" 최초 표시 시점 구분, 메모 길이 제한(`VARCHAR(n)` vs `TEXT`) 등을 후보로 검토했으나, 지금 스코프에서는 불필요하다고 판단해 채택하지 않았다. `userNote: String?` 하나로 충분하다. 나중에 실제로 필요해지면 그때 컬럼을 추가한다 (스키마 확장으로 대응 가능).

## API 계약

PATCH `/checklists/{checklistId}/items/{itemId}` 요청은 다음 3가지 중 하나(discriminated union), 항상 이 중 정확히 하나의 필드만 보낸다:

```
{ checked: boolean }   // CHECK: 완료/미확인 전환 (완료 시 userNote도 함께 초기화)
{ value: string }      // YES_NO / DATE / DOCUMENT_REQUEST
{ userNote: string }   // CHECK: "미흡" 표시 + 메모 (빈 문자열 허용, null 없음)
```

- `userNote`는 CHECK 타입 문항에서만 유효 — 다른 itemType에 보내면 400(INVALID_INPUT)
- `{ userNote: "..." }` 처리: `checked = true`, `userNote` 저장
- `{ checked: true }` 처리(완료로 재확인): `checked = true`, `userNote = null`로 초기화 — 이전에 "미흡"이었어도 "완료"를 다시 누르면 메모가 지워진다
- `{ checked: false }` 처리: `checked = false`, `userNote = null`로 초기화 (미확인 상태로 되돌아가면 메모도 의미가 없어짐)
- 응답의 `issueFound`는 저장 시점에 계산: `엔티티의 issueFound(서버 자동 규칙) OR (userNote != null)` — DB에는 자동 감지 결과만 별도 컬럼으로 저장하고, 응답 DTO 조립 시점에 합쳐서 내려준다 (결정 사항 5)

## 남은 작업

- Backend: `ChecklistItem` 엔티티에 `userNote` 필드 추가, `ChecklistItemResponse`에 반영. **PATCH `/checklists/{checklistId}/items/{itemId}` 엔드포인트 자체가 아직 없으므로(원 스펙에서도 미구현 상태) 이번에 새로 구현한다** — checked/value/userNote 3가지 요청을 함께 처리
- FE: `ChecklistItemDto`/`ChecklistItem`(domain) 타입에 `userNote` 추가, `ChecklistClient.tsx`의 CHECK 항목 렌더링을 완료/미흡 2버튼 + 조건부 텍스트 입력으로 교체
- `superpowers:writing-plans`로 구현 계획 작성 (Backend/Frontend 각각)
