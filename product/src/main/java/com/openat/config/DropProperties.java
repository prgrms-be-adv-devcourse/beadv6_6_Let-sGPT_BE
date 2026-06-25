package com.openat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("drop")
public record DropProperties(
    @DefaultValue("5m") Duration warmBefore,
    @DefaultValue("10m") Duration closeMargin,
    @DefaultValue("7d") Duration nullCloseTtl,
    @DefaultValue("1h") Duration idempotencyTtl) {}
