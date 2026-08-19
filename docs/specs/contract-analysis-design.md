# 계약 문구 분석(contract-analysis) 도메인 — 구현 현황 정리

## 배경 / 성격

다른 문서들과 같은 방식의 **회고성(retroactive) 문서**입니다. 이 도메인은 담당자(CLAUDE.md 기준: 송민혁)가 직접 최신화했습니다(2026-08-07). 이전 버전은 `/analyze`가 구현되기 전 시점에 작성되어 있어 이후 진행분(AI 분석, Q&A 챗봇, 환각 검증 보강 등)이 반영되어 있지 않았습니다 — 이번 갱신으로 코드 기준 현재 상태를 다시 정리했습니다.

**범위**: `com.algogyeyak.contractanalysis.*`(계약 문구 입력/OCR/마스킹/AI 분석/Q&A 챗봇)만 다룹니다. 다른 도메인(예: 매물 소유권 검증에 필요한 `Property`)에 대한 판단은 이 문서의 범위 밖이라 다루지 않습니다.

## 파이프라인 — 요구사항 4단계 대비 실제 5개 엔드포인트

요구사항은 입력→OCR→마스킹→AI분석 4단계였지만, 실제로는 분석 결과에 대한 후속 질문을 처리하는 **`POST /contract-analysis/chat`**이 추가로 구현되어 있습니다(요구사항 문서 작성 이후 추가된 기능).

| 순서 | 엔드포인트 | 상태 |
|---|---|---|
| 1 | `POST /contract-analysis/inputs` | ✅ 구현 |
| 2 | `POST /contract-analysis/ocr` | ✅ 구현 |
| 3 | `POST /contract-analysis/masking` | ✅ 구현 |
| 4 | `POST /contract-analysis/analyze` | ✅ 구현 |
| 5 (요구사항 외 추가) | `POST /contract-analysis/chat` | ✅ 구현 |

## 주요 Entity — 요구사항 대비 실제

요구사항의 "Entity"들은 애초에 **영구 저장 대상이 아니라 요청/응답을 표현하는 모델**입니다(요구사항 본문에도 "장기 저장 목적으로 저장하지 않는다"고 명시). 실제 구현도 이와 일치하게 JPA 엔티티가 아니라 DTO(record)로만 존재합니다.

| 요구사항 Entity | 실제 대응 |
|---|---|
| `ContractAnalysisRequest`(userId, propertyId, inputType, maskedText) | `ContractAnalysisInputRequest`/`ContractAnalysisInputResponse` — 입력 단계 DTO. `maskedText`는 마스킹 단계 응답(`ContractAnalysisMaskingResponse`)에 별도로 존재 |
| `OcrResult`(extractedText, confidence, status) | `ContractAnalysisOcrResponse`(extractedText, confidence, editable, **uncertainFields**) — `status` 대신 `editable`(항상 true) + 신뢰도 낮은 필드 목록(`uncertainFields: [{text, index}]`, 아래 참고) |
| `ClauseAnalysisResult`(originalText, riskFlag, explanation, question, suggestedText) | ✅ `ContractAnalysisClause`(record) — 필드 구성이 요구사항과 정확히 일치 |
| `ContractAnalysisResponse`(summary, clauses, disclaimer, aiGeneratedNotice, status) | ✅ `ContractAnalysisAnalyzeResponse`(summary, clauses, aiGeneratedNotice, disclaimer) — `status` 필드만 없음(현재 실패는 전부 예외로 표현되고 별도 status enum은 없음) |

