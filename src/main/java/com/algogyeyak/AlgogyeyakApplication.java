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
    // backend가 아니라 그 루트 폴더로 잡혀 .env를 못 찾고, 그 결과 DEV_LOGIN_ENABLED 등 .env로만
    // 설정한 값이 조용히 기본값으로 떨어진다(팀원마다 Run Configuration의 working directory를
    // 수동으로 backend로 맞춰야 하는 문제) — working directory가 이미 backend면(.env가 바로
    // 보이면) 아무것도 하지 않고, 루트에서 실행돼 backend/.env가 한 단계 아래에 있는 경우에만
    // spring-dotenv에 그 위치를 알려줘서 실행 방식과 무관하게 항상 .env를 읽도록 한다.
    private static void configureDotenvDirectory() {
        if (System.getProperty("springdotenv.directory") != null) {
            return;
        }

        Path cwd = Path.of("").toAbsolutePath();
        if (Files.exists(cwd.resolve(".env"))) {
            return;
        }

        Path backendEnv = cwd.resolve("backend").resolve(".env");
        if (Files.exists(backendEnv)) {
            System.setProperty("springdotenv.directory", backendEnv.getParent().toString());
        }
    }

}
