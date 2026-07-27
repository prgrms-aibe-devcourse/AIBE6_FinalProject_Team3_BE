package com.algogyeyak;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AlgogyeyakApplication {

    public static void main(String[] args) {
        configureDotenvDirectory();
        SpringApplication.run(AlgogyeyakApplication.class, args);
    }

    // spring-dotenv는 JVM working directory 기준으로 .env를 찾는다. IntelliJ가 이 저장소의
    // 상위(루트) 폴더를 프로젝트로 열었을 때 자동 생성되는 Run Configuration은 working directory가
    // backend가 아니라 그 루트 폴더로 잡혀 backend/.env를 못 찾고, 그 결과 DEV_LOGIN_ENABLED 등
    // .env로만 설정한 값이 조용히 기본값으로 떨어진다(팀원마다 Run Configuration의 working
    // directory를 수동으로 backend로 맞춰야 하는 문제) — backend/.env가 실제로 있으면(=working
    // directory가 루트) 그 위치를 항상 우선해서 spring-dotenv에 알려준다. 루트에도 우연히(혹은
    // 다른 도구가 생성해서) .env가 있을 수 있는데, 그걸 backend/.env보다 먼저 확인해버리면 이
    // 문제가 그대로 재발하므로 존재 여부와 무관하게 backend/.env를 항상 먼저 확인한다.
    // working directory가 이미 backend 자체라면(= backend/backend/.env는 없음) spring-dotenv의
    // 기본 동작(CWD)에 맡긴다.
    private static void configureDotenvDirectory() {
        if (System.getProperty("springdotenv.directory") != null) {
            return;
        }

        Path cwd = Path.of("").toAbsolutePath();
        Path backendEnv = cwd.resolve("backend").resolve(".env");
        if (Files.exists(backendEnv)) {
            System.setProperty("springdotenv.directory", backendEnv.getParent().toString());
        }
    }

}
