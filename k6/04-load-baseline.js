import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login } from './common/auth.js';

// baseline load test: 예상 실사용 트래픽(60명 동시접속) 수준을 5분간 유지했을 때
// 응답시간/에러율이 정상 범위인지 확인한다. 01-read-endpoints.js와 같은 엔드포인트
// 조합을 쓰되, VU 1명짜리 스모크가 아니라 실제 부하를 준다는 점이 다르다. 20명은 5번(stress)
// 결과 기준으로 보면 여유가 너무 많아(부담은 100명부터 시작) baseline치고 가벼웠어서 60명으로
// 올림 - 100명(부담 시작점)보다는 충분히 낮게 유지.
export const options = {
  stages: [
    { duration: '1m', target: 60 }, // 0 -> 60명 램프업
    { duration: '5m', target: 60 }, // 60명 유지
    { duration: '1m', target: 0 },  // 60 -> 0명 램프다운
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

export default function (data) {
  const headers = { Cookie: cookieHeader(data.cookies) };

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