## 계약 문구 입력 (`POST /contract-analysis/inputs`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 입력 방식(이미지/텍스트) 확인 | ✅ `InputType`으로 분기 |
| 이미지 파일 형식·크기 검증 | ✅ `image/jpeg`, `image/png`만 허용, 10MB 이하. ~~크기 검증은 코드상 10MB 상한이 있으나 `application.yml`에 `spring.servlet.multipart.max-file-size`가 설정돼 있지 않아 Spring Boot 기본값(1MB)에 먼저 걸려, 1MB~10MB 사이 이미지는 의도한 400이 아니라 500으로 잘못 응답됨~~ **(2026-08-12 재정정)** 전수조사 시점엔 실재했던 문제였으나, `dev`의 `d7cf368`(2026-08-11, 이 문서 최초 조사와 별개로 이미 병합돼 있던 커밋)가 `application.yml`에 `max-file-size`/`max-request-size: 10MB`를 추가해 이미 해결됨(전수조사 결과 버그 1번 참고) |
| 텍스트 최소 조건 확인 | ✅ 20자 미만이면 거부(`MIN_TEXT_LENGTH = 20`, 상수 하드코딩 — 정책값으로 외부화되어 있진 않음) |
| 선택한 매물이 사용자 소유인지 확인 | ❌ **여전히 TODO 스텁 상태.** `validatePropertyOwnership`은 `propertyId`가 `null`이 아니면 아무 것도 하지 않고 그냥 통과시킴(본문이 비어있음, TODO 주석 그대로) — 이전 버전 문서 작성 이후로 변경 없음 |
| 실패: 지원하지 않는 파일 형식 | ✅ 400 |
| 실패: 파일 크기 초과 | ✅ 400. ~~1MB 이하 구간만 의도대로 400, 1MB~10MB 구간은 멀티파트 리졸버가 서비스 코드보다 먼저 막아 500으로 잘못 응답됨~~ **(2026-08-12 재정정)** `d7cf368`로 이미 해결됨(위 행 참고) |
| 실패: 입력된 텍스트 없음 | ✅ 400 (`CONTRACT_ANALYSIS_INVALID_INPUT`) |
| 실패: 분석하기에 지나치게 짧은 문구 | ✅ 400 |
| 실패: 부동산 계약과 관련 없는 입력 | ⚠️ **(2026-08-14 재정정)** `/inputs`·`/ocr` 단계의 사전 검증은 여전히 없다. 대신 `d7cf368`(2026-08-11) 이후 `/analyze`에서 Gemini가 `maskedText`가 계약 조항으로 보이지 않으면 `clauses`를 빈 배열로, `summary`에 안내 문구("입력하신 내용이 계약 조항으로 보이지 않습니다...")를 채워 **200으로** 응답하는 방식으로 처리된다(`GeminiClientImpl` 프롬프트 규칙 8, `ContractAnalysisAnalyzeResponse.of()`). 전용 코드 `CONTRACT_ANALYSIS_NOT_RELATED`는 이 방식과 별개로 끝까지 미사용 상태였던 것으로 확인되어 제거함(`ErrorCode.java`) |
| 실패: 매물 접근 권한 없음 | ❌ 위 TODO 참고 — 코드(`CONTRACT_ANALYSIS_FORBIDDEN`)는 정의돼 있지만 실제로 발생하지 않음 |

## OCR 텍스트 추출 (`POST /contract-analysis/ocr`) — 요구사항 대비

**⚠️ 정책이 이전 버전 문서 이후 바뀌었습니다**: 낮은 신뢰도를 이유로 OCR 결과 자체를 거부하던 방식(`OCR_LOW_CONFIDENCE`, 422)을 없애고, 신뢰도 낮은 필드를 `uncertainFields`로 알려주기만 하는 힌트 방식으로 전환했습니다. OCR이 불완전해도 사용자 흐름을 막지 않고, 프론트가 해당 필드만 강조해서 "확인이 필요합니다"로 보여줄 수 있게 한다는 판단입니다.

| 요구사항 | 실제 구현 |
|---|---|
| 외부 OCR API(Clova) 호출 | ✅ `ClovaOcrClientImpl` |
| 추출 텍스트를 수정 가능한 형태로 제공 | ✅ `editable: true` 필드 |
| 결과가 비어있거나 신뢰도 낮은지 확인 | 결과가 비어있으면 ✅ `OCR_EMPTY_RESULT`(422)로 거부. 신뢰도(필드별 0.7 미만)는 더 이상 거부 사유가 아니고, `uncertainFields: [{text, index}]`로 응답에 포함만 됨 — 전체 응답은 항상 성공 |
| 실패: OCR API 장애 / 응답 시간 초과 | ✅ 502(`OCR_API_ERROR`), 타임아웃(`SocketTimeoutException`)은 504로 별도 구분 |
| 실패: 문자 인식 어려운 이미지 / 손글씨·저품질 이미지 | ⚠️ 더 이상 "실패"로 취급되지 않음 — 신뢰도 낮은 필드는 `uncertainFields`에 담겨 성공 응답으로 내려감. 정책 자체가 "거부"에서 "힌트"로 바뀌었기 때문에, 요구사항의 "실패 사유" 관점과는 전제가 달라진 상태(버그가 아니라 의도된 방향 전환) |
| 실패: 비어있는 OCR 결과 | ✅ |
| 실패 시 텍스트 직접 입력 안내 | ⚠️ 여전히 백엔드 응답엔 별도 안내 필드가 없음. `uncertainFields`로 프론트가 UX를 구성해야 하는 구조는 동일 |

