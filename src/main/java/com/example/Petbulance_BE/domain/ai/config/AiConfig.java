package com.example.Petbulance_BE.domain.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /**
     * AI 진단 전용 스레드풀.
     *
     * <p>Tomcat 요청 스레드는 입력 검사만 하고 작업을 여기에 넘긴 뒤 바로 반환된다(SSE).
     * Claude 응답을 읽어 사용자에게 흘려보내는 일은 이 스레드들이 한다.
     *
     * <p>대기열이 없다(SynchronousQueue): 스레드가 모두 일하는 중이면 기다리지 않고 바로 거절(AbortPolicy) -> 503.
     * 즉 이 풀 크기가 AI 진단의 동시 처리 상한(벌크헤드)이다.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiStreamExecutor(AiProperties properties) {
        int size = properties.maxConcurrentStreams();
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                size, size, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-stream-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
