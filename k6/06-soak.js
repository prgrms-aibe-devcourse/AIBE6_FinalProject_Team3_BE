import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

// soak(endurance) test: 중간 부하(150명)를 길게(기본 30분) 유지해서, 짧은 테스트로는 안 보이는
// 문제 - 커넥션/메모리 누수, 시간이 지날수록 응답시간이 서서히 늘어나는 현상 - 를 찾는다.
// 80명은 Grafana 끈 상태에서 30분 내내 완전히 안정적(힙도 깔끔한 톱니, 누수 없음)으로 확인돼
// 150으로 올림 - 5번(stress)에서 150명 3분 유지는 이미 안전한 걸로 확인됐으니, 그 수준을 30분
// 동안 오래 유지해도 괜찮은지 보는 게 자연스러운 다음 단계. 실제로 더 오래 관찰하려면
// -e SOAK_DURATION=2h 처럼 오버라이드해서 늘려서 돌리면 됨(기본값은 CI/로컬에서 부담 없이
// 돌릴 수 있게 30분으로 잡았다).
const SOAK_DURATION = __ENV.SOAK_DURATION || '30m';

export const options = {
  stages: [
    { duration: '1m', target: 150 },
    { duration: SOAK_DURATION, target: 150 },
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
  return { authCookies: extractAuthCookies(res) };
}

// VU별로 유지되는 현재 세션 쿠키 - setup()에서 전달받은 값으로 첫 iteration에서 초기화되고,
// 이후 401을 만나면 이 VU 안에서 재로그인한 값으로 갱신된다(module-scope 변수라 같은 VU의
// 다음 iteration에도 그대로 이어짐).
let currentAuthCookies;

// access token(30분)이 SOAK_DURATION(기본 30분) 근처에서 만료될 수 있는데, 30개 VU가
// setup()에서 만든 세션 하나를 공유하는 구조라 /auth/refresh로 갱신하면 안 된다 - 이 앱은
// "유저당 세션 1개" 정책이라(RefreshTokenService), 한 VU가 refresh하면 나머지 VU가 들고 있는
// refresh token이 그 즉시 무효화된다(02-auth-session.js의 concurrentRefresh 시나리오와 동일한
// 경쟁). 대신 재로그인을 쓴다 - access token은 로그아웃 전엔 다른 로그인/refresh와 무관하게
// 독립적으로 유효하므로, 각 VU가 401을 만날 때마다 따로 재로그인해도 서로 방해하지 않는다.
function authenticatedGet(url) {
  let res = http.get(url, { headers: { Cookie: authCookieHeader(currentAuthCookies) } });
  if (res.status === 401) {
    currentAuthCookies = extractAuthCookies(login(TEST_EMAIL, TEST_PASSWORD));
    res = http.get(url, { headers: { Cookie: authCookieHeader(currentAuthCookies) } });
  }
  return res;
}

export default function (data) {
  if (!currentAuthCookies) {
    currentAuthCookies = data.authCookies;
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
