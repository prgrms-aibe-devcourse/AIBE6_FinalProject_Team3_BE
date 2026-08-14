# 임장 체크리스트(checklist) 도메인 — 구현 현황 정리

## 배경 / 성격

다른 문서(`auth-design.md`, `user-design.md`, `property-design.md`, `market-data-design.md`, `contract-analysis-design.md`, `risk-analysis-design.md`)와 같은 방식의 **회고성(retroactive) 문서**입니다. 처음 구현할 당시엔 이 공식 요구사항 명세서가 아니라 내부 브레인스토밍 결과(`docs/superpowers/specs/2026-07-23-checklist-design.md`)를 기반으로 진행했기 때문에, 처음 이 문서를 작성할 때 명세서와 실제 코드 사이에 8개의 확인 필요 항목이 나왔습니다.

**이 문서는 그 8개 항목을 전부 검토·확정한 뒤, 실제로 코드까지 반영을 완료한 시점 기준으로 다시 작성한 버전입니다.** 원래의 "확인 필요" 목록과 그 처리 결과는 아래 "해결 내역"에 정리했습니다.

**범위**: `com.algogyeyak.checklist.*`(체크리스트 생성/조회/항목 확인/결과 확인)만 다룹니다.

## 문항 구성 (템플릿 버전 3 기준)

**(2026-08-14 갱신)** 실사용 피드백(더 구체적인 확인 항목 요청)을 반영해 버전 2(24개)에서 버전 3으로 6개 문항이 새로 추가됐습니다. 매물유형별 실제 적용 문항 수는 **오피스텔 29개, 연립다세대·단독다가구 30개**로, 기존 요구사항의 "20~24개" 상한을 넘어섰습니다 — 신규 항목들을 기존 항목에 병합(예: 콘센트+누전+차단기를 하나로)하는 방안도 검토했으나, 병합하면 "어느 부분이 문제인지" 구분이 안 돼 항목 수 증가를 감수하고 명확성을 우선하기로 결정했습니다. 이에 따라 요구사항 명세서의 문항 수 상한 자체를 20~30개로 갱신하는 것으로 판단합니다(`ChecklistTemplateSeedDataTest` 참고).

| 카테고리 | 버전 2 개수 | 버전 3 개수 |
|---|---|---|
| 실내 상태 | 5개 | **10개** (단창/이중창·누전·차단기·보일러종류·냉난방방식 5개 신규) |
| 소음·환경 | 3개 | 3개 (문항 수는 그대로, 외부소음 항목 guideText만 보강) |
| 보안·안전 | 4개(매물유형별 변형 1개만 적용) | 4~5개(방범창은 연립다세대·단독다가구에만 신규 적용, 오피스텔은 그대로 4개) |
| 서류·행정 | 9개 | 9개 |
| 주변 환경 | 3개 | 3개 |

**보안·안전 잠금장치 문항 (매물유형별 분기)**
- 오피스텔·연립다세대: "공동현관과 현관문 잠금장치가 모두 정상 작동하나요?" (둘 다 있는 구조라 한 문항으로 같이 확인)
- 단독·다가구: "현관문 잠금장치가 정상 작동하나요?" (공동현관이 없을 수 있어 현관문 기준으로만 확인)

**명의 일치 문항 방향**: "등기부등본상 소유자와 임대인(계약 당사자)의 명의가 다른가요?"로 질문하고, **Y(다름=불일치)일 때 자동으로 주의 항목**이 됩니다(요구사항 명세서의 "명의 불일치(Y)" 방향과 일치).