## 개인정보 마스킹 (`POST /contract-analysis/masking`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 전화번호·주민등록번호·계좌번호 정규식 마스킹 | ✅ 순서를 고정(주민번호→전화번호→계좌→성명)해서 충돌 방지 |
| 임대인/임차인 성명 등 라벨 기반 마스킹 | ✅ "임대인"/"임차인" 라벨 뒤 2~4자 한글을 마스킹 |
| 마스킹 결과를 서버에 저장하지 않음 | ✅ 순수 함수형 서비스라 애초에 영속화 코드 자체가 없음 |
| 사용자가 마스킹 결과를 확인했는지 검증 | ✅ **이전 버전 문서에서 "검증 지점 없음"으로 지적됐던 부분이 해소됨.** `/masking` 자체는 여전히 확인 여부를 받지 않지만(`requiresUserConfirmation: true`만 응답), 다음 단계인 `/analyze`가 `userConfirmed !== true`면 즉시 `CONTRACT_ANALYSIS_MASKING_NOT_CONFIRMED`(400)로 막음 — CLAUDE.md의 "마스킹 완료 전 AI 분석 요청 절대 차단" 정책이 실제로 강제되고 있음 |
| 실패: 마스킹 결과 확인 미완료 | ✅ 위 항목 참고, `/analyze`에서 발생 |
| 실패: 마스킹 처리 실패 | ✅ 정규식 처리 중 런타임 예외 발생 시 500(`MASKING_FAILED`)으로 감싸서 반환 |
| 실패: 확인할 텍스트 없음 | ✅ 400 |

## AI 계약 문구 분석 (`POST /contract-analysis/analyze`) — 요구사항 대비

✅ **구현 완료.** 이전 버전 문서가 작성된 시점엔 이 엔드포인트가 아예 없었지만, 현재는 `ContractAnalysisAnalyzeService` + `GeminiClientImpl`(Gemini `generateContent`, `response_schema`로 JSON 구조 강제)로 전체 흐름이 동작함.

| 요구사항 항목 | 실제 구현 |
|---|---|
| 계약 문구별 위험 신호 분석 | ✅ 조항별 `riskFlag`(boolean, CLAUDE.md 정책대로 등급 없이 true/false) |
| 쉬운 설명 생성 | ✅ `explanation` — 시스템 프롬프트에 "위험 등급이 아니라 사실과 이유로 설명" 지시 |
| 확인 질문 생성 | ✅ `question` — 중개사/임대인에게 직접 물을 질문(사용자 자기점검 질문 금지 규칙, 프롬프트 rule 5 + 스키마 `description`으로 이중 강제) |
| 수정 요청 문구 예시 생성 | ✅ `suggestedText` — "법률적 정답이 아닌 협의용 예시"임을 프롬프트에서 안내 |
| AI 응답 구조 검증 | ✅ `parseAndValidateSchema`가 JSON 파싱 실패/필수 필드 누락 시 `CONTRACT_ANALYSIS_AI_RESPONSE_INVALID`(502) |
| "사용자가 입력하지 않은 조항 포함 여부" 검증(환각 방지) | ✅ `validateNoHallucination` — 마스킹 텍스트에 조항 원문이 그대로 포함되는지 확인. 다음 3단계 폴백: (1) 정규화 후 완전 포함, (2) 조 제목(`제N조(...)`) 제거 후 포함, (3) 그래도 실패하면 원문 단어의 85% 이상이(순서 무관, 대괄호 제거 후) 마스킹 텍스트에 존재하는지(표/체크박스 OCR 평문화로 어순이 깨지는 경우 대응, 5단어 미만 짧은 인용은 100% 일치 요구) |
| "AI가 생성한 결과입니다" 고지 | ✅ 응답 레벨 `aiGeneratedNotice` 필드로 고정 문자열 반환(조항별 `explanation`에는 더 이상 안 들어감 — 중복 방지, 프롬프트 rule 6) |
| 법적 효력 없음 안내 / "협의용 예시" 안내 | ✅ 응답 레벨 `disclaimer` 필드로 통합(마찬가지로 조항별 텍스트에서 제거, 아래 "남은 이슈" 참고) |
| 마스킹 미확인 시 차단 | ✅ 위 마스킹 섹션 참고 |

