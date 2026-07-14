package com.openat.member;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling: OutboxPublisher(@Scheduled)가 outbox_events PENDING 행을 주기적으로 relay하는 데 필수.
@EnableScheduling
@SpringBootApplication
public class MemberApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
