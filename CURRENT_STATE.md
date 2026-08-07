# Backend 구현 현황

이 문서는 도메인별 실제 구현 상태를 요약합니다. 상세 설계 이력·트러블슈팅·남은 이슈는 각 `docs/specs/{도메인}-design.md`를 참고하세요. 도메인 간에 반복적으로 나타나는 패턴(죽은 에러코드, 권한 처리 방식 등)은 `docs/specs/cross-domain-summary.md`에 모아뒀습니다.

마지막 갱신: 2026-08-07

## auth — 거의 완전 구현

구글/카카오 OAuth2 로그인과 로컬 이메일/비밀번호 로그인이 Refresh Token과 함께 구현되어 있습니다. 다중 소셜 계정 연동(`UserSocialAccount`), Access/Refresh 토큰 발급·검증·재발급(둘 다 Redis 기반), 로그아웃 시 jti 블랙리스트 즉시 무효화, 토큰 실패 사유(만료/무효/누락) 3종 구분까지 되어 있습니다. 남은 건 코드가 아니라 팀 결정 2건뿐입니다 — 카카오 email 스코프 심사 신청 여부, Refresh Token "별도 저장소 없음" 원문 요구사항의 해석.

→ 상세: [`docs/specs/auth-design.md`](./docs/specs/auth-design.md)

## user — 부분 구현

프로필 등록/조회/수정, 닉네임 중복확인, S3 presign/confirm 방식의 프로필 이미지 업로드·삭제까지 되어 있습니다. 회원 탈퇴는 `User`만 익명화되고, `UserPreference`/OAuth 연동정보/Property·ContractAnalysis 연관 데이터 처리 방식은 아직 TODO로 남아있습니다.

→ 상세: [`docs/specs/user-design.md`](./docs/specs/user-design.md)

## property — 부분 구현, 명세보다 좁음

매물 CRUD, 지역·면적·가격·거래유형 검색 + 페이지네이션, 매물 신고, market-data 시세비교 연동, 체크리스트 진행률 표시까지 되어 있습니다. 매매(SALE) 거래유형은 아직 없고(전월세만 지원), risk-analysis(위험 신호) 정보는 매물 상세 응답에 아직 연동되지 않았습니다.

→ 상세: [`docs/specs/property-design.md`](./docs/specs/property-design.md)

## market-data — 거의 완전 구현

국토부 실거래가 API(오피스텔/연립다세대/단독다가구) 연동, 반경 기반(300m→600m) 시세비교, Redis 캐싱(TTL 30분), property 도메인과의 실연동까지 완료돼 있습니다. 남은 건 프론트 UI 연동과, 외부 API 호출 실패와 표본 부족을 구분 못 하는 구조적 한계(risk-analysis 쪽에서도 이 한계에 영향받음) 정도입니다.

→ 상세: [`docs/specs/market-data-design.md`](./docs/specs/market-data-design.md)

## checklist — 거의 완전 구현

체크리스트 생성/조회/항목 확인/결과 확인 4개 엔드포인트, 관리자용 문항 템플릿 CRUD, **(2026-08-07)** 내 체크리스트 목록 조회(`GET /checklists`) DB 레벨 페이지네이션까지 되어 있습니다. 요구사항 대비 확인 필요 8개 항목은 전부 처리 완료됐고, 남은 건 거래유형별 문항 분기 도입 여부(당장 불필요로 판단)와 템플릿 버전 관리(admin 페이지 확장, 다음 작업 예정) 정도입니다.

→ 상세: [`docs/specs/checklist-design.md`](./docs/specs/checklist-design.md)

## risk-analysis — 완전 구현

허위매물 의심 신호 탐지기 4종, API 4개(`POST /risk-analysis`, `GET /risk-signals`, `GET /deposit-safety`, `POST /deposit-safety/recalculate`), market-data 어댑터(전세+매매), 전세가율 계산(`DepositSafetyCheckService`), checklist 연계 보조 신호, 매물 수정 시 자동 재계산(이벤트 기반)까지 전부 구현되어 있습니다. **(2026-08-06)** 동시 요청 시 upsert 경쟁 상태 관련 트러블슈팅(MySQL REPEATABLE READ 스냅샷 문제 포함) 2건도 해결했습니다. 남은 건 "동일 계정 다수 등록" 탐지 활성화 여부·선순위보증금 입력 화면 배치(팀/FE 결정 2건)와, market-data의 API 실패 구분·`PropertyDetailResponse` 연동(다른 도메인 쪽 코드 작업 2건)뿐입니다.

→ 상세: [`docs/specs/risk-analysis-design.md`](./docs/specs/risk-analysis-design.md)

## contract-analysis — 부분 구현

입력(이미지/텍스트) → Clova OCR → 개인정보 마스킹(전화번호/주민번호/계좌/성명) 3단계는 동작합니다. 파이프라인의 핵심인 AI 계약 분석(`/analyze`)은 아직 없고(에러코드만 미리 정의됨), 매물 소유권 검증도 TODO 상태입니다. 챗봇(`/chat`) 기능이 최근 추가됐습니다.

→ 상세: [`docs/specs/contract-analysis-design.md`](./docs/specs/contract-analysis-design.md)

## admin — 부분 구현, 여러 패키지에 분산

별도 도메인 패키지로 통합돼 있지 않고 기능별로 흩어져 있습니다: `admin.controller.AdminStatsController`(대시보드 통계), `user.controller.AdminUserController`(유저 목록/역할/상태 변경), `property.controller.AdminPropertyReportController`(매물 신고 검토), `checklist.controller.AdminChecklistTemplateController`(문항 템플릿 CRUD). 전부 `/admin/**` 경로로 `ROLE_ADMIN`만 접근 가능합니다. 별도 설계 문서는 아직 없습니다.

