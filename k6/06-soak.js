import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

// soak(endurance) test: baseline 수준(30명)의 중간 부하를 길게(기본 30분) 유지해서,
// 짧은 테스트로는 안 보이는 문제 - 커넥션/메모리 누수, 시간이 지날수록 응답시간이 서서히
// 늘어나는 현상 - 를 찾는다. 실제로 오래 관찰하려면 -e SOAK_DURATION=2h 처럼 오버라이드해서
// 늘려서 돌리면 됨(기본값은 CI/로컬에서 부담 없이 돌릴 수 있게 30분으로 잡았다).
const SOAK_DURATION = __ENV.SOAK_DURATION || '30m';

export const options = {
  stages: [
    { duration: '1m', target: 30 },
    { duration: SOAK_DURATION, target: 30 },
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

export default function (data) {
  const headers = { Cookie: authCookieHeader(data.authCookies) };

  const listRes = http.get(`${BASE_URL}/properties?page=0&size=20`, { headers });
  check(listRes, { '매물 목록 200': (r) => r.status === 200 });

  const properties = listRes.json('data.content');
  if (properties && properties.length > 0) {
    const propertyId = properties[0].propertyId;
    const detailRes = http.get(`${BASE_URL}/properties/${propertyId}`, { headers });
    check(detailRes, { '매물 상세 200': (r) => r.status === 200 });
  }

  const checklistListRes = http.get(`${BASE_URL}/checklists?page=0&size=20`, { headers });
  check(checklistListRes, { '체크리스트 목록 200': (r) => r.status === 200 });

  const checklists = checklistListRes.json('data.content');
  const started = checklists && checklists.find((c) => Boolean(c.checklistId));
  if (started) {
    const checklistDetailRes = http.get(`${BASE_URL}/properties/${started.propertyId}/checklists`, { headers });
    check(checklistDetailRes, { '체크리스트 상세 200': (r) => r.status === 200 });
  }

  const meRes = http.get(`${BASE_URL}/auth/me`, { headers });
  check(meRes, { '내 정보 조회 200': (r) => r.status === 200 });

  sleep(1);
}