**에러 코드**: `CONTRACT_ANALYSIS_AI_RESPONSE_INVALID`(502, 스키마 위반), `CONTRACT_ANALYSIS_AI_HALLUCINATION`(502, 환각), `CONTRACT_ANALYSIS_AI_API_ERROR`(502, 연동 실패 / 504 타임아웃) — 이전 문서에서 "정의만 되어있고 미사용"이라 지적됐던 부분이 전부 실제로 쓰이고 있음.

## Q&A 챗봇 (`POST /contract-analysis/chat`) — 요구사항 외 추가 기능

요구사항 문서에는 없던 기능으로, `/analyze` 결과의 특정 조항에 대해 사용자가 후속 질문을 이어갈 수 있게 함(`ContractAnalysisChatService`).

- 요청(`ContractAnalysisChatRequest`): 질의 대상 조항(`ContractAnalysisChatClause`: originalText/riskFlag/explanation — `/analyze` 응답의 `ContractAnalysisClause`에서 question/suggestedText만 뺀 축소판), `question`, 대화 이력(`history: [{role, content}]`, `role`은 "user"/"assistant")
- 검증: `question` 없으면 `CONTRACT_ANALYSIS_QUESTION_REQUIRED`(400), `clause.originalText` 없으면 `CONTRACT_ANALYSIS_INVALID_INPUT`(400)
- Gemini 호출은 analyze와 별도 시스템 프롬프트(`CHAT_SYSTEM_INSTRUCTION_TEMPLATE`, 조항 무관 질문엔 정중히 안내하도록 지시) 경로를 씀 — `response_schema` 강제는 없음(자유 텍스트 답변)
- 응답(`ContractAnalysisChatResponse`)도 analyze와 동일하게 `aiGeneratedNotice`/`disclaimer`를 응답 레벨 필드로 고정 반환, 답변 텍스트 자체엔 고지문구·마크다운 문법을 넣지 않도록 프롬프트로 지시

## 비기능 요구사항 — 대조

| 항목 | 요구사항 | 실제 |
|---|---|---|
| 계약 이미지·마스킹 전 텍스트를 로그에 기록하지 않음 | O | ✅ 코드상 이미지/원문 텍스트를 로깅하는 지점 없음(환각 검증 실패 시 디버그 로그도 정규화된 masked/original 텍스트만 남김 — 마스킹 이후 텍스트라 개인정보 자체는 이미 제거된 상태) |
| 마스킹 확인 전 AI API에 전송 금지 | O | ✅ `/analyze`가 `userConfirmed`를 검증한 뒤에만 `geminiClient.analyzeClauses`를 호출 — 이전 문서에서 "위험 있음"으로 지적됐던 부분 해소 |
| 외부 API 전달 데이터 범위 최소화 | O | ✅ OCR엔 이미지만, 마스킹은 서버 자체 정규식 처리(외부 API 미사용), AI엔 마스킹된 텍스트만 전달 |
| 계약 이미지 원본을 분석 완료/실패 후 임시저장소에서 삭제 | O | 원본을 디스크에 저장하는 코드 자체가 없어(메모리 상에서 바로 외부 API로 전달) 이 요구사항이 적용될 대상이 없음 — 향후 파일을 임시 저장하는 방식으로 바뀌면 재검토 필요 |
| 외부 API 인증키를 클라이언트에 노출하지 않음 | O | ✅ `@Value`로 서버 설정에서만 주입(Clova `secretKey`, Gemini `apiKey`), 응답 DTO에 포함 안 됨 |
| AI 결과가 입력 근거 기반이어야 함 / 환각 방지 | O | ✅ `validateNoHallucination` (위 참고) |
| OCR 결과를 AI 분석 전 사용자가 수정 가능 | O | ✅ `editable: true`로 응답, `/analyze`는 프론트가 넘긴 `maskedText`를 그대로 받으므로 그 사이 수정이 실제로 반영됨 |
| 계약 조항 분석과 보증금 안전성 체크 구분 표시 | O | contract-analysis 도메인 범위 밖 — 여기서 다루는 응답엔 보증금 안전성 관련 필드가 없음(다른 도메인 여부는 확인 안 함) |
| OCR 실패 시 텍스트 직접 입력 제공 | O | ⚠️ `/inputs`가 `TEXT` 타입을 이미 지원하므로 "가능은 하다"고 볼 수 있으나, OCR 응답이 이를 명시적으로 안내하진 않음(위 참고) |
| AI API 호출 실패 시 무제한 재시도 금지 | O | ✅ 재시도 로직 자체가 없음(호출 1회, 실패 시 즉시 예외) |
| 동일 분석 요청 중복 제출 방지 | O | ❌ 여전히 관련 로직 없음(멱등 키나 중복 요청 차단 장치 없음) — 이전 문서 이후 변경 없음 |
| 분석 실패 시 입력 내용이 즉시 사라지지 않음 | O | 서버는 애초에 아무 것도 저장하지 않는 구조라, 프론트엔드가 입력값을 화면 상태로 들고 있어야 한다는 의미로 보임 — 확인 필요 |
| OCR/AI 응답시간 제한 적용 | O | ✅ 둘 다 타임아웃 발생 시 504(`GATEWAY_TIMEOUT`)로 구분 처리(OCR: `SocketTimeoutException`, Gemini: `SocketTimeoutException`/`HttpTimeoutException`) |
| 이미지 크기·해상도 제한 | O | 크기(10MB)는 ✅ 실제로 시행됨(위 "전수조사 결과" 참고 — `d7cf368`로 멀티파트 설정 누락 해결됨). 해상도 제한은 여전히 없음 |
| OCR/AI API 응답시간·오류율 모니터링 | O | 코드 안에서 확인 가능한 범위 밖(Actuator/Prometheus 등 인프라 레벨 설정 필요) — 확인 안 함 |

