import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, CSRF_HEADERS } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

// 3-1과 3-2 둘 다 "정확히 같은 순간에 여러 요청이 같은 리소스를 두고 경쟁"해야 재현되는
// 시나리오라, VU마다 정확히 1회씩만 실행하는 per-vu-iterations executor를 쓴다(부하량이
// 아니라 동시 타이밍이 목적).
export const options = {
  scenarios: {
    // 3-1. 체크리스트 생성 동시 요청 - ChecklistService.createOrGetChecklist()가
    // check-then-act(findByUserIdAndPropertyId → 없으면 save)인데 DataIntegrityViolationException에
    // 대한 방어가 없다(risk-analysis에서 고쳤던 것과 동일한 패턴이 여기는 아직 안 고쳐진 상태) -
    // 그래서 아래 checklistCreate의 결과를 "200이어야 함"으로 단정하지 않고 상태코드를 그대로
    // 기록한다. 500이 나오면 실제로 버그가 재현된 것.
    checklistCreate: {
      executor: 'per-vu-iterations',
      exec: 'checklistCreateScenario',
      vus: 20,
      iterations: 1,
      startTime: '0s',
      maxDuration: '30s',
    },
    // 3-2. 회원가입 동시 요청(동일 이메일/닉네임) - LocalAuthService.signup()은 REQUIRES_NEW +
    // DataIntegrityViolationException 복구 패턴이 이미 적용돼 있어(이번 세션에 risk-analysis에
    // 도입한 것과 같은 패턴, auth 도메인이 원조), 정확히 1명만 성공(200)하고 나머지는 409
    // (AUTH_EMAIL_ALREADY_EXISTS 또는 AUTH_NICKNAME_ALREADY_EXISTS)로 정상 처리되는지 확인한다.
    // 500이 나오면 이 패턴이 실제로는 안 지켜지고 있다는 뜻.
    duplicateSignup: {
      executor: 'per-vu-iterations',
      exec: 'signupScenario',
      vus: 20,
      iterations: 1,
      startTime: '30s', // checklistCreate와 시간대를 분리해서 결과를 헷갈리지 않게
      maxDuration: '30s',
    },
  },
};

// 전체 테스트 시작 전 한 번만 실행 - 로그인 + 체크리스트가 아직 없는 매물을 새로 만들고,
// 회원가입 경쟁에 쓸 유니크한 이메일/닉네임도 이때 미리 정해둔다(재실행해도 안 겹치게
// Date.now() 사용).
export function setup() {
  const loginRes = login(TEST_EMAIL, TEST_PASSWORD);
  const authCookies = extractAuthCookies(loginRes);
  const headers = { Cookie: authCookieHeader(authCookies), 'Content-Type': 'application/json', ...CSRF_HEADERS };

  // PropertyService.register()가 "같은 유저 + 같은 거래유형 + 같은 도로명주소"면
  // PROPERTY_DUPLICATE(409)로 막는다 - title만 Date.now()로 바꾸고 주소를 고정해두면, 이
  // 스크립트를 같은 계정으로 두 번째 실행하는 순간부터 매번 중복으로 막힌다. 도로명 번지수를
  // 매 실행마다 바꿔서 다른 도로명주소로 지오코딩되게 한다(테헤란로는 번지수 폭이 넓어 100~499
  // 사이 대부분이 유효하게 resolve됨).
  const streetNumber = 100 + (Date.now() % 400);
  const propertyRes = http.post(`${BASE_URL}/properties`, JSON.stringify({
    title: `[LOADTEST] 동시성 테스트 매물 ${Date.now()}`,
    address: `서울특별시 강남구 테헤란로 ${streetNumber}`,
    propertyType: 'OFFICETEL',
    transactionType: 'JEONSE',
    deposit: 100000000,
    area: 20.0,
  }), { headers });

  check(propertyRes, { '테스트 매물 생성 200/201': (r) => r.status === 200 || r.status === 201 });
  const propertyId = propertyRes.json('data.propertyId');

  const runId = Date.now();
  const signupEmail = `loadtest_${runId}@example.com`;
  const signupNickname = `lt${runId}`.slice(0, 20);

  return { authCookies, propertyId, signupEmail, signupNickname };
}

// 3-1
export function checklistCreateScenario(data) {
  const headers = { Cookie: authCookieHeader(data.authCookies), ...CSRF_HEADERS };
  const res = http.post(`${BASE_URL}/properties/${data.propertyId}/checklists`, null, { headers });

  check(res, {
    '체크리스트 생성 500 아님(버그 미재현)': (r) => r.status < 500,
  });
  console.log(`[checklistCreate] VU=${__VU} status=${res.status}`);
}

// 3-2
export function signupScenario(data) {
  const res = http.post(`${BASE_URL}/auth/signup`, JSON.stringify({
    email: data.signupEmail,
    password: 'Test1234!',
    nickname: data.signupNickname,
  }), { headers: { 'Content-Type': 'application/json', ...CSRF_HEADERS } });

  check(res, {
    // AuthController.signup()은 성공 시 200이 아니라 201(CREATED)을 반환한다.
    '회원가입 201(승자) 또는 409(중복, 정상 처리됨)': (r) => r.status === 201 || r.status === 409,
    '회원가입 500이 아님': (r) => r.status < 500,
  });
  console.log(`[duplicateSignup] VU=${__VU} status=${res.status}`);
}
