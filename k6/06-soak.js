import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login } from './common/auth.js';

// soak(endurance) test: 중간 부하(80명)를 길게(기본 30분) 유지해서, 짧은 테스트로는 안 보이는
// 문제 - 커넥션/메모리 누수, 시간이 지날수록 응답시간이 서서히 늘어나는 현상 - 를 찾는다.
// 30명은 1차 테스트 결과 너무 여유로워서(5번 기준 부담은 100명부터 시작) 80명으로 올림 - 4번
// (baseline 60명)보다는 높고 5번에서 다운이 확인된 150명보다는 충분히 낮게 유지. 실제로 더 오래
// 관찰하려면 -e SOAK_DURATION=2h 처럼 오버라이드해서 늘려서 돌리면 됨(기본값은 CI/로컬에서
// 부담 없이 돌릴 수 있게 30분으로 잡았다).
const SOAK_DURATION = __ENV.SOAK_DURATION || '30m';

export const options = {
  stages: [
    { duration: '1m', target: 80 },
    { duration: SOAK_DURATION, target: 80 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const TEST_EMAIL = __ENV.TEST_EMAIL;
const TEST_PASSWORD = __ENV.TEST_PASSWORD;

export function setup() {
  const res = login(TEST_EMAIL, TEST_PASSWORD);
  return { cookies: res.cookies };
}

function cookieHeader(cookies) {
  return Object.entries(cookies)
    .map(([name, jar]) => `${name}=${jar[0].value}`)
    .join('; ');
}

// VU별로 유지되는 현재 세션 쿠키 - setup()에서 전달받은 값으로 첫 iteration에서 초기화되고,
// 이후 401을 만나면 이 VU 안에서 재로그인한 값으로 갱신된다(module-scope 변수라 같은 VU의
// 다음 iteration에도 그대로 이어짐).
let currentCookies;

// access token(30분)이 SOAK_DURATION(기본 30분) 근처에서 만료될 수 있는데, 30개 VU가
// setup()에서 만든 세션 하나를 공유하는 구조라 /auth/refresh로 갱신하면 안 된다 - 이 앱은
// "유저당 세션 1개" 정책이라(RefreshTokenService), 한 VU가 refresh하면 나머지 VU가 들고 있는
// refresh token이 그 즉시 무효화된다(02-auth-session.js의 concurrentRefresh 시나리오와 동일한
// 경쟁). 대신 재로그인을 쓴다 - access token은 로그아웃 전엔 다른 로그인/refresh와 무관하게
// 독립적으로 유효하므로, 각 VU가 401을 만날 때마다 따로 재로그인해도 서로 방해하지 않는다.
function authenticatedGet(url) {
  let res = http.get(url, { headers: { Cookie: cookieHeader(currentCookies) } });
  if (res.status === 401) {
    currentCookies = login(TEST_EMAIL, TEST_PASSWORD).cookies;
    res = http.get(url, { headers: { Cookie: cookieHeader(currentCookies) } });
  }
  return res;
}

export default function (data) {
  if (!currentCookies) {
    currentCookies = data.cookies;
  }

  const listRes = authenticatedGet(`${BASE_URL}/properties?page=0&size=20`);
  check(listRes, { '매물 목록 200': (r) => r.status === 200 });

  const properties = listRes.json('data.content');
  if (properties && properties.length > 0) {
    const propertyId = properties[0].propertyId;
    const detailRes = authenticatedGet(`${BASE_URL}/properties/${propertyId}`);
    check(detailRes, { '매물 상세 200': (r) => r.status === 200 });
  }

  const checklistListRes = authenticatedGet(`${BASE_URL}/checklists?page=0&size=20`);
  check(checklistListRes, { '체크리스트 목록 200': (r) => r.status === 200 });

  const checklists = checklistListRes.json('data.content');
  const started = checklists && checklists.find((c) => Boolean(c.checklistId));
  if (started) {
    const checklistDetailRes = authenticatedGet(`${BASE_URL}/properties/${started.propertyId}/checklists`);
    check(checklistDetailRes, { '체크리스트 상세 200': (r) => r.status === 200 });
  }

  const meRes = authenticatedGet(`${BASE_URL}/auth/me`);
  check(meRes, { '내 정보 조회 200': (r) => r.status === 200 });

  sleep(1);
}