## 체크리스트 생성 (`POST /properties/{propertyId}/checklists`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 매물 접근 권한 확인 | ✅ (매물 존재 + 소유자 확인) |
| 매물유형·거래유형에 맞는 템플릿으로 생성 | ✅ `ChecklistItemTemplate.isApplicableTo(PropertyType)`로 매물유형에 맞는 문항만 필터링(거래유형별 분기는 아직 없음 — 문항 대부분이 거래유형과 무관해 현재로선 불필요하다고 판단) |
| 기존 체크리스트 있으면 재생성하지 않고 그대로 반환 | ✅ `createOrGetChecklist` (멱등), DB에도 `(user_id, property_id)` 유니크 제약으로 이중 보장 |
| 실패: 존재하지 않는 매물 | ✅ 404 |
| 실패: 접근 권한 없음 | ✅ 403 |
| 실패: 매물 정보 부족 | 발생 불가 — 매물은 등록 시점에 이미 필수값이 검증된 뒤에만 존재할 수 있어, 체크리스트 생성 시점엔 정보 부족 상황 자체가 나올 수 없음(확인 완료) |
| 실패: 지원하지 않는 매물 유형 | 발생 불가 — 유형별로 템플릿 전체를 나누지 않고 문항 단위로만 필터링하는 구조라 "지원 안 하는 유형" 개념이 없음(확인 완료) |

## 체크리스트 조회 (`GET /properties/{propertyId}/checklists`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 접근 권한 확인 | ✅ 매물 단위로 조회한 뒤 소유자가 다르면 403, 없으면 404로 명시적으로 구분(`updateChecklistItem`/`getChecklistResult`와 동일한 패턴으로 통일) |
| 카테고리·중요도 순 정렬 | ✅ 응답 생성 시 카테고리(FE 기준 순서: 실내→소음→보안→주변→서류행정, 서류행정에 REQUIRED가 몰려있어 마지막 배치) → 중요도(REQUIRED 먼저) → displayOrder 순으로 정렬 |
| 확인/주의 항목 구분 | ✅ 응답 항목마다 `checked`, `issueFound` 필드로 구분 제공 |
| 실패: 존재하지 않는 체크리스트 | ✅ 404 |
| 실패: 접근 권한 없음 | ✅ 403 |
| 실패: 삭제된 매물에 연결된 체크리스트 | ✅ 매물 삭제 여부를 조회 시점에 재확인해 404 반환 |

## 체크리스트 항목 확인 (`PATCH /checklists/{checklistId}/items/{itemId}`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 접근 권한 확인 | ✅ 403 |
| 체크 상태(또는 Y/N·날짜값) 저장 | ✅ `check()`/`answer()`로 분기 처리 |
| 서류 미제공 시 자동 issueFound | ✅ `DOCUMENT_REQUEST` 타입에서 `NOT_PROVIDED` 응답 시 자동 반영 |
| 신탁등기 있음(Y)/명의 불일치(Y) 자동 issueFound | ✅ 둘 다 Y 기준으로 통일(명의 일치 문항 방향을 요구사항에 맞게 수정 완료) |
| 확인한 항목 수·주의 항목 수 갱신 | ✅ `refreshStatus()`가 매 변경마다 호출됨 |
| 실패: 존재하지 않는 체크리스트 항목 | ✅ 404 |
| 실패: 접근 권한 없음 | ✅ 403 |
| 실패: 잘못된 상태값 | ✅ 400 |
| 실패: 삭제된 매물 | ✅ 매물 삭제 여부를 항목 수정 시점에 재확인해 404 반환 |

## 체크리스트 결과 확인 (`GET /checklists/{checklistId}/result`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 필수 항목 중 미확인 개수 계산 | ✅ `requiredMissingCount` |
| 주의 항목 수 계산 | ✅ `issueCount` (issueFound뿐 아니라 `userNote`가 있는 경우도 포함) |
| 등급/점수 대신 개수로만 표시 | ✅ |
| 시작 전이면 "체크리스트를 시작해보세요" 안내로 대체 | ✅ `message` 필드 |
| 실패: 존재하지 않는 체크리스트 | ✅ 404 |
| 실패: 삭제된 매물에 연결된 체크리스트 | ✅ **(2026-07-30 버그 수정)** 매물 삭제 여부를 결과 조회 시점에도 재확인해 404 반환 — 원래 `getChecklist`/`updateChecklistItem`에만 있던 검사가 결과조회엔 빠져 있던 버그였음 |
| 실패: 확인한 항목 없음 | 발생시키지 않음 — 요구사항 명세서의 "시스템 처리" 설명(시작 전이면 안내로 대체, 즉 성공 처리)과 "실패 사유" 목록이 서로 모순되는데, 실제 구현은 전자(성공+안내 메시지)를 따르기로 확정 — 사용성 측면에서도 이 방식이 더 낫다고 판단(확인 완료) |
| 실패: 결과 계산 실패 | 발생 불가 — 계산 자체가 항상 성공하는 순수 함수(확인 완료) |

