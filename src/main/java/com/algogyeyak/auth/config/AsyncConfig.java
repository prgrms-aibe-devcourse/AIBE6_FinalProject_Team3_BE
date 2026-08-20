package com.algogyeyak.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 인증 메일(회원가입 인증번호/비밀번호 재설정 링크) 발송을 비동기로 실행하기 위한 설정.
 *
 * {@code EmailService.send*()}가 동기로 {@code JavaMailSender.send()}를 호출하면 SMTP 왕복
 * 시간(수백ms~수 초)만큼 요청 스레드가 그대로 블로킹된다 - 이 발송이 걸리는 두 엔드포인트(이메일
 * 인증번호 발송/비밀번호 재설정 요청)는 인증 없이 호출 가능해 부하가 몰리기 가장 쉬운 지점이라,
 * 서블릿 스레드풀이 SMTP 지연으로 고갈될 위험이 크다. {@code @Async}로 실제 발송을 이 전용
 * executor로 넘겨 HTTP 응답이 SMTP 왕복을 기다리지 않게 한다.
 *
 * 기본 executor(SimpleAsyncTaskExecutor)는 요청마다 새 스레드를 만들고 상한이 없다 - 메일 발송이
 * 몰릴 때 스레드가 무한정 늘어나는 것 자체가 원래 막으려던 문제(스레드 고갈)를 다른 자리로 옮기는
 * 셈이라 쓰지 않는다. 대신 크기를 제한한 {@link ThreadPoolTaskExecutor}를 이 용도 전용으로 둔다 -
 * 메일 발송은 지연은 감수해도 무한정 쌓이면 안 되는 부가 작업이므로 코어/최대 풀 크기를 한 자리
 * 수로 작게 유지한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor emailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-async-");
        // 기본값(waitForTasksToCompleteOnShutdown=false)이면 재배포/재시작 시 shutdownNow()가 큐에
        // 남은 발송 작업을 실행조차 안 하고 그냥 버린다 - 클라이언트는 이미 200(발송됨)을 받았는데
        // 실제로는 아무 메일도 안 나가고, exceptionally() 콜백도 안 불려 쿨다운 해제조차 안 되며
        // 로그도 안 남는(관측 불가) 조용한 유실이 생긴다. 큐에 남은 작업은 끝까지 실행되도록 대기하되,
        // 무한정 붙잡지 않도록 상한을 둔다(SMTP 타임아웃이 아직 설정돼 있지 않아 개별 발송 자체가
        // 오래 걸릴 수 있음 - 별도로 확인 필요한 사항).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