## 남은 이슈 / 확인 필요 총정리 (2026-08-07 기준)

1. 매물 소유권 검증이 여전히 TODO 스텁 상태(`ContractAnalysisInputService.validatePropertyOwnership`) — 이전 문서 이후 변경 없음
2. ~~"부동산 계약과 관련 없는 입력" 검증 로직이 여전히 없음 — 전용 에러코드(`CONTRACT_ANALYSIS_NOT_RELATED`)는 정의돼 있지만 어디서도 쓰이지 않는 죽은 코드~~ **(2026-08-14 재정정)** `/analyze`가 Gemini의 `clauses` 빈 배열 + `summary` 안내 문구 방식으로 이미 처리하고 있음을 확인(위 표 참고). 다만 `/inputs`·`/ocr` 단계의 사전 검증은 여전히 없음. 미사용이 재확인된 `CONTRACT_ANALYSIS_NOT_RELATED`는 제거함
3. OCR 실패 응답에 "텍스트 직접 입력 안내"가 담겨있지 않음 — 프론트가 이 UX를 알아서 구성해야 하는 구조로 보이는데 의도된 역할 분담인지 확인 필요
4. 동일 분석 요청의 중복 제출을 막는 장치가 없음
5. 계약 이미지 원본의 "임시저장소 삭제" 요구사항에 대응할 저장 로직 자체가 없음(메모리에서 바로 외부 API로 전달) — 향후 저장 방식이 바뀌면 재검토 필요
6. `ContractAnalysisAnalyzeService`에 환각 검증 실패 시 디버그용 `log.error("[HALLUCINATION_DEBUG] ...")` 3줄이 남아 있음 — 실제 운영에서도 매 환각 검출마다 로그가 쌓이는 구조라, 상시 유지할지 진단 완료 후 레벨을 낮출지 정리 필요

## 전수조사 결과 (2026-08-12)

### 버그/정확성

