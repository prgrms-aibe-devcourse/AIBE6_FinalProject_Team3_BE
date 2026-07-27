package com.algogyeyak.global.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * spring-dotenv는 JVM working directory 기준으로 .env를 찾는다. IntelliJ가 이 저장소의 상위(루트)
 * 폴더를 프로젝트로 열었을 때 자동 생성되는 Run Configuration은 working directory가 backend가
 * 아니라 그 루트 폴더로 잡혀 backend/.env를 못 찾고, 그 결과 DEV_LOGIN_ENABLED 등 .env로만 설정한
 * 값이 조용히 기본값으로 떨어진다(팀원마다 Run Configuration의 working directory를 수동으로
 * backend로 맞춰야 하는 문제) — backend/.env가 실제로 있으면(=working directory가 루트) 그 위치를
 * 항상 우선해서 spring-dotenv에 알려준다. 루트에도 우연히 .env가 있을 수 있는데, 그걸 backend/.env
 * 보다 먼저 확인해버리면 이 문제가 그대로 재발하므로 존재 여부와 무관하게 backend/.env를 항상 먼저
 * 확인한다. working directory가 이미 backend 자체라면(= backend/backend/.env는 없음) spring-dotenv의
 * 기본 동작(CWD)에 맡긴다.
 *
 * spring-dotenv(springboot4-dotenv)의 실제 로딩 지점인 Boot4DotenvEnvironmentPostProcessor는
 * Ordered를 구현하지 않아 정렬 시 최저 우선순위로 취급된다 — 이 클래스가 springdotenv.directory를
 * 그보다 먼저 세팅해둬야 의미가 있으므로 HIGHEST_PRECEDENCE로 명시한다.
 */
public class DotenvDirectoryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String DIRECTORY_PROPERTY = "springdotenv.directory";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (System.getProperty(DIRECTORY_PROPERTY) != null) {
            return;
        }

        Path cwd = Path.of("").toAbsolutePath();
        Path backendEnv = cwd.resolve("backend").resolve(".env");
        if (Files.exists(backendEnv)) {
            System.setProperty(DIRECTORY_PROPERTY, backendEnv.getParent().toString());
        }
    }
}
