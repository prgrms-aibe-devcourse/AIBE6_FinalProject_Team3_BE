import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './common/config.js';
import { login } from './common/auth.js';

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

// "정확히 같은 순간에 여러 요청이 같은 리소스를 두고 경쟁"해야 재현되는 시나리오라, VU마다
// 정확히 1회씩만 실행하는 per-vu-iterations executor를 쓴다(부하량이 아니라 동시 타이밍이 목적).
//
// 원래는 회원가입 동시 요청(동일 이메일/닉네임) 시나리오도 있었으나, LocalAuthService.signup()에
// 이메일 인증(POST /auth/email-verification/{request,confirm}) 완료가 선행 조건으로 추가되면서
// 제외했다 - 인증 코드가 실제 이메일로만 발송되고 테스트용 우회가 없어 k6에서 자동화할 방법이
// 없다(2026-08-20 기준). signup()의 REQUIRES_NEW 동시성 보호 자체는 코드 리뷰로 이미 검증된 패턴
// (auth 도메인이 원조, risk-analysis/checklist에도 동일 패턴 이식)이라 신뢰하고, 부하 테스트로는
// 더 이상 재확인하지 않는다.
export const options = {
  scenarios: {
    // 체크리스트 생성 동시 요청 - ChecklistService.createOrGetChecklist()에 REQUIRES_NEW +
    // DataIntegrityViolationException 복구 패턴을 적용해(2026-08-20) 이제는 몇 명이 동시에
    // 요청해도 항상 200/201로 정상 처리돼야 한다. 5명으로는 REQUIRES_NEW 격리 자체가 도는지만
    // 확인되고 실제 경쟁 강도는 약해서, 더 확실히 검증하려고 50명으로 올림.
    checklistCreate: {
      executor: 'per-vu-iterations',
      exec: 'checklistCreateScenario',
      vus: 50,
      iterations: 1,
      startTime: '0s',
      maxDuration: '30s',
    },
  },
};

function cookieHeader(cookies) {
  return Object.entries(cookies)
    .map(([name, jar]) => `${name}=${jar[0].value}`)
    .join('; ');
}

// 전체 테스트 시작 전 한 번만 실행 - 로그인 + 체크리스트가 아직 없는 매물을 새로 만든다.
export function setup() {
  const loginRes = login(TEST_EMAIL, TEST_PASSWORD);
  const cookies = loginRes.cookies;
  const headers = { Cookie: cookieHeader(cookies), 'Content-Type': 'application/json' };

  const propertyRes = http.post(`${BASE_URL}/properties`, JSON.stringify({
    title: `[LOADTEST] 동시성 테스트 매물 ${Date.now()}`,
    address: '서울특별시 강남구 테헤란로 123',
    propertyType: 'OFFICETEL',
    transactionType: 'JEONSE',
    deposit: 100000000,
    area: 20.0,
  }), { headers });

  check(propertyRes, { '테스트 매물 생성 200/201': (r) => r.status === 200 || r.status === 201 });
  const propertyId = propertyRes.json('data.propertyId');

  return { cookies, propertyId };
}

export function checklistCreateScenario(data) {
  const headers = { Cookie: cookieHeader(data.cookies) };
  const res = http.post(`${BASE_URL}/properties/${data.propertyId}/checklists`, null, { headers });

  check(res, {
    '체크리스트 생성 200/201(동시 요청에도 정상 처리됨)': (r) => r.status === 200 || r.status === 201,
  });
  console.log(`[checklistCreate] VU=${__VU} status=${res.status}`);
}
