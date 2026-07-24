package com.openat.chat.infrastructure.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatQueryDataSourceProperties.class)
public class ChatDataSourceConfig {

  static final int QUERY_MAXIMUM_POOL_SIZE = 3;
  static final int QUERY_MINIMUM_IDLE = 0;
  static final long QUERY_CONNECTION_TIMEOUT_MILLIS = 3_000L;
  private static final long QUERY_VALIDATION_TIMEOUT_MILLIS = 1_000L;
  private static final String UNCONFIGURED_JDBC_URL =
      "jdbc:postgresql://127.0.0.1:1/ai_query_unavailable";

  @Bean("primaryDataSourceProperties")
  @Primary
  @ConfigurationProperties("spring.datasource")
  public DataSourceProperties primaryDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean("dataSource")
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public HikariDataSource primaryDataSource(
      @Qualifier("primaryDataSourceProperties") DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean("chatQueryDataSource")
  public HikariDataSource chatQueryDataSource(ChatQueryDataSourceProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("chat-query");
    config.setJdbcUrl(properties.isConfigured() ? properties.getUrl() : UNCONFIGURED_JDBC_URL);
    config.setUsername(properties.getUsername());
    config.setPassword(properties.getPassword());
    config.setMaximumPoolSize(QUERY_MAXIMUM_POOL_SIZE);
    config.setMinimumIdle(QUERY_MINIMUM_IDLE);
    config.setReadOnly(true);
    config.setConnectionTimeout(QUERY_CONNECTION_TIMEOUT_MILLIS);
    config.setValidationTimeout(QUERY_VALIDATION_TIMEOUT_MILLIS);
    config.setInitializationFailTimeout(-1L);
    return new HikariDataSource(config);
  }

  @Bean("chatQueryJdbcTemplate")
  public NamedParameterJdbcTemplate chatQueryJdbcTemplate(
      @Qualifier("chatQueryDataSource") DataSource dataSource) {
    return new NamedParameterJdbcTemplate(dataSource);
  }

  @Bean("chatQueryTransactionManager")
  public PlatformTransactionManager chatQueryTransactionManager(
      @Qualifier("chatQueryDataSource") DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }
}
