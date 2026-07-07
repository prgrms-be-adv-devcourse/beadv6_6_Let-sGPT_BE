package com.openat.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.profiles.active=test",
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
    })
@Testcontainers
@DisplayName("애플리케이션 컨텍스트")
class SearchApplicationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Test
  @DisplayName("전체 빈 구성으로 컨텍스트가 기동된다")
  void contextLoads() {}
}
