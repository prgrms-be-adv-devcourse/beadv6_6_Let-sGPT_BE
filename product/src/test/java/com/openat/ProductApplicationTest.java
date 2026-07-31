package com.openat;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.product.infrastructure.kafka.ProductOutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.profiles.active=test",
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.kafka.bootstrap-servers=localhost:9092",
      "spring.kafka.listener.auto-startup=false",
      "product.image.s3.bucket=test-image-bucket",
      "product.image.s3.staging-prefix=images/staging/",
      "product.image.s3.final-prefix=images/final/",
      "product.image.s3.endpoint-override=http://localhost:9000",
      "product.image.s3.access-key=test-access-key",
      "product.image.s3.secret-key=test-secret-key"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("애플리케이션 컨텍스트")
class ProductApplicationTest {

  @Autowired private ApplicationContext applicationContext;

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Test
  @DisplayName("전체 빈 구성으로 컨텍스트가 기동된다")
  void contextLoads() {}

  @Test
  @DisplayName("상품 outbox relay와 스케줄링 후처리기가 함께 기동된다")
  void outboxRelaySchedulingEnabled() {
    assertThat(applicationContext.getBean(ProductOutboxRelay.class)).isNotNull();
    assertThat(applicationContext.getBean(ScheduledAnnotationBeanPostProcessor.class))
        .isNotNull();
  }
}
