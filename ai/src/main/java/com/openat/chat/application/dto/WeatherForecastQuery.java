package com.openat.chat.application.dto;

public record WeatherForecastQuery(
    String location, double latitude, double longitude, ForecastDay day) {

  public enum ForecastDay {
    TODAY,
    TOMORROW
  }
}
