package com.openat.chat.infrastructure.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openat.chat.application.dto.WeatherForecastQuery;
import com.openat.chat.application.dto.WeatherForecastQuery.ForecastDay;
import com.openat.chat.application.dto.WeatherForecastResult;
import com.openat.chat.application.port.WeatherPort;
import com.openat.chat.application.port.WeatherToolException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class OpenMeteoWeatherAdapter implements WeatherPort {

  public static final String SOURCE_URL = "https://open-meteo.com/en/docs";
  private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
  private static final double COORDINATE_SCALE = 100;
  private static final Pattern SENSITIVE_LOCATION =
      Pattern.compile(
          "(?i)([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|"
              + "\\bORD-[A-Z0-9-]+\\b|"
              + "\\b[0-9A-F]{8}-[0-9A-F]{4}-[1-5][0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}\\b|"
              + "(?:01[016789]|0\\d{1,2})[- ]?\\d{3,4}[- ]?\\d{4})");
  private static final Pattern KOREAN_LOCALITY = Pattern.compile("^[가-힣]{1,20}$");
  private static final Pattern KOREAN_ROAD_NAME = Pattern.compile("^[가-힣]{1,20}(?:로|길)$");
  private static final Pattern LOCAL_ADMINISTRATIVE_AREA =
      Pattern.compile("^[가-힣]{1,20}(?:특별자치시|시|군|구)$");
  private static final Set<String> PROVINCE_NAMES =
      Set.of(
          "서울", "서울특별시", "부산", "부산광역시", "대구", "대구광역시", "인천", "인천광역시", "광주", "광주광역시", "대전", "대전광역시",
          "울산", "울산광역시", "세종", "세종특별자치시", "경기", "경기도", "강원", "강원특별자치도", "충북", "충청북도", "충남", "충청남도",
          "전북", "전북특별자치도", "전남", "전라남도", "경북", "경상북도", "경남", "경상남도", "제주", "제주특별자치도");
  private static final Set<Integer> WEATHER_CODES =
      Set.of(
          0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82,
          85, 86, 95, 96, 99);

  private final RestClient forecastClient;
  private final OpenMeteoProperties properties;
  private final Clock clock;

  public OpenMeteoWeatherAdapter(
      @Qualifier("openMeteoForecastRestClient") RestClient forecastClient,
      OpenMeteoProperties properties,
      Clock clock) {
    this.forecastClient = forecastClient;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public boolean isAvailable() {
    return properties.isEnabled();
  }

  @Override
  public WeatherForecastResult getForecast(WeatherForecastQuery query) {
    if (!isAvailable()) {
      throw new WeatherToolException("WEATHER_UNAVAILABLE", "날씨 도구가 현재 비활성화되어 있어요.", false);
    }
    if (query == null || query.day() == null) {
      throw new WeatherToolException("WEATHER_REQUEST_INVALID", "오늘 또는 내일 중 확인할 날짜가 필요해요.", false);
    }

    Location location = locationFrom(query);
    ForecastDaily daily = fetchForecast(location);
    LocalDate targetDate =
        LocalDate.now(clock.withZone(KOREA_ZONE))
            .plusDays(query.day() == ForecastDay.TOMORROW ? 1 : 0);
    int index = daily.indexOf(targetDate);
    double minimum = daily.valueAt(daily.temperatureMin(), index, "최저 기온");
    double maximum = daily.valueAt(daily.temperatureMax(), index, "최고 기온");
    double precipitation = daily.valueAt(daily.precipitationProbabilityMax(), index, "최대 강수 확률");
    int weatherCode = daily.intValueAt(daily.weatherCode(), index, "날씨 코드");
    validateForecast(minimum, maximum, precipitation, weatherCode);
    Instant queriedAt = clock.instant();

    return new WeatherForecastResult(
        location.displayName(),
        targetDate,
        weatherSummary(weatherCode),
        minimum,
        maximum,
        precipitation,
        Integer.toString(weatherCode),
        queriedAt,
        clothingAdvice(minimum, maximum, precipitation),
        SOURCE_URL);
  }

  private Location locationFrom(WeatherForecastQuery query) {
    String safeLocation = normalizeLocationName(query.location());
    if (!Double.isFinite(query.latitude())
        || !Double.isFinite(query.longitude())
        || query.latitude() < 32
        || query.latitude() > 39
        || query.longitude() < 124
        || query.longitude() > 132) {
      throw new WeatherToolException(
          "WEATHER_LOCATION_INVALID", "날씨 위치 좌표가 대한민국 범위를 벗어났어요.", false);
    }
    return new Location(
        roundCoordinate(query.latitude()), roundCoordinate(query.longitude()), safeLocation);
  }

  private static double roundCoordinate(double coordinate) {
    return Math.round(coordinate * COORDINATE_SCALE) / COORDINATE_SCALE;
  }

  static String normalizeLocationName(String query) {
    if (query == null || query.isBlank()) {
      throw unsafeLocation();
    }
    String stripped = query.strip().replaceAll("\\s+", " ");
    if (stripped.length() > 80 || SENSITIVE_LOCATION.matcher(stripped).find()) {
      throw unsafeLocation();
    }

    String[] parts = stripped.split(" ");
    List<String> selected = new ArrayList<>(2);
    boolean localAreaSelected = false;
    for (String part : parts) {
      String word = part.replaceAll("^[^가-힣]+|[^가-힣]+$", "");
      if (word.isEmpty()) {
        break;
      }
      if (PROVINCE_NAMES.contains(word) && selected.isEmpty()) {
        selected.add(word);
        continue;
      }
      if (LOCAL_ADMINISTRATIVE_AREA.matcher(word).matches() && !localAreaSelected) {
        selected.add(word);
        localAreaSelected = true;
        continue;
      }
      if (parts.length == 1
          && KOREAN_LOCALITY.matcher(word).matches()
          && !KOREAN_ROAD_NAME.matcher(word).matches()) {
        selected.add(word);
      }
      break;
    }
    if (selected.isEmpty()) {
      throw unsafeLocation();
    }
    return String.join(" ", selected);
  }

  private static WeatherToolException unsafeLocation() {
    return new WeatherToolException(
        "WEATHER_LOCATION_INVALID", "날씨는 시·도와 시·군·구 수준의 지역명으로만 물어봐 줘.", false);
  }

  private ForecastDaily fetchForecast(Location location) {
    ForecastResponse response =
        invoke(
            () ->
                forecastClient
                    .get()
                    .uri(
                        builder ->
                            builder
                                .path("/v1/forecast")
                                .queryParam("latitude", location.latitude())
                                .queryParam("longitude", location.longitude())
                                .queryParam(
                                    "daily",
                                    "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                                .queryParam("timezone", "Asia/Seoul")
                                .queryParam("forecast_days", 2)
                                .build())
                    .retrieve()
                    .onStatus(
                        HttpStatusCode::is3xxRedirection, OpenMeteoWeatherAdapter::rejectRedirect)
                    .body(ForecastResponse.class));
    if (response == null || response.daily() == null || !response.daily().hasConsistentSize()) {
      throw schemaError("날씨 예보 응답");
    }
    return response.daily();
  }

  private <T> T invoke(Supplier<T> request) {
    RuntimeException first = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        return request.get();
      } catch (HttpClientErrorException exception) {
        throw new WeatherToolException(
            "WEATHER_PROVIDER_REJECTED", "날씨 제공자가 요청을 처리하지 못했어요.", false, exception);
      } catch (HttpServerErrorException | ResourceAccessException exception) {
        first = exception;
      }
    }
    throw new WeatherToolException(
        "WEATHER_PROVIDER_UNAVAILABLE", "날씨 제공자 응답이 지연되거나 실패했어요.", true, first);
  }

  private static void rejectRedirect(
      org.springframework.http.HttpRequest request,
      org.springframework.http.client.ClientHttpResponse response) {
    throw new WeatherToolException(
        "WEATHER_REDIRECT_REJECTED", "허용되지 않은 날씨 제공자 이동 응답을 거부했어요.", false);
  }

  private void validateForecast(
      double minimum, double maximum, double precipitation, int weatherCode) {
    if (!Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || minimum < -80
        || maximum > 60
        || minimum > maximum
        || !Double.isFinite(precipitation)
        || precipitation < 0
        || precipitation > 100
        || !WEATHER_CODES.contains(weatherCode)) {
      throw schemaError("날씨 예보 값");
    }
  }

  private WeatherToolException schemaError(String subject) {
    return new WeatherToolException(
        "WEATHER_SCHEMA_INVALID", subject + " 형식이 계약과 일치하지 않아요.", false);
  }

  private String weatherSummary(int code) {
    if (code == 0) {
      return "맑음";
    }
    if (code <= 3) {
      return "대체로 흐림";
    }
    if (code == 45 || code == 48) {
      return "안개";
    }
    if (code >= 51 && code <= 67) {
      return "비";
    }
    if (code >= 71 && code <= 77) {
      return "눈";
    }
    if (code >= 80 && code <= 82) {
      return "소나기";
    }
    if (code >= 85 && code <= 86) {
      return "눈 소나기";
    }
    if (code >= 95) {
      return "뇌우";
    }
    return "변화가 있는 날씨";
  }

  private String clothingAdvice(double minimum, double maximum, double precipitation) {
    String temperatureAdvice;
    if (maximum <= 5) {
      temperatureAdvice = "두꺼운 외투와 보온용품이 좋아.";
    } else if (minimum <= 10) {
      temperatureAdvice = "아침저녁에 입을 얇은 외투를 챙겨.";
    } else if (maximum >= 28) {
      temperatureAdvice = "통풍이 잘되는 가벼운 옷이 좋아.";
    } else {
      temperatureAdvice = "기온 변화에 맞춰 겹쳐 입기 좋은 옷을 골라.";
    }
    return precipitation >= 60 ? temperatureAdvice + " 우산도 챙겨." : temperatureAdvice;
  }

  private record Location(double latitude, double longitude, String displayName) {}

  private record ForecastResponse(ForecastDaily daily) {}

  private record ForecastDaily(
      List<String> time,
      @JsonProperty("weather_code") List<Integer> weatherCode,
      @JsonProperty("temperature_2m_max") List<Double> temperatureMax,
      @JsonProperty("temperature_2m_min") List<Double> temperatureMin,
      @JsonProperty("precipitation_probability_max") List<Double> precipitationProbabilityMax) {

    boolean hasConsistentSize() {
      if (time == null
          || weatherCode == null
          || temperatureMax == null
          || temperatureMin == null
          || precipitationProbabilityMax == null
          || time.isEmpty()) {
        return false;
      }
      int size = time.size();
      return size <= 2
          && weatherCode.size() == size
          && temperatureMax.size() == size
          && temperatureMin.size() == size
          && precipitationProbabilityMax.size() == size;
    }

    int indexOf(LocalDate target) {
      for (int index = 0; index < time.size(); index++) {
        try {
          if (target.equals(LocalDate.parse(time.get(index)))) {
            return index;
          }
        } catch (RuntimeException ignored) {
          throw new WeatherToolException(
              "WEATHER_SCHEMA_INVALID", "날씨 예보 날짜 형식이 계약과 일치하지 않아요.", false);
        }
      }
      throw new WeatherToolException("WEATHER_DATE_MISSING", "요청한 날짜의 날씨 예보가 없어요.", false);
    }

    double valueAt(List<Double> values, int index, String label) {
      Double value = values.get(index);
      if (value == null) {
        throw new WeatherToolException("WEATHER_SCHEMA_INVALID", label + " 값이 비어 있어요.", false);
      }
      return value;
    }

    int intValueAt(List<Integer> values, int index, String label) {
      Integer value = values.get(index);
      if (value == null) {
        throw new WeatherToolException("WEATHER_SCHEMA_INVALID", label + " 값이 비어 있어요.", false);
      }
      return value;
    }
  }
}
