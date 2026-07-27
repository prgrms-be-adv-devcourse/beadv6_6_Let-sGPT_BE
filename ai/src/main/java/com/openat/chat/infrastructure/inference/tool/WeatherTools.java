package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.dto.WeatherForecastQuery;
import com.openat.chat.application.dto.WeatherForecastQuery.ForecastDay;
import com.openat.chat.application.dto.WeatherForecastResult;
import com.openat.chat.application.port.WeatherPort;
import com.openat.chat.application.port.WeatherToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {

  private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);

  private final WeatherPort weatherPort;

  public WeatherTools(WeatherPort weatherPort) {
    this.weatherPort = weatherPort;
  }

  @Tool(name = "getWeatherForecast", description = "한국 지역의 오늘 또는 내일 실제 날씨 예보를 대표 좌표로 조회한다.")
  public AdminToolResult getWeatherForecast(
      @ToolParam(description = "질문 지역을 정규화한 시·도와 시·군·구 수준의 한국 행정구역명") String location,
      @ToolParam(description = "해당 행정구역의 WGS84 대표 위도. 근삿값 허용") double latitude,
      @ToolParam(description = "해당 행정구역의 WGS84 대표 경도. 근삿값 허용") double longitude,
      @ToolParam(description = "TODAY 또는 TOMORROW") ForecastDay day,
      ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      if (!weatherPort.isAvailable()) {
        return context.completed(
            AdminToolResult.failed("WEATHER_UNAVAILABLE", "날씨 도구가 현재 비활성화되어 있어요."));
      }
      WeatherForecastResult result =
          weatherPort.getForecast(new WeatherForecastQuery(location, latitude, longitude, day));
      return context.completed(
          AdminToolResult.success(
              new WeatherFacts(
                  result.location(),
                  result.forecastDate().toString(),
                  result.summary(),
                  result.temperatureMinC(),
                  result.temperatureMaxC(),
                  result.precipitationProbabilityPercent(),
                  result.clothingAdvice(),
                  result.sourceUrl())));
    } catch (WeatherToolException exception) {
      return context.completed(AdminToolResult.failed(exception.code(), exception.getMessage()));
    } catch (IllegalArgumentException exception) {
      return context.completed(
          AdminToolResult.failed("WEATHER_REQUEST_INVALID", exception.getMessage()));
    } catch (RuntimeException exception) {
      log.warn("날씨 도구 실패 errorType={}", exception.getClass().getSimpleName());
      return context.completed(
          AdminToolResult.failed("WEATHER_PROVIDER_UNAVAILABLE", "날씨 예보를 가져오지 못했어요."));
    }
  }

  public record WeatherFacts(
      String location,
      String forecastDate,
      String summary,
      double temperatureMinC,
      double temperatureMaxC,
      double precipitationProbabilityPercent,
      String clothingAdvice,
      String sourceUrl) {}
}