## 비기능 요구사항 — 대조

| 항목 | 요구사항 | 실제 |
|---|---|---|
| 매물유형·거래유형에 맞는 항목으로 생성 | O | ✅ 매물유형 필터링 반영 완료(거래유형 분기는 아직 없음) |
| 동일 조건 매물엔 동일 버전 템플릿 적용 | O | ✅ |
| 템플릿 변경 이력을 버전으로 관리 | O | ✅ 버전 1→2에 이어 **(2026-08-14)** 2→3도 반영(내용은 스냅샷 복사되므로 기존 체크리스트엔 영향 없음). 다만 버전 이력 조회 등 정식 체계는 여전히 없고 Flyway 도입 전 임시 방식(seeder) 그대로 |
| 하나의 유저-매물 조합엔 하나의 활성 체크리스트만 | O | ✅ DB 유니크 제약으로 보장 |
| 체크리스트 항목은 반드시 체크리스트에 속함 | O | ✅ |
| 삭제된 매물의 체크리스트는 수정 불가 | O | ✅ 조회·항목수정 시점 모두에서 검사하도록 수정 완료 |
| 항목은 짧고 명확하게 | O | ✅ |
| 필수/일반 항목 구분 표시 | O | ✅ `importance` 필드 |
| `안전 점수` 대신 `확인 완료도`/`주의 항목 수` | O | ✅ |
| 상태 변경 즉시 반영 | O | ✅ |
| 등기부등본 세부 항목에 안내 문구 제공 | O | ✅ `guideText` |
| 동일 상태 변경 반복돼도 중복 저장 안 됨 | O | ✅ |
| 저장 실패 시 완료로 표시 안 함 | O | ✅ |
| 항목 변경 시 전체 체크리스트 재생성 안 함 | O | ✅ |
| 조회 시 매물 불필요 상세정보 미조회 | O | ✅ |

## 요구사항에 없던 추가 구현

- **`GET /checklists`(내 체크리스트 목록)** — 본인 매물 전체와 매물별 체크리스트 진행 상태(시작 전 포함)를 한 번에 보여주기 위해 추가됨.

  - **(2026-07-30)** 응답에 `lastCheckedAt`(최종 점검일) 필드 추가 — 체크리스트가 있으면 `checklist.updatedAt`, 시작 전이면 `property.updatedAt`으로 대체. 목록 정렬 기준도 매물 등록일(`createdAt`) 최신순에서 `lastCheckedAt` 최신순으로 변경(동률이면 안정 정렬로 등록일 최신순 유지) — FE가 "최종 점검일" 표시를 요청하면서 함께 결정됨.
  - **(2026-08-06, breaking change)** 페이지네이션 추가 — 매물 목록(`GET /properties`)과 동일한 컨벤션으로 `page`/`size` 쿼리 파라미터 + `PageResponse` 봉투 응답으로 전환. 응답이 배열(`data: [...]`)에서 객체(`data: { content, page, size, totalElements, totalPages, hasNext }`)로 바뀜. 원래 "타겟 유저가 매물을 많이 등록할 일이 적어 당장 불필요"로 보류했던 항목이었으나, 다른 목록 API(`GET /properties`, 관리자 유저/신고 목록)들이 이미 전부 DB 레벨 페이지네이션을 쓰고 있어 일관성 차원에서 도입 결정됨. 정렬(`lastCheckedAt` 내림차순)은 DB 쿼리(`ChecklistRepository.findOverviewByUserId()` — Property LEFT JOIN Checklist, `COALESCE(checklist.updatedAt, property.updatedAt)` 기준)로 이동해 처리하며, 사용자가 정렬 기준을 고를 수 없어 클라이언트가 보낸 `sort` 파라미터는 무시함.
  - **(2026-08-13)** 응답에 `title`(매물 제목) 필드 추가 — 주소만으로는 목록에서 매물을 구분하기 어렵다는 이유로, 이미 `Property` 엔티티에 있던 값을 그대로 노출함(리포지토리 쿼리가 `Property` 엔티티 전체를 select하고 있어 쿼리 변경 없이 반영됨).
