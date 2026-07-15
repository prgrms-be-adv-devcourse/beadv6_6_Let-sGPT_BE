package com.openat.settlement.infrastructure.scheduler;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 정산 대사 배치의 다중 replica 중복 실행 방지 — payment 모듈의 동명 클래스와 동일 최소 구현(Postgres advisory lock).
// 정식 ShedLock(Redis, 7-14 plan D5)이 들어오면 이 클래스는 걷어내고 @SchedulerLock으로 교체 예정.
@Component
public class PgAdvisoryLock {

    private final JdbcTemplate jdbcTemplate;

    public PgAdvisoryLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryLock(long key) {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, key);
        return Boolean.TRUE.equals(acquired);
    }

    public void unlock(long key) {
        jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, key);
    }
}
