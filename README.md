# 알고계약 (algogyeyak) — Backend

사회초년생과 대학생을 위한 부동산 계약 안전 확인 서비스의 API 서버입니다.

## Stack

- Java 21
- Spring Boot 4.1.0 (Gradle Kotlin DSL, wrapper Gradle 9.5.1)
- Spring Web MVC
- Spring Data JPA — H2(로컬/dev), MySQL(runtime)
- Spring Security + OAuth2 Client + JWT (jjwt)
- springdoc-openapi (Swagger UI)
- Spring Boot Actuator + Micrometer(Prometheus)
- Lombok

## Getting Started

```bash
./gradlew.bat bootRun
```

`application.yml`에 별도 데이터소스 설정이 없어 기본적으로 인메모리 H2로 기동됩니다. MySQL 등 실제 데이터소스 연결은 아직 `application-{dev,prod,test}.yml`에 구성되어 있지 않습니다.

## Scripts

```bash
./gradlew.bat build      # 빌드
./gradlew.bat bootRun    # 로컬 실행
./gradlew.bat test       # 전체 테스트

# 단일 테스트 클래스
./gradlew.bat test --tests "com.ll.algogyeyak.AlgogyeyakApplicationTests"

# 단일 테스트 메서드
./gradlew.bat test --tests "com.ll.algogyeyak.AlgogyeyakApplicationTests.contextLoads"
```

Windows 환경이므로 `gradlew.bat`을 사용합니다.

## Config profiles

`application.yml` + `application-{dev,prod,test}.yml` 4개 파일이 있으며, 현재는 전부 `spring.application.name`만 설정되어 있습니다. 데이터소스, OAuth2 client secret 등 프로필별 값은 아직 채워지지 않았습니다.

## 알아둘 점

메인 애플리케이션 패키지는 `com.algogyeyak`(`AlgogyeyakApplication.java`)인데, 기존 테스트는 `com.ll.algogyeyak`(Gradle `group`과 동일) 아래에 있습니다. 새 클래스를 추가하기 전에 팀 내에서 어느 패키지 컨벤션을 따를지 먼저 확인하세요.

## Current state

Spring Initializr로 생성된 직후 단계입니다. 컨트롤러/서비스/리포지토리/엔티티/시큐리티 설정이 아직 없고, `contextLoads()` 플레이스홀더 테스트만 존재합니다.

## Docs

- [CLAUDE.md](./CLAUDE.md) — AI 코딩 에이전트용 아키텍처/명령어 가이드
- [AGENTS.md](./AGENTS.md) — CLAUDE.md를 가리키는 포인터 (Claude Code 외 다른 AI 코딩 툴용)