- **`userNote`(미흡 메모)** — CHECK 타입 항목에 "완료했지만 미흡함"을 메모와 함께 남기는 기능. `hasIssue() = issueFound || userNote != null`
- **관리비 확인 항목** — 서류·행정 카테고리에 신규 추가("관리비에 전기세·수도세 등이 포함되어 있는지 확인했나요?"). 오피스텔 등에서 관리비가 실제 임대료보다 커질 수 있다는 실용적 이유로 추가(노션 요구사항 명세서 반영 완료)
- **`helperText`(쉬운 설명)** **(2026-07-30 추가)** — `guideText`(짧은 실무 안내)와 별개로, 부동산 지식이 없는 사용자도 이해할 수 있게 초등학생 수준으로 풀어쓴 설명. `ChecklistItemTemplate`/`ChecklistItem`에 컬럼 추가, 템플릿→문항 스냅샷 복사 시 함께 복사됨. 서류·행정 REQUIRED 6개 문항(등기부등본/신탁등기/명의불일치/소유권취득일/확정일자/전입세대열람)에 우선 반영, FE가 이미 콘텐츠를 준비해둔 상태에서 백엔드 필드가 뒤따라 추가됨. **(2026-07-31 갱신)** 값 안에 마크다운 스타일 강조(`**강조할 문구**`)와 문단 구분(`\n\n`)이 포함돼 있음 — 순수 텍스트가 아니라 경량 마크업이 섞인 문자열이므로, FE는 그대로 `<p>`에 렌더링하면 안 되고 ① `**...**` 파싱 후 굵게 표시, ② `white-space: pre-line`(또는 동등한 처리)으로 `\n\n`을 문단 줄바꿈으로 반영해야 의도대로 보임(현재 `ChecklistClient.tsx`는 아직 미반영 상태)
- **`disclaimer`(면책 문구)** **(2026-07-30 추가)** — 결과 조회(`GET /checklists/{checklistId}/result`) 응답에 "이 결과는 매물의 안전을 보장하지 않습니다."라는 고정 문구를 `disclaimer` 필드로 포함. 체크리스트 결과가 등급·점수가 아니라는 점을 API 응답 차원에서도 명시하기 위해 추가

## (2026-08-14) 실사용 피드백 반영 — 신규 문항 6개, 다지선다 타입, 예시 이미지

일주일간 수집된 실사용 피드백("확인 항목을 더 구체적으로", "예시 사진 추가", "보일러 종류 안내", "보안·단열 항목 보완")을 검토해 아래를 반영했습니다.

