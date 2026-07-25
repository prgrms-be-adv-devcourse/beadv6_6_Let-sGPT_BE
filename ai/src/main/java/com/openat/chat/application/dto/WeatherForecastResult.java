package com.openat.chat.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record WeatherForecastResult(
    String location,
    LocalDate forecastDate,
    String summary,
    double temperatureMinC,
    double temperatureMaxC,
    double precipitationProbabilityPercent,
    String weatherCode,
    Instant queriedAt,
    String clothingAdvice,
    String sourceUrl) {}
