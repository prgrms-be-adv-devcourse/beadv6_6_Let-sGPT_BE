package com.openat.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;

@DisplayName("관리자 AI 조회 전용 데이터소스 설정")
class ChatDataSourceConfigTest {

  private final ChatDataSourceConfig config = new ChatDataSourceConfig();

  @Test
  @DisplayName("조회 풀은 최대 3개·최소 유휴 0개·읽기 전용·3초 연결 제한으로 고정한다")
  void chatQueryDataSource_configured_usesBoundedReadOnlyPool() {
    // given
    ChatQueryDataSourceProperties properties = new ChatQueryDataSourceProperties();
    properties.setEnabled(true);
    properties.setUrl("jdbc:postgresql://127.0.0.1:5432/openat");
    properties.setUsername("ai_query_app");
    properties.setPassword("test-only-password");

    // when
    try (HikariDataSource dataSource = config.chatQueryDataSource(properties)) {
      // then
      assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
      assertThat(dataSource.getMinimumIdle()).isZero();
      assertThat(dataSource.isReadOnly()).isTrue();
      assertThat(dataSource.getConnectionTimeout()).isEqualTo(3_000L);
      assertThat(dataSource.getInitializationFailTimeout()).isEqualTo(-1L);
    }
  }

  @Test
  @DisplayName("주 JPA 데이터소스 팩토리는 Primary를 유지한다")
  void primaryDataSourceFactory_declared_keepsPrimaryBoundary() throws NoSuchMethodException {
    // given
    var method =
        ChatDataSourceConfig.class.getDeclaredMethod(
            "primaryDataSource",
            org.springframework.boot.jdbc.autoconfigure.DataSourceProperties.class);

    // when
    Primary primary = method.getAnnotation(Primary.class);

    // then
    assertThat(primary).isNotNull();
  }
}