- **신규 문항 6개** — 단창/이중창 여부(YES_NO), 콘센트·전기 배선 누전 위험(CHECK, guideText로 확인 방법 안내), 차단기함 상태(CHECK), 보일러 종류(MULTIPLE_CHOICE), 냉난방 방식(MULTIPLE_CHOICE), 방범창(CHECK, 연립다세대·단독다가구 전용). 기존 CCTV·화재감지기 항목은 이미 있어 중복 추가하지 않음.
- **`ChecklistItemType.MULTIPLE_CHOICE` 신규 도입** — 기존 4개 타입(CHECK/YES_NO/DATE/DOCUMENT_REQUEST)이 전부 이진 응답이라 "보일러 종류"처럼 3개 이상 선택지 중 하나를 고르는 문항을 표현할 방법이 없었음. `ChecklistItemTemplate`/`ChecklistItem` 양쪽에 `options`(콤마 구분 문자열, 예: `"가스보일러,기름보일러,전기보일러,지역난방"`) 필드를 추가하고 템플릿→문항 스냅샷 복사 시 함께 복사됨(문항 내용 자체라 guideText/helperText와 동일하게 시점 고정 필요). 단일 선택만 지원(다중 선택 불필요 확인 완료).
- **외부 소음 항목 guideText 보강** — "저층이 더 심하다/고층이 더 심하다"로 일반화할 수 없어(저층은 도로·행인 소음, 고층은 바람·실외기 소음으로 종류 자체가 다름), 층수 판정 로직을 새로 만드는 대신 두 케이스를 guideText에 모두 안내하는 방식으로 해결.
- **예시 이미지 스키마 신규 도입** — `checklist_item_template_images` 테이블(`ChecklistItemTemplateImage`, `template_id` FK)을 신설. `ChecklistItem`에도 원본 템플릿을 가리키는 `template` FK(nullable)를 추가해, 문항 조회 시 `item.getTemplate().getImages()`로 이미지를 가져옴. **guideText/helperText와 달리 스냅샷 복사하지 않고 항상 템플릿을 실시간 참조** — 이미지는 문항 내용처럼 시점 고정이 필요한 콘텐츠가 아니라(현재 AI 생성 이미지를 추후 실제 사진으로 교체할 예정), 관리자가 이미지를 교체하면 이미 만들어진 체크리스트에도 자동 반영되는 편이 낫다고 판단.
  - 이미지는 S3(`checklist-template-images/` prefix, 폴더 구분 없이 파일명으로만 구분)에 콘솔로 직접 업로드하고, `S3ImagePurpose.CHECKLIST_TEMPLATE`(신규, 프로필/매물 이미지와 동일한 public 정책)를 통해 `S3PresignService.generateDownloadUrl()`로 URL을 생성 — 프로필/매물 이미지가 이미 쓰던 인프라를 그대로 재사용.
  - 문항 content ↔ 이미지 key 매핑은 `ChecklistTemplateSeeder`에 데이터로 둠(`application.yml` 등 설정 파일이 아니라 코드) — 환경별로 달라지는 설정값이 아니라 `ChecklistTemplateSeedData`의 문항 내용과 같은 성격의 시드 데이터라고 판단.
  - 관리자 페이지에 이미지 관리 API(업로드/삭제)는 아직 없음 — 지금은 시더 하드코딩으로 1차 반영하고, 추후 "관리자 페이지 확장" 작업 때 API로 옮길 예정(아래 "남은 이슈" 참고).
- **`GET /checklists` 응답에 `title`(매물 제목) 필드 추가** — 위 "요구사항에 없던 추가 구현" 절 참고.

## 해결 내역 (이전 "확인 필요" 8개 항목)

| # | 내용 | 처리 결과 |
|---|---|---|
| 1 | 매물유형별 문항 분기 없음 | ✅ 코드 수정 — 잠금장치 문항 매물유형별 분리 + 관리비 항목 추가(템플릿 v2) |
| 2 | 카테고리·중요도 순 정렬 없음 | ✅ 코드 수정 — 명시적 정렬 로직 추가(FE 순서 기준 카테고리 순위 테이블 사용) |
| 3 | 명의 일치/불일치 Y값 방향이 요구사항과 반대 | ✅ 코드 수정 — 질문 문구·판정 로직·테스트 모두 요구사항 방향(불일치=Y)으로 반전 |
| 4 | 삭제된 매물 체크리스트 조회/수정 방지가 생성 시점에만 있음 | ✅ 코드 수정 — 조회(`getChecklist`)·항목수정(`updateChecklistItem`) 시점에 매물 삭제 여부 검사 추가. **(2026-07-30 갱신)** 결과조회(`getChecklistResult`)에도 같은 검사가 빠져 있던 게 뒤늦게 발견돼 별도 버그 수정으로 추가(위 "체크리스트 결과 확인" 표 참고) |
| 5 | "매물 정보 부족"/"지원하지 않는 매물 유형" 실패 사유가 발생 안 함 | 문서화 완료 — 구조적으로 발생 불가, 코드 변경 불필요로 확정 |
| 6 | 결과확인 "확인한 항목 없음"/"결과 계산 실패" 실패 사유가 발생 안 함 | 문서화 완료 — 명세서 자체의 내부 모순, 현재 구현 유지로 확정 |
| 7 | 템플릿 버전 관리가 정수 상수 하나뿐 | 기존 로드맵 유지 — 이번 v2 반영이 첫 실전 사례 |
| 8 | `getChecklist`만 권한 없음을 404로 처리(도메인 내부 불일치) | ✅ 코드 수정 — 403/404 명시적 분리, 나머지 엔드포인트와 통일 |

## 에러코드 통일 (2026-07-30, `fix/checklist_error_code_unification`)

