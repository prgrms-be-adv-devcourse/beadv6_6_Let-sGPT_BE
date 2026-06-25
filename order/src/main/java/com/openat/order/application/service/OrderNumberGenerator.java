package com.openat.order.application.service;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter PREFIX_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).withZone(Clock.systemUTC().getZone());

    public String generate(Clock clock) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return "ORD-" + PREFIX_FORMATTER.withZone(clock.getZone()).format(clock.instant()) + "-" + suffix;
    }
}
