package com.openat.chat.application.port;

import com.openat.chat.application.dto.WeatherForecastQuery;
import com.openat.chat.application.dto.WeatherForecastResult;

public interface WeatherPort {

  boolean isAvailable();

  WeatherForecastResult getForecast(WeatherForecastQuery query);
}