위 8번 항목(403/404 분리) 이후, 실제로 쓰이는 에러코드 자체를 도메인 전용으로 다시 정리한 후속 작업입니다. 그 전까지는 세 엔드포인트(`getChecklist`/`updateChecklistItem`/`getChecklistResult`) 모두 범용 `ErrorCode.NOT_FOUND`(메시지만 다르게 커스텀)/`ErrorCode.FORBIDDEN`을 그대로 썼습니다.

- **`ErrorCode`에 `CHECKLIST_NOT_FOUND`/`CHECKLIST_ITEM_NOT_FOUND` 신규 추가** — 범용 `NOT_FOUND`를 대체
- **권한 없음(403)은 전부 `PROPERTY_ACCESS_DENIED`로 통일** — checklist 소유권이 결국 매물 소유권에서 파생되므로, property 도메인의 기존 코드를 재사용(범용 `FORBIDDEN` 대체)
- **`getChecklist()`** — 체크리스트를 propertyId로 바로 찾던 구조에서, 매물을 먼저 조회(존재/삭제/소유권 확인)한 뒤 체크리스트를 찾는 2단계 구조로 변경. "매물 자체가 없음"(`PROPERTY_NOT_FOUND`)과 "매물은 있는데 체크리스트가 없음"(`CHECKLIST_NOT_FOUND`)을 구분할 수 있게 됨(이전엔 둘 다 그냥 404로 뭉뚱그려졌음)
- **`updateChecklistItem()`** — 체크리스트 없음/매물삭제는 `CHECKLIST_NOT_FOUND`, 항목 없음은 `CHECKLIST_ITEM_NOT_FOUND`, 권한 없음은 `PROPERTY_ACCESS_DENIED`
- **`getChecklistResult()`** — 위와 동일하게 `CHECKLIST_NOT_FOUND`/`PROPERTY_ACCESS_DENIED`로 통일 (매물 삭제 검사 자체가 이 시점엔 없었던 버그도 같은 라운드에서 같이 수정 — 위 "체크리스트 결과 확인" 표 참고)
- `checklistId` 기반 엔드포인트(`updateChecklistItem`/`getChecklistResult`)는 `PROPERTY_NOT_FOUND`를 쓰지 않음 — propertyId를 직접 안 받으므로 "매물 없음"을 별도로 구분할 지점이 없어, 전부 `CHECKLIST_NOT_FOUND`로 흡수

## 남은 이슈 / 확인 필요

1. **거래유형별 문항 분기는 여전히 없음** — 이번엔 매물유형 필터만 추가했고, 거래유형(전세/월세)에 따른 분기는 다루지 않음. 문항을 다시 살펴봐도 전세/월세 중 하나에만 해당하는 항목은 없어 지금 당장 필요하진 않다고 판단 — 필요성이 확인되면 동일한 필터 메커니즘(`applicablePropertyTypes`와 유사한 방식)으로 확장 가능
2. **(2026-08-14 신규) 예시 이미지 관리자 API 없음** — 지금은 `ChecklistTemplateSeeder`에 문항 content ↔ S3 key 매핑을 하드코딩해두고 앱 최초 기동 시 한 번만 반영하는 구조. 이미지를 교체/추가하려면 코드 수정 + 재배포가 필요함(문항 텍스트/타입은 이미 관리자 CRUD로 재배포 없이 가능한 것과 대조적). 관리자 페이지 확장 작업(템플릿 버전 관리와 같은 묶음) 때 이미지 목록조회/추가/삭제 API를 추가할 예정 — 그 전까지는 스키마(`ChecklistItemTemplateImage`, `S3ImagePurpose.CHECKLIST_TEMPLATE`)만 준비된 상태.

## 전수조사 결과 (2026-08-12)

### 버그/정확성

