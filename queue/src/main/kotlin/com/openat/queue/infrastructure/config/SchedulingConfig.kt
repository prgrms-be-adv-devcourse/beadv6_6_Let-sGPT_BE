package com.openat.queue.infrastructure.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 입장 처리 스케줄러(Phase 2)와 만료 대기자 스위퍼(Phase 1)가 공유하는 스케줄링 인프라.
 * product 모듈의 `SchedulingConfig` 관례(전용 스레드풀 + `@Primary`)를 따른다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(QueueProperties::class)
class SchedulingConfig {

    @Bean
    @Primary
    fun taskScheduler(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 2
        scheduler.setThreadNamePrefix("queue-scheduler-")
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.initialize()
        return scheduler
    }
}
