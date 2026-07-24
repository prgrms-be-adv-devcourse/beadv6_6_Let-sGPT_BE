package com.openat.chat.infrastructure.persistence;

import static com.openat.chat.application.port.DataQueryCapabilityState.Availability.AVAILABLE;
import static com.openat.chat.application.port.DataQueryCapabilityState.Availability.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.chat.infrastructure.config.ChatQueryDataSourceProperties;
import com.openat.chat.infrastructure.config.ReadModelBootstrapProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalReadModelBootstrapTest {

  private static final String QUERY_PASSWORD = "query-password";

  private final ChatQueryDataSourceProperties dataSourceProperties = configuredDataSource();
  private final ReadModelBootstrapProperties bootstrapProperties = enabledBootstrap();
  private final ReadModelDdlApplier ddlApplier = mock(ReadModelDdlApplier.class);
  private final ReadModelStartupVerifier verifier = mock(ReadModelStartupVerifier.class);

  @Test
  @DisplayName("자동 구성이 꺼져 있으면 조회 계약과 DDL을 건드리지 않는다")
  void run_skipsWhenBootstrapIsDisabled() {
    bootstrapProperties.setEnabled(false);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.run(null);

    assertThat(bootstrap.isCompleted()).isTrue();
    verify(verifier, never()).verifyNow();
    verify(ddlApplier, never()).apply(QUERY_PASSWORD);
  }

  @Test
  @DisplayName("스케줄러가 기동 러너보다 먼저 호출돼도 비활성 설정은 DDL을 실행하지 않는다")
  void retry_skipsDisabledBootstrapBeforeRunner() {
    bootstrapProperties.setEnabled(false);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.retry();

    verify(verifier, never()).verifyNow();
    verify(ddlApplier, never()).apply(QUERY_PASSWORD);
  }

  @Test
  @DisplayName("AI 조회 DB 설정이 없으면 자동 구성을 건너뛴다")
  void run_skipsWhenQueryDataSourceIsNotConfigured() {
    dataSourceProperties.setEnabled(false);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.run(null);

    assertThat(bootstrap.isCompleted()).isTrue();
    verify(verifier, never()).verifyNow();
    verify(ddlApplier, never()).apply(QUERY_PASSWORD);
  }

  @Test
  @DisplayName("이미 유효한 read-model은 DDL을 다시 적용하지 않는다")
  void run_skipsDdlWhenContractIsAlreadyAvailable() {
    when(verifier.verifyNow()).thenReturn(AVAILABLE);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.run(null);

    assertThat(bootstrap.isCompleted()).isTrue();
    verify(ddlApplier, never()).apply(QUERY_PASSWORD);
  }

  @Test
  @DisplayName("조회 계약이 없으면 DDL 적용 후 전용 계정으로 다시 검증한다")
  void run_appliesAndVerifiesMissingContract() {
    when(verifier.verifyNow()).thenReturn(UNAVAILABLE, AVAILABLE);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.run(null);

    assertThat(bootstrap.isCompleted()).isTrue();
    verify(ddlApplier).apply(QUERY_PASSWORD);
  }

  @Test
  @DisplayName("원본 테이블이 아직 없으면 기동을 막지 않고 다음 주기에 다시 적용한다")
  void retry_reconcilesAfterInitialFailure() {
    when(verifier.verifyNow()).thenReturn(UNAVAILABLE, UNAVAILABLE, AVAILABLE);
    doThrow(new IllegalStateException("source table missing"))
        .doNothing()
        .when(ddlApplier)
        .apply(QUERY_PASSWORD);
    LocalReadModelBootstrap bootstrap = bootstrap();

    bootstrap.run(null);
    assertThat(bootstrap.isCompleted()).isFalse();

    bootstrap.retry();

    assertThat(bootstrap.isCompleted()).isTrue();
    verify(ddlApplier, org.mockito.Mockito.times(2)).apply(QUERY_PASSWORD);
  }

  private LocalReadModelBootstrap bootstrap() {
    return new LocalReadModelBootstrap(
        dataSourceProperties, bootstrapProperties, ddlApplier, verifier);
  }

  private static ChatQueryDataSourceProperties configuredDataSource() {
    ChatQueryDataSourceProperties properties = new ChatQueryDataSourceProperties();
    properties.setEnabled(true);
    properties.setUrl("jdbc:postgresql://127.0.0.1:5432/openat");
    properties.setUsername("ai_query_app");
    properties.setPassword(QUERY_PASSWORD);
    return properties;
  }

  private static ReadModelBootstrapProperties enabledBootstrap() {
    ReadModelBootstrapProperties properties = new ReadModelBootstrapProperties();
    properties.setEnabled(true);
    return properties;
  }
}