---

## 로컬 개발 환경

### Redis

Access token blacklist와 refresh token 저장소는 Redis가 있어야 동작합니다 (`REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`, 로컬 기본값은 `localhost:6379`/비밀번호 없음). 로컬에서 가장 간단한 실행 방법:

```bash
docker run --name algogyeyak-redis -p 6379:6379 -d redis:7-alpine
```

Redis 연결에 실패하면 **fail-closed**로 처리됩니다 — access token 인증은 거부되고(401), refresh token 발급/재발급/로그아웃은 503(`AUTH_TOKEN_STORE_UNAVAILABLE`)으로 실패합니다. 가용성보다 "장애 중 무효화된 토큰이 재사용되지 않는 것"을 우선한 결정입니다.

### 비밀번호 정책

로컬 비밀번호 정책은 영문+숫자를 포함한 8~72자의 ASCII 출력 가능 문자(공백 제외)입니다 — BCrypt가 72바이트를 넘는 부분을 조용히 잘라버리는 문제 때문에 멀티바이트 문자를 막아 문자 수와 바이트 수를 일치시켰습니다 (`SignupRequest`의 `@Pattern` 참고).

### 소셜 로그인 / 환경변수

실제 구글/카카오 로그인을 로컬에서 테스트하려면 아래 환경변수가 필요합니다 (미설정 시 더미 값으로 기동은 되지만 실제 소셜 로그인은 동작하지 않습니다). `.env.example`을 `backend/.env`로 복사해 값을 채워두면 `me.paulschwarz:springboot4-dotenv`(개발 전용 의존성)가 `bootRun` 시 자동으로 읽어들입니다 — 별도로 셸에 export하거나 IDE Run Configuration에 등록할 필요가 없습니다. **`.env`는 반드시 BOM 없는 UTF-8로 저장하세요** — 메모장 등으로 저장하면 파일 앞에 보이지 않는 BOM이 붙어 dotenv 파서가 `Malformed entry` 에러를 내며 기동에 실패합니다.

dotenv는 원래 JVM의 working directory 기준으로 `.env`를 찾습니다. 이 프로젝트를 루트 폴더(`aibe6_team3_final_project`)로 열었다면 IntelliJ가 생성하는 Run Configuration의 working directory가 `backend`가 아니라 그 루트로 잡혀 `.env`를 못 찾는 경우가 있는데, `com.algogyeyak.global.config.DotenvDirectoryEnvironmentPostProcessor`가 기동 시 working directory에 `.env`가 없으면 `backend/.env`가 있는지 확인해 자동으로 그 위치를 알려주므로 — working directory를 `backend`로 직접 맞추거나 Run Configuration에 환경변수를 등록하는 등 팀원별 IDE 설정이 필요 없습니다.

| 환경변수                                       | 설명                                                                                                                                                                                                         |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`    | Google Cloud Console에서 발급                                                                                                                                                                                |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET`      | Kakao Developers에서 발급                                                                                                                                                                                    |
| `JWT_SECRET`                                   | HS256 서명용 시크릿 (최소 32바이트 랜덤 문자열)                                                                                                                                                              |
| `OAUTH2_STATE_SIGNING_KEY`                     | OAuth2 인가 요청을 담는 쿠키(`oauth2_auth_request`)의 HMAC 서명 키 (최소 32바이트 랜덤 문자열, `JWT_SECRET`과는 다른 값 권장)                                                                                |
| `OAUTH2_REDIRECT_URI`                          | 로그인 성공 후 리다이렉트할 프론트엔드 콜백 URL (dev 기본값 `http://localhost:3000/oauth/callback`, prod는 기본값 없이 fail-fast — 빠뜨리면 로그인 성공 후 localhost로 조용히 리다이렉트되는 것을 막기 위함) |
| `CORS_ALLOWED_ORIGINS`                         | 허용할 프론트엔드 origin (기본값 `http://localhost:3000`)                                                                                                                                                    |
| `COOKIE_SECURE`                                | `access_token` 등 쿠키의 Secure 속성 (dev 기본값 `false`, prod 기본값 `true`)                                                                                                                                |
| `COOKIE_SAME_SITE`                             | 쿠키의 SameSite 속성 (dev 기본값 `Lax`, prod는 기본값 없이 fail-fast — 배포 시나리오에 맞게 반드시 명시)                                                                                                     |
| `COOKIE_DOMAIN`                                | 쿠키의 Domain 속성 — 커스텀 서브도메인 배포 시에만 `.example.com`처럼 지정 (dev/prod 기본값 모두 비어있음=host-only)                                                                                         |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | access token blacklist/refresh token 저장소용 Redis 연결 정보 (dev 기본값 `localhost:6379`/비밀번호 없음, prod는 기본값 없이 fail-fast)                                                                      |

로컬은 프론트/백엔드 모두 `http://localhost:3000` / `http://localhost:8080`을 그대로 씁니다. 한때 `app.localhost`/`api.localhost` + `Domain=.localhost`로 커스텀 서브도메인 배포 구조를 로컬에서부터 검증해보려 했으나, **크롬이 `localhost`를 public-suffix처럼 취급해 서브도메인이 `Domain=.localhost` 쿠키를 설정하는 것 자체를 거부**한다는 걸 확인해 되돌렸습니다 (`api.localhost`에서 심은 OAuth2 state 쿠키를 콜백 때 못 찾아 `authorization_request_not_found`로 로그인 자체가 실패했음). 커스텀 서브도메인 + 공유 쿠키가 실제로 동작하는지는 진짜 도메인이 생기거나 `lvh.me`/`nip.io` 같은 실제 등록된 서브도메인 지원 도메인을 쓸 때 검증하면 됩니다.