1. ~~**이미지 크기 제한(10MB)이 1MB~10MB 구간에서는 실제로 시행되지 않고 500으로 잘못 응답함.** `ContractAnalysisInputService`(L18 `MAX_IMAGE_SIZE_BYTES`)와 `ContractAnalysisOcrService`(L20)는 각자 10MB 상한을 코드로 체크하지만, `application.yml`/`application-{dev,prod,test,local}.yml` 전체를 확인한 결과 `spring.servlet.multipart.max-file-size`/`max-request-size`가 어디에도 설정돼 있지 않다. 이 경우 Spring Boot 기본값(파일당 1MB, 요청 전체 10MB)이 적용되어, 1MB를 넘는 이미지는 컨트롤러/서비스 코드에 도달하기도 전에 멀티파트 리졸버가 `MaxUploadSizeExceededException`을 던져 거부한다. `GlobalExceptionHandler`(`backend/src/main/java/com/algogyeyak/global/exception/GlobalExceptionHandler.java`)에는 이 예외 전용 핸들러가 없어 catch-all `Exception` 핸들러(L132-136)로 떨어지고, 결과적으로 문서/코드가 의도한 `400 CONTRACT_ANALYSIS_FILE_TOO_LARGE`가 아니라 `500 INTERNAL_SERVER_ERROR`가 내려간다. 서비스 레벨 유닛 테스트만 있고(`ContractAnalysisOcrServiceTest` 등, 서비스 메서드를 직접 호출) 실제 멀티파트 파싱을 거치는 통합 테스트가 없어 이 경로는 테스트로도 걸러지지 않는다. 수정 방향: `application.yml`에 `spring.servlet.multipart.max-file-size: 10MB`(+`max-request-size`)를 명시하거나, `MaxUploadSizeExceededException` 전용 핸들러를 추가해 400으로 매핑.~~ **(2026-08-12 해결 확인)** 이 전수조사와 별개로 `dev`에 이미 병합돼 있던 `d7cf368`(2026-08-11, "전수조사 발견 이슈 5건 수정" — 담당자 자체 점검으로 보임)가 `application.yml`에 `spring.servlet.multipart.max-file-size`/`max-request-size: 10MB`를 추가해 해결함. 이 전수조사는 그 커밋이 로컬에 반영되기 전 스냅샷을 기준으로 진행돼 뒤늦게 같은 문제를 중복 발견한 것 — 코드 재확인 결과 현재 `dev`에는 이미 해결된 상태다. **(2026-08-14 재-재정정)** 위 "해결 확인"은 1MB~10MB 구간(한도 불일치)만 검증한 것이었고, **정확히 10MB를 넘는 요청은 여전히 500으로 응답됨을 실제 API 호출로 재확인**했다 — `max-file-size`를 앱의 자체 상한과 맞춰도, 그 상한 자체를 넘는 요청은 결국 `MaxUploadSizeExceededException`으로 컨트롤러 이전에 걸러지고 여전히 전용 핸들러가 없었기 때문. `GlobalExceptionHandler`에 `MaxUploadSizeExceededException` → `400 CONTRACT_ANALYSIS_FILE_TOO_LARGE` 핸들러를 추가하고 11MB 더미 파일로 재검증해 진짜로 해결함(`ErrorCode.CONTRACT_ANALYSIS_NOT_RELATED`도 이때 함께 정리 — 아래 2번 참고). 참고로 `ContractAnalysisOcrService`/`ContractAnalysisInputService`의 자체 10MB 체크는 Spring 쪽 한도와 정확히 같아서 여전히 도달 불가능한 죽은 코드로 남아 있다(둘 중 하나를 더 낮추거나 없애는 정리는 별도 판단 필요).
2. **(참고, 새 버그 아님)** 같은 `d7cf368`에서 `/analyze`가 `maskedText`에 남은 주민등록번호/전화번호/계좌번호 패턴을 서버가 직접 재검증해 거부하는 `ContractAnalysisMaskingService.containsUnmaskedPii()` + `CONTRACT_ANALYSIS_UNMASKED_PII_DETECTED`(400)를 추가했다 — 클라이언트가 `/masking`을 실제로 거쳤는지 `userConfirmed` 플래그만으로는 보장할 수 없던 문제(마스킹 우회)를 막는 조치로, 이번 전수조사가 발견한 항목은 아니지만 관련 보안 항목이라 기록해둔다. `/inputs`의 `validatePropertyOwnership` TODO 주석도 같은 커밋에서 "PropertyRepository/`isOwnedBy`가 이미 있으니 연결만 하면 된다"는 내용으로 갱신됐으나, **실제 검증 로직은 여전히 없다** — 아래 보안 1번(propertyId 우회) 결론에는 영향 없음.

### 보안

