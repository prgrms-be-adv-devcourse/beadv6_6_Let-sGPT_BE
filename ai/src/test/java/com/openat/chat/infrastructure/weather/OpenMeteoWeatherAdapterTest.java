package com.openat.chat.infrastructure.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.chat.application.dto.WeatherForecastQuery;
import com.openat.chat.application.dto.WeatherForecastQuery.ForecastDay;
import com.openat.chat.application.port.WeatherToolException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenMeteoWeatherAdapterTest {

  private static final double LATITUDE = 37.4812;
  private static final double LONGITUDE = 127.0365;

  private MockRestServiceServer forecastServer;
  private OpenMeteoWeatherAdapter adapter;

  @BeforeEach
  void setUp() {
    RestClient.Builder forecastBuilder =
        RestClient.builder().baseUrl(WeatherInfrastructureConfig.FORECAST_BASE_URL);
    forecastServer = MockRestServiceServer.bindTo(forecastBuilder).build();
    OpenMeteoProperties properties = new OpenMeteoProperties();
    adapter =
        new OpenMeteoWeatherAdapter(
            forecastBuilder.build(),
            properties,
            Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  @DisplayName("1차 모델이 채운 한국 대표 좌표로 오늘 예보와 공식 출처를 반환한다")
  void getForecast_validKoreaCoordinates_returnsForecast() {
    // given
    expectForecast(validForecast(), 37.48, 127.04);

    // when
    var result = adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY));

    // then
    assertThat(result.location()).isEqualTo("서울특별시 서초구");
    assertThat(result.forecastDate()).isEqualTo(LocalDate.of(2026, 7, 22));
    assertThat(result.temperatureMinC()).isEqualTo(22.1);
    assertThat(result.temperatureMaxC()).isEqualTo(30.5);
    assertThat(result.precipitationProbabilityPercent()).isEqualTo(20);
    assertThat(result.sourceUrl()).isEqualTo("https://open-meteo.com/en/docs");
    forecastServer.verify();
  }

  @Test
  @DisplayName("내일 요청은 같은 대표 좌표에서 다음 날짜 예보를 선택한다")
  void getForecast_tomorrow_returnsNextDayForecast() {
    // given
    expectForecast(validForecast(), 37.48, 127.04);

    // when
    var result = adapter.getForecast(query("서울특별시 서초구", ForecastDay.TOMORROW));

    // then
    assertThat(result.forecastDate()).isEqualTo(LocalDate.of(2026, 7, 23));
    assertThat(result.summary()).isEqualTo("비");
    assertThat(result.precipitationProbabilityPercent()).isEqualTo(70);
    forecastServer.verify();
  }

  @Test
  @DisplayName("상세 주소가 들어와도 결과에는 시·도와 시·군·구 수준의 이름만 남긴다")
  void getForecast_detailedAddress_exposesOnlyAdministrativeArea() {
    // given
    expectForecast(validForecast(), 37.39, 127.11);

    // when
    var result =
        adapter.getForecast(
            new WeatherForecastQuery("경기도 성남시 분당구 판교역로 166", 37.3948, 127.1112, ForecastDay.TODAY));

    // then
    assertThat(result.location()).isEqualTo("경기도 성남시");
    forecastServer.verify();
  }

  @Test
  @DisplayName("연락처나 식별자가 섞인 위치는 날씨 공급자 호출 전에 거부한다")
  void getForecast_sensitiveLocation_rejectsBeforeProviderCall() {
    assertThatThrownBy(
            () ->
                adapter.getForecast(
                    new WeatherForecastQuery(
                        "서울 강남구 010-1234-5678", LATITUDE, LONGITUDE, ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_LOCATION_INVALID");

    forecastServer.verify();
  }

  @Test
  @DisplayName("건물번호가 붙은 단일 도로명은 행정구역명으로 채택하지 않는다")
  void getForecast_singleRoadName_rejectsBeforeProviderCall() {
    assertThatThrownBy(
            () ->
                adapter.getForecast(
                    new WeatherForecastQuery("판교역로166", 37.3948, 127.1112, ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_LOCATION_INVALID");

    forecastServer.verify();
  }

  @Test
  @DisplayName("대한민국 범위를 벗어난 모델 좌표는 공급자 호출 전에 거부한다")
  void getForecast_coordinatesOutsideKorea_rejectsBeforeProviderCall() {
    assertThatThrownBy(
            () ->
                adapter.getForecast(
                    new WeatherForecastQuery("서울특별시 서초구", 35.6764, 139.6500, ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_LOCATION_INVALID");

    forecastServer.verify();
  }

  @Test
  @DisplayName("유한하지 않은 모델 좌표는 공급자 호출 전에 거부한다")
  void getForecast_nonFiniteCoordinates_rejectsBeforeProviderCall() {
    assertThatThrownBy(
            () ->
                adapter.getForecast(
                    new WeatherForecastQuery(
                        "서울특별시 서초구", Double.NaN, LONGITUDE, ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_LOCATION_INVALID");

    forecastServer.verify();
  }

  @Test
  @DisplayName("날짜가 빠진 요청은 오늘로 추측하지 않고 공급자 호출 전에 거부한다")
  void getForecast_missingDay_rejectsBeforeProviderCall() {
    assertThatThrownBy(
            () ->
                adapter.getForecast(
                    new WeatherForecastQuery("서울특별시 서초구", LATITUDE, LONGITUDE, null)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_REQUEST_INVALID");

    forecastServer.verify();
  }

  @Test
  @DisplayName("필수 배열 길이가 다르면 현재 날씨를 추측하지 않고 schema 오류로 끝낸다")
  void getForecast_inconsistentSchema_rejects() {
    // given
    expectForecast(
        """
        {
          "daily": {
            "time": ["2026-07-22", "2026-07-23"],
            "weather_code": [0],
            "temperature_2m_max": [30.5, 31.0],
            "temperature_2m_min": [22.1, 23.0],
            "precipitation_probability_max": [20, 40]
          }
        }
        """,
        37.48,
        127.04);

    // when & then
    assertThatThrownBy(() -> adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_SCHEMA_INVALID");
  }

  @Test
  @DisplayName("WMO catalog에 없는 날씨 코드는 정상 결과로 확정하지 않는다")
  void getForecast_unknownWeatherCode_rejects() {
    // given
    expectForecast(validForecast().replace("[0, 61]", "[98, 61]"), 37.48, 127.04);

    // when & then
    assertThatThrownBy(() -> adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .extracting(error -> ((WeatherToolException) error).code())
        .isEqualTo("WEATHER_SCHEMA_INVALID");
  }

  @Test
  @DisplayName("provider 5xx는 한 번만 재시도하고 두 번째 응답으로 완료한다")
  void getForecast_transientServerError_retriesOnce() {
    // given
    expectForecastFailure(withServerError());
    expectForecast(validForecast(), 37.48, 127.04);

    // when
    var result = adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY));

    // then
    assertThat(result.location()).isEqualTo("서울특별시 서초구");
    forecastServer.verify();
  }

  @Test
  @DisplayName("provider timeout은 한 번만 재시도한 뒤 retryable 오류로 끝낸다")
  void getForecast_repeatedTimeout_returnsRetryableError() {
    // given
    expectForecastFailure(withException(new SocketTimeoutException("timeout")));
    expectForecastFailure(withException(new SocketTimeoutException("timeout")));

    // when & then
    assertThatThrownBy(() -> adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .satisfies(
            error -> {
              WeatherToolException exception = (WeatherToolException) error;
              assertThat(exception.code()).isEqualTo("WEATHER_PROVIDER_UNAVAILABLE");
              assertThat(exception.retryable()).isTrue();
            });
    forecastServer.verify();
  }

  @Test
  @DisplayName("provider 4xx는 재시도하지 않고 비재시도 오류로 끝낸다")
  void getForecast_clientError_doesNotRetry() {
    // given
    expectForecastFailure(withBadRequest());

    // when & then
    assertThatThrownBy(() -> adapter.getForecast(query("서울특별시 서초구", ForecastDay.TODAY)))
        .isInstanceOf(WeatherToolException.class)
        .satisfies(
            error -> {
              WeatherToolException exception = (WeatherToolException) error;
              assertThat(exception.code()).isEqualTo("WEATHER_PROVIDER_REJECTED");
              assertThat(exception.retryable()).isFalse();
            });
    forecastServer.verify();
  }

  private WeatherForecastQuery query(String location, ForecastDay day) {
    return new WeatherForecastQuery(location, LATITUDE, LONGITUDE, day);
  }

  private void expectForecast(String body, double latitude, double longitude) {
    forecastServer
        .expect(requestTo(startsWith("https://api.open-meteo.com/v1/forecast")))
        .andExpect(queryParam("latitude", Double.toString(latitude)))
        .andExpect(queryParam("longitude", Double.toString(longitude)))
        .andExpect(queryParam("timezone", "Asia/Seoul"))
        .andExpect(queryParam("forecast_days", "2"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private void expectForecastFailure(
      org.springframework.test.web.client.ResponseCreator responseCreator) {
    forecastServer
        .expect(requestTo(startsWith("https://api.open-meteo.com/v1/forecast")))
        .andRespond(responseCreator);
  }

  private String validForecast() {
    return """
        {
          "daily": {
            "time": ["2026-07-22", "2026-07-23"],
            "weather_code": [0, 61],
            "temperature_2m_max": [30.5, 31.0],
            "temperature_2m_min": [22.1, 23.0],
            "precipitation_probability_max": [20, 70]
          }
        }
        """;
  }
}
