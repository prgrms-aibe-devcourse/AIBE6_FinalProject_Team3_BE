# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```
# build
./gradlew.bat build

# run the app locally
./gradlew.bat bootRun

# run all tests
./gradlew.bat test

# run a single test class
./gradlew.bat test --tests "com.ll.algogyeyak.AlgogyeyakApplicationTests"

# run a single test method
./gradlew.bat test --tests "com.ll.algogyeyak.AlgogyeyakApplicationTests.contextLoads"
```

Use `gradlew.bat` (Windows). On the wrapper, Gradle 9.5.1 is pinned via `gradle/wrapper/gradle-wrapper.properties`.

## Architecture

- **Framework**: Spring Boot 4.1.0, Java 21 toolchain, group `com.ll`, artifact `algogyeyak`.
- **Web layer**: `spring-boot-starter-webmvc` (servlet MVC, not WebFlux).
- **Persistence**: `spring-boot-starter-data-jpa`, with H2 (`spring-boot-h2console`) for local/dev and MySQL (`mysql-connector-j`) as the runtime driver — expect environment-specific datasource config to live in the `application-{profile}.yml` files.
- **Auth**: `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-client` for OAuth2 login, plus `jjwt` (api/impl/jackson, 0.12.6) for issuing/validating JWTs. Security config and JWT filter classes don't exist yet — when adding them, wire OAuth2 login and JWT-based session handling together rather than picking one.
- **API docs**: `springdoc-openapi-starter-webmvc-ui` — Swagger UI is available once controllers exist.
- **Observability**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus` for metrics/health endpoints.
- **Lombok**: enabled via `compileOnly` + `annotationProcessor` (and test equivalents) — expected for entities/DTOs.
- **Config profiles**: `application.yml` plus `application-{dev,prod,test}.yml`, selected via Spring profiles. All four currently only set `spring.application.name`; profile-specific values (datasource, OAuth2 client secrets, etc.) have not been added yet.

### Known inconsistency

The main application package is `com.algogyeyak` (`AlgogyeyakApplication.java`), but the existing test lives under `com.ll.algogyeyak` (matching the Gradle `group`). Confirm which package convention the team intends before adding new classes — don't propagate the mismatch by guessing.

## Current state

The backend is at the Spring Initializr stage: one empty `@SpringBootApplication` class, one placeholder `contextLoads()` test, and no controllers, services, repositories, entities, or security config yet.

See [README.md](./README.md) for stack overview and getting-started instructions.
