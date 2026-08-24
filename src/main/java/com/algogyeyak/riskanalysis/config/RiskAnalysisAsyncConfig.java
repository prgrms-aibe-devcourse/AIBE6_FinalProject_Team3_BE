package com.algogyeyak.riskanalysis.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 매물 등록/수정 후 위험 신호·전세가율을 재계산하는 {@link com.algogyeyak.riskanalysis.service.RiskRecalculationService}를
 * 비동기로 실행하기 위한 전용 executor.
 *
 * {@code RiskRecalculationService.onPropertyUpdated()}가 동기로 실행되면, 그 안에서
 * {@code FakeListingSignalService.checkAndSave()}가 국토부 실거래가 API를 최대 6~12회(전세 시세비교
 * + 매매 시세비교) 순차 호출하고 건별 카카오 지오코딩까지 수행한다. 이게 매물 등록/수정 요청 스레드
 * 안에서 그대로 블로킹되어, 사용자가 등록/수정 버튼을 누를 때마다 응답이 그만큼 지연된다(전수조사 성능
 * 감사 결과, 2026-08-24). {@code AsyncConfig.emailTaskExecutor()}와 동일한 이유로 전용 bounded
 * executor를 둔다 - 기본 {@code SimpleAsyncTaskExecutor}는 요청마다 새 스레드를 무제한으로 만들어서,
 * 재계산이 몰리면 스레드 고갈이라는 원래 문제를 다른 자리로 옮기는 셈이라 쓰지 않는다.
 *
 * 재계산 실패는 이미 {@code RiskRecalculationService}가 자체적으로 예외를 잡아 로그만 남기므로(매물
 * 수정 자체를 실패시키지 않는다는 요구사항), 비동기 실행 중 예외가 나도 애플리케이션에 영향이 없다.
 */
@Configuration
public class RiskAnalysisAsyncConfig {

    @Bean
    public Executor riskRecalculationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("risk-recalc-async-");
        // AsyncConfig.emailTaskExecutor()와 동일한 이유 - 재배포/재시작 시 큐에 남은 재계산 작업이
        // 조용히 유실되지 않도록 끝까지 대기하되, 무한정 붙잡지 않도록 상한을 둔다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