특별히 발견된 이슈 없음. 아래 항목들을 코드 기준으로 재검증했고 문제 없음을 확인:
- `ChecklistItem.check()`/`markInsufficient()`/`answer()`가 각각 `itemType`을 검증해 CHECK/YES_NO/DATE/DOCUMENT_REQUEST 방식이 서로 섞여 호출될 수 없음(`ChecklistService.updateChecklistItem`에서 잘못된 조합을 보내면 전부 400).
- `Checklist.refreshStatus()`/`computeResult()`는 `items`가 0개(모든 템플릿이 비활성화된 극단적 상황)여도 예외 없이 `NOT_STARTED`로 안전하게 계산됨(다만 `AdminChecklistTemplateService`가 활성 문항 0개를 이미 막고 있어 실제로 도달하기는 어려움).
- `getChecklist`/`updateChecklistItem`/`getChecklistResult` 세 곳 모두 매물 소유권(`property.isOwnedBy`/`checklist.getUser().getId().equals(userId)`)과 매물 삭제 여부를 일관되게 재검증함.

### 보안

특별히 발견된 이슈 없음.
- `GET /checklists` 목록(`ChecklistRepository.findOverviewByUserId`)이 `p.userId = :userId`와 `c.user.id = :userId` 조건을 모두 걸어 다른 유저의 매물/체크리스트가 섞여 나올 수 없음을 코드로 확인.
- `updateChecklistItem`은 itemId를 해당 `checklist.getItems()`(이미 소유권 검증된 체크리스트) 컬렉션 내에서만 찾으므로, 다른 유저의 체크리스트에 속한 itemId를 넣어도 `CHECKLIST_ITEM_NOT_FOUND`로만 끝나고 크로스 체크리스트 접근이 되지 않음.
- `/checklists/**`, `/properties/**/checklists` 모두 `SecurityConfig`의 `anyRequest().authenticated()`에 걸려 인증 없이 접근 가능한 구멍이 없음.

### 코드 품질 (중복/구조/일관성)

1. ~~`ChecklistItemResponse.from()`이 `ChecklistItem.hasIssue()`를 재사용하지 않고 같은 로직을 중복 구현~~ **(2026-08-13 코드 수정 완료)** — `ChecklistItemResponse.from()`이 `item.isIssueFound() || item.getUserNote() != null`을 직접 계산하던 걸 `item.hasIssue()` 호출로 교체함. 계산식이 원래 완전히 동일했어서(`ChecklistItem.hasIssue()`와 100% 같은 식) 동작 변화는 없는 순수 리팩터.
2. ~~`ChecklistRepository.findAllByUserId(Long userId)`가 죽은 코드~~ **(2026-08-13 정정, 오탐)** — 전수조사 당시 `checklist` 패키지 범위로만 참조를 확인해 놓친 것으로 보인다. 실제로는 `UserService.withdraw()`(`UserService.java:234`)가 회원 탈퇴 시 본인 소유 체크리스트를 하드 삭제하기 위해 이 메서드로 목록을 조회한다 — 삭제하면 회원 탈퇴 기능이 깨지므로 코드 변경 불필요.
3. ~~**관리자 템플릿 신규 생성 시 `version` 산정이 "활성" 기준이 아니라 "전체(비활성 포함)" 기준** — `AdminChecklistTemplateService.create()`(`AdminChecklistTemplateService.java:63-66`)는 `findAllByOrderByDisplayOrderAsc()`(활성+비활성 전체)에서 최대 버전을 구해 새 문항에 배정한다. 반면 `ChecklistItemTemplate` 클래스 주석은 "버전 하나 = 전체 매물 공통"이라는 불변식을 전제한다. 만약 과거에 더 높은 버전 번호를 가진 비활성 문항이 남아있는 상태에서 관리자가 새 문항을 추가하면, 새 문항은 현재 활성 배치보다 더 높은 번호를 받게 되고, `ChecklistService.createChecklist()`는 활성 목록의 첫 항목 버전만 체크리스트의 `templateVersion`으로 저장하므로 실제로는 서로 다른 버전 번호가 섞인 활성 문항 집합인데도 단일 버전 값만 기록되는 불일치가 생길 수 있다.~~ — ✅ **(2026-08-12 해결)** 버전 산정을 `findAllByOrderByDisplayOrderAsc()`(전체)에서 `findByActiveTrueOrderByDisplayOrderAsc()`(활성만)로 변경해 비활성 문항의 버전이 더 이상 영향을 주지 않도록 했고, 이 시나리오를 직접 재현하는 회귀 테스트(`AdminChecklistTemplateServiceTest.createIgnoresHigherVersionFromInactiveTemplates`)를 추가함.