1. **`/analyze`는 propertyId 소유권 검증을 아예 우회할 수 있는 별도 경로.** `ContractAnalysisAnalyzeRequest.propertyId`(`dto/ContractAnalysisAnalyzeRequest.java` L6) 필드는 컨트롤러/서비스 어디에서도 읽히지 않는 완전한 죽은 필드다(grep으로 재확인 — `ContractAnalysisAnalyzeService.analyze`는 `maskedText`/`userConfirmed`만 사용). `/inputs`의 `validatePropertyOwnership` TODO가 나중에 구현돼도, `/analyze`는 `/inputs` 호출 여부와 무관하게 독립적으로(그리고 상태 없이) 호출 가능한 엔드포인트라 자체 소유권 검증이 없으면 여전히 우회 가능하다. 즉 소유권 검증을 붙여야 할 지점이 `/inputs` 하나가 아니라 최소 `/analyze`도 포함되어야 하는데, 현재는 필드만 있고 로직이 전혀 없다.
2. **파일 형식 검증이 클라이언트가 보낸 `Content-Type` 헤더에만 의존.** `ContractAnalysisInputService.validateImage`(L48)와 `ContractAnalysisOcrService.validateAndResolveFormat`(L68)는 둘 다 `image.getContentType()` 문자열만 확인하고 실제 파일 바이트(매직 넘버)는 검사하지 않는다. Content-Type은 멀티파트 요청에서 클라이언트가 임의로 지정 가능한 값이므로, 이미지가 아닌 파일을 `image/jpeg`로 위장해 그대로 Clova OCR API로 전달할 수 있다. 현재는 응답을 저장/실행하지 않아 피해가 제한적이지만, 매직바이트 기반 스니핑이 전혀 없다는 점은 명확한 검증 공백이다.
3. **`/masking`, `/analyze`, `/chat` 요청 본문에 길이 상한이 전혀 없음.** `ContractAnalysisMaskingRequest.text`, `ContractAnalysisAnalyzeRequest.maskedText`, `ContractAnalysisChatRequest.question`/`history`는 record에 `@Size` 등 bean validation이 없고 컨트롤러도 `@Valid`를 쓰지 않는다(둘 다 코드 확인). 이 값들은 그대로 `GeminiClientImpl.analyzeClauses`/`chat` 호출에 실린다 — 인증된 사용자라면 누구든 매우 큰 텍스트나 긴 대화 히스토리를 반복 전송해 외부 AI API 비용을 유발하거나 응답을 지연시킬 수 있다. 기존에 지적된 "중복 제출 방지 없음"과는 별개로, 페이로드 크기 자체에 대한 상한이 없다는 점이 새로 확인된 부분이다.

### 코드 품질 (중복/구조/일관성)

1. `MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024`가 `ContractAnalysisInputService`(L18)와 `ContractAnalysisOcrService`(L20) 두 곳에 각각 하드코딩되어 있다. 공용 상수나 설정값(`@ConfigurationProperties` 등)으로 추출돼 있지 않아 정책이 바뀌면 두 곳을 함께 고쳐야 하고, 위 "버그" 항목처럼 이 값이 실제로는 서버 멀티파트 기본값에 막혀 시행되지 않는 상태이기도 하다.
2. `GeminiClientImpl.execute`(L188-198)의 주석과 `HttpTimeoutException` catch 분기가 실제 빈 배선과 맞지 않는다. 주석은 "Spring Boot 4의 RestTemplateBuilder는 JDK HttpClient 기반 요청 팩토리를 쓰므로 read timeout이 SocketTimeoutException이 아니라 HttpTimeoutException으로 던져진다"고 설명하지만, 실제로 주입되는 `RestTemplate` 빈(`property/config/RestTemplateConfig.java` L33-39)은 국토부 API User-Agent 이슈 때문에 `SimpleClientHttpRequestFactory`(HttpURLConnection 기반)로 명시적으로 오버라이드되어 있다(같은 파일 주석에도 그 이유가 적혀 있음). 이 RestTemplate에서는 read timeout이 항상 `SocketTimeoutException`으로만 던져지므로 `HttpTimeoutException` 분기(L191)는 현재 배선에서는 도달하지 않는 코드다. 첫 번째 분기가 이미 정상 처리하므로 기능상 문제는 없지만, 주석이 실제 설정과 맞지 않아 향후 유지보수 시 혼란을 줄 수 있다.
