package com.openat.member.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code BaseTimeEntity}의 @CreatedDate/@LastModifiedDate가 동작하려면
 * JPA Auditing이 활성화되어 있어야 하므로 별도 설정 클래스로 분리해 켜준다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
