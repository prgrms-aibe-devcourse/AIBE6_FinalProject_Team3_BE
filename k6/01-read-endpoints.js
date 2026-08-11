import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

// 스모크 테스트 단계: VU 1명, 30초만 - 스크립트 자체가 정상 동작하는지만 확인.
// 검증되면 stages를 늘려서(예: 0→20명 ramp-up, 5분 유지) baseline 측정으로 전환.
export const options = {
  vus: 20,
  duration: '30s',
};

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;
// 1-5(관리자 통계)는 ROLE_ADMIN이 있어야 하므로 별도 관리자 계정으로 한 번 더 로그인한다.
// 안 넘기면 1-5는 건너뛴다(다른 항목들은 일반 유저로도 전부 확인 가능).
const TEST_ADMIN_EMAIL = __ENV.TEST_ADMIN_EMAIL;
const TEST_ADMIN_PASSWORD = __ENV.TEST_ADMIN_PASSWORD;

// setup()은 테스트 시작 전 딱 한 번만 실행된다 - 로그인을 VU마다 반복하지 않고,
// 여기서 받은 쿠키를 이후 default() 안에서 재사용한다.
export function setup() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  const adminAuthCookies = (TEST_ADMIN_EMAIL && TEST_ADMIN_PASSWORD)
    ? extractAuthCookies(login(TEST_ADMIN_EMAIL, TEST_ADMIN_PASSWORD))
    : null;
  return { authCookies: extractAuthCookies(res), adminAuthCookies };
}

export default function (data) {
  const headers = { Cookie: authCookieHeader(data.authCookies) };

  // 1-1. 매물 목록 (검색/페이지네이션)
  const listRes = http.get(`${BASE_URL}/properties?page=0&size=20`, { headers });
  check(listRes, {
    '매물 목록 200': (r) => r.status === 200,
    '매물 목록 500ms 이내': (r) => r.timings.duration < 500,
  });

  // 1-2. 매물 상세 - 목록에서 첫 번째 매물 ID를 그대로 사용(하드코딩 방지)
  const properties = listRes.json('data.content');
  if (properties && properties.length > 0) {
    const propertyId = properties[0].propertyId;
    const detailRes = http.get(`${BASE_URL}/properties/${propertyId}`, { headers });
    check(detailRes, {
      '매물 상세 200': (r) => r.status === 200,
      '매물 상세 500ms 이내': (r) => r.timings.duration < 500,
    });
  }

  // 1-3. 내 체크리스트 목록 (페이지네이션)
  const checklistListRes = http.get(`${BASE_URL}/checklists?page=0&size=20`, { headers });
  check(checklistListRes, {
    '체크리스트 목록 200': (r) => r.status === 200,
    '체크리스트 목록 500ms 이내': (r) => r.timings.duration < 500,
  });

  // 1-4. 체크리스트 항목 포함 조회 - 별도 GET /checklists/{id}는 없고, 매물 기준
  // (GET /properties/{propertyId}/checklists)으로 조회한다. 체크리스트가 없으면 404이므로,
  // 목록에서 checklistId가 채워진(이미 시작된) 항목의 propertyId로만 호출한다.
  // checklistId가 없는 경우 JSON에 null이 아니라 필드 자체가 빠진다(@JsonInclude(NON_NULL)) -
  // JS에서는 undefined가 되므로 !== null이 아니라 Boolean으로 존재 여부를 확인해야 한다.
  const checklists = checklistListRes.json('data.content');
  const started = checklists && checklists.find((c) => Boolean(c.checklistId));
  if (started) {
    const checklistDetailRes = http.get(`${BASE_URL}/properties/${started.propertyId}/checklists`, { headers });
    check(checklistDetailRes, {
      '체크리스트 상세 200': (r) => r.status === 200,
      '체크리스트 상세 500ms 이내': (r) => r.timings.duration < 500,
    });
  }

  // 1-5. 관리자 통계 대시보드 - ROLE_ADMIN 필요. adminAuthCookies가 없으면(관리자 계정 env를 안 넘기면) 건너뜀.
  if (data.adminAuthCookies) {
    const adminHeaders = { Cookie: authCookieHeader(data.adminAuthCookies) };
    const statsRes = http.get(`${BASE_URL}/admin/stats/dashboard`, { headers: adminHeaders });
    check(statsRes, {
      '관리자 통계 200': (r) => r.status === 200,
      '관리자 통계 800ms 이내': (r) => r.timings.duration < 800, // 집계 쿼리라 여유를 좀 더 둠
    });
  }

  // 1-6. 내 정보 조회 - 프론트가 보호 페이지마다 부르는 구조라 실질적으로 가장 자주 호출될 엔드포인트
  const meRes = http.get(`${BASE_URL}/auth/me`, { headers });
  check(meRes, {
    '내 정보 조회 200': (r) => r.status === 200,
    '내 정보 조회 300ms 이내': (r) => r.timings.duration < 300, // 단순 조회라 더 빡빡한 기준
  });

  sleep(1);
}
