# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# 소스보다 먼저 복사해서, 소스만 바뀌었을 때는 의존성 다운로드 레이어가 캐시되게 한다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon

# bootJar가 실행 가능한 jar와 원본 클래스만 담은 -plain.jar를 함께 만들어내므로,
# COPY 대상이 하나로 고정되게 실행 가능한 jar만 골라 app.jar로 이름을 통일한다.
RUN find build/libs -maxdepth 1 -name "*.jar" ! -name "*-plain.jar" -exec cp {} app.jar \;

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/app.jar ./app.jar
USER app

# docker-compose.yml이 SPRING_PROFILES_ACTIVE=prod를 명시적으로 넘기지만, 이 이미지가
# docker-compose 없이(예: 장애 대응 중 수동 `docker run`) 실행될 경우를 대비한 안전망이다.
# 이 값이 없으면 application.yml의 dev용 기본 시크릿(JWT_SECRET 등)으로 조용히 기동될 수
# 있다 - 이 이미지는 운영 전용이므로 fail-fast 프로필을 이미지 자체의 기본값으로 굳혀둔다.
# docker-compose나 `-e`로 넘긴 값이 있으면 그 값이 우선한다.
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
