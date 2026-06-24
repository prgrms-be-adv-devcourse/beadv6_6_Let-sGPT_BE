package com.openat.settlement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 정산 배치/스케줄/Kafka 인프라 설정입니다.
 *
 * 기존 Payment Service API 조회 방식(OpenFeign)은 제거했습니다.
 * 결제/환불 정산 재료 적재는 Kafka Consumer가 담당합니다.
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(databaseType = "postgres", tablePrefix = "settlement.batch_")
@EnableScheduling
@EnableKafka
public class SettlementBatchInfrastructureConfig {

    /**
     * 월 정산 partition 병렬 처리 개수입니다.
     *
     * application.yml:
     * settlement.batch.grid-size
     */
    @Value("${settlement.batch.grid-size}")
    private int gridSize;

    /**
     * 판매자별 월 정산 partition을 병렬 처리할 스레드 풀입니다.
     */
    @Bean(name = "settlementTaskExecutor")
    public TaskExecutor settlementTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(gridSize);
        executor.setMaxPoolSize(gridSize*2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("settlement-worker-");
        executor.initialize();

        return executor;
    }

    /*
        판매자 정산 - 로컬 테스트
     */
    @Bean(name = "localBatchLaunchExecutor")
    public TaskExecutor localBatchLaunchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(gridSize);
        executor.setMaxPoolSize(gridSize*2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("local-batch-launcher-");
        executor.initialize();

        return executor;
    }
}
