import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL } from './common/config.js';
import { login, extractAuthCookies, authCookieHeader } from './common/auth.js';

// stress test: 정상 트래픽(baseline 60명)을 넘어서 VU를 100 -> 200 -> 300 -> 400까지
// 단계적으로 올려가며 언제부터 응답시간/에러율이 무너지기 시작하는지 확인한다. t3.small에서
// 몇 명부터 HikariCP 풀이나 JVM 힙이 병목이 되는지가 관심사라, thresholds로
// 테스트를 중단시키지 않고(abortOnFail 없음) 각 단계별 실측값을 그대로 관찰한다.
// 50/100/150은 Grafana(모니터링 스택) 끈 상태에서 이미 완전히 안정적으로 확인됨 - 특히
// Tomcat 기본 최대 스레드(200)가 하나의 구조적 한계선일 가능성이 높아, 그 근처를 오래(3분씩)
// 유지했을 때도 버티는지 보려고 올림.
export const options = {
  stages: [
    { duration: '2m', target: 100 },
    { duration: '3m', target: 100 },
    { duration: '2m', target: 200 },
    { duration: '3m', target: 200 },
    { duration: '2m', target: 300 },
    { duration: '3m', target: 300 },
    { duration: '2m', target: 400 },
    { duration: '3m', target: 400 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    // 중단 조건이 아니라 각 구간 결과 확인용 참고치 - baseline(p95<500ms)보다 넉넉하게 잡았다.
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.05'],
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
