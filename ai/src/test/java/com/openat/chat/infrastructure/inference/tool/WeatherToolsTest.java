package com.openat.chat.infrastructure.inference.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.WeatherForecastQuery;
import com.openat.chat.application.dto.WeatherForecastQuery.ForecastDay;
import com.openat.chat.application.dto.WeatherForecastResult;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.application.port.WeatherPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

@ExtendWith(MockitoExtension.class)
@DisplayName("Spring AI 날씨 도구")
class WeatherToolsTest {

  @Mock WeatherPort weatherPort;
  @Mock ChatEventSink sink;

  private WeatherTools tools;
  private ToolContext toolContext;

  @BeforeEach
  void setUp() {
    tools = new WeatherTools(weatherPort);
    toolContext =
        new ToolContext(
            Map.of(
                AdminToolExecutionContext.KEY,
                new AdminToolExecutionContext(UUID.randomUUID(), "서초 날씨는 어때", sink)));
  }

  @Test
  @DisplayName("1차 모델이 채운 지역명과 대표 좌표를 날씨 포트에 그대로 전달한다")
  void getWeatherForecast_modelCoordinates_forwardsToWeatherPort() {
    // given
    given(weatherPort.isAvailable()).willReturn(true);
    given(weatherPort.getForecast(any()))
        .willReturn(
            new WeatherForecastResult(
                "서울특별시 서초구",
                LocalDate.of(2026, 7, 24),
                "맑음",
                24.1,
                31.2,
                10,
                "0",
                Instant.parse("2026-07-24T03:00:00Z"),
                "가벼운 옷이 좋아.",
                "https://open-meteo.com/en/docs"));

    // when
    AdminToolResult result =
        tools.getWeatherForecast("서울특별시 서초구", 37.4812, 127.0365, ForecastDay.TODAY, toolContext);

    // then
    ArgumentCaptor<WeatherForecastQuery> queryCaptor =
        ArgumentCaptor.forClass(WeatherForecastQuery.class);
    verify(weatherPort).getForecast(queryCaptor.capture());
    assertThat(queryCaptor.getValue())
        .extracting(
            WeatherForecastQuery::location,
            WeatherForecastQuery::latitude,
            WeatherForecastQuery::longitude,
            WeatherForecastQuery::day)
        .containsExactly("서울특별시 서초구", 37.4812, 127.0365, ForecastDay.TODAY);
    assertThat(result.status()).isEqualTo(AdminToolResult.Status.SUCCESS);
  }
}
