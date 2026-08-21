import org.gradle.kotlin.dsl.implementation

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ll"
version = "0.0.1-SNAPSHOT"
description = "algogyeyak"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-h2console")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // access token blacklist/refresh token 저장소(auth)에 쓰고, 실거래가 비교 결과 캐싱 등 다른
    // 도메인의 캐싱 연결 준비도 겸한다(그쪽 실제 @Cacheable/캐시 대상 설계는 별도 작업). Lettuce는
    // 연결이 지연 생성이라 Redis가 안 떠 있어도 앱 기동 자체는 영향받지 않는다.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 이메일 인증(회원가입)/비밀번호 재설정 메일 발송용. JavaMailSender는 spring.mail.* 프로퍼티만
    // 채우면 자동 구성된다.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    // 로컬 개발 전용: backend/.env를 자동으로 읽어 Spring 프로퍼티로 노출한다
    // (OS 환경변수가 있으면 그게 항상 우선). 운영 배포에는 포함되지 않는다.
    developmentOnly("me.paulschwarz:springboot4-dotenv:5.1.0")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("com.mysql:mysql-connector-j")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    // 로컬/CI에서 실제 Redis 컨테이너로 issue/rotate/revoke Lua script의 원자성 등 실제 동작을
    // 검증하기 위함 — RefreshTokenServiceTest(Mockito)는 스크립트 앞뒤 자바 분기만 보고, 실제
    // TTL 만료/동시성은 검증하지 못한다.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")

    // Initializr에 없어서 직접 추가해야 하는 것
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0") // Swagger UI

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // S3
    implementation(platform("software.amazon.awssdk:bom:2.25.0"))
    implementation("software.amazon.awssdk:s3")

    // Prometheus
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // MarketComparisonService의 지오코딩 결과 캐시(geocodeCache)용. 이전에는 크기/만료 제한이
    // 없는 순수 ConcurrentHashMap이라 서버를 오래 띄워둘수록(주소 종류가 늘어날수록) 메모리를
    // 무한정 점유하는 구조였다(2026-08-21 멘토링 피드백에서 지적) - Caffeine으로 바꿔
    // maximumSize/expireAfterWrite로 상한을 둔다. 버전은 Spring Boot BOM이 관리한다.
    implementation("com.github.ben-manes.caffeine:caffeine")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
