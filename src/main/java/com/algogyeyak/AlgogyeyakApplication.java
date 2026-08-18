package com.algogyeyak;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlgogyeyakApplication {

    public static void main(String[] args) {
        // 앱 전체에 타임존을 명시적으로 고정한다 - 배포 JVM의 기본 타임존이 KST가 아니면(클라우드
        // 컨테이너 기본값은 흔히 UTC), LocalDate.now()/LocalDateTime.now()에 의존하는 모든 곳
        // (AdminStatsService의 "오늘" 기준 기본 조회기간, JPA Auditing의 createdAt 등)이 최대
        // 9시간 어긋난다. SpringApplication.run()보다 먼저(어떤 날짜 계산도 일어나기 전에) 설정해야
        // 앱 전체에 일관되게 적용된다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(AlgogyeyakApplication.class, args);
    }

}
