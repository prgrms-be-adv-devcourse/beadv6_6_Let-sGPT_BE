package com.openat.chat.infrastructure.persistence;

import com.openat.chat.application.port.DataQueryCapabilityState.Availability;
import com.openat.chat.infrastructure.config.ChatQueryDataSourceProperties;
import com.openat.chat.infrastructure.config.ReadModelBootstrapProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("local")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalReadModelBootstrap implements ApplicationRunner {

  private final ChatQueryDataSourceProperties dataSourceProperties;
  private final ReadModelBootstrapProperties bootstrapProperties;
  private final ReadModelDdlApplier ddlApplier;
  private final ReadModelStartupVerifier verifier;
  private final AtomicBoolean completed = new AtomicBoolean();
  private final AtomicBoolean applying = new AtomicBoolean();

  public LocalReadModelBootstrap(
      ChatQueryDataSourceProperties dataSourceProperties,
      ReadModelBootstrapProperties bootstrapProperties,
      ReadModelDdlApplier ddlApplier,
      ReadModelStartupVerifier verifier) {
    this.dataSourceProperties = dataSourceProperties;
    this.bootstrapProperties = bootstrapProperties;
    this.ddlApplier = ddlApplier;
    this.verifier = verifier;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!bootstrapProperties.isEnabled()) {
      completed.set(true);
      log.info("로컬 AI read-model 자동 구성이 비활성 상태야.");
      return;
    }
    if (!dataSourceProperties.isConfigured()) {
      completed.set(true);
      log.info("AI 조회 DB 설정이 없어 로컬 read-model 자동 구성을 건너뛰어.");
      return;
    }
    reconcile();
  }

  @Scheduled(fixedDelayString = "${chat.data.bootstrap.retry-delay:15s}")
  void retry() {
    if (bootstrapProperties.isEnabled()
        && dataSourceProperties.isConfigured()
        && !completed.get()) {
      reconcile();
    }
  }

  boolean isCompleted() {
    return completed.get();
  }

  private void reconcile() {
    if (!applying.compareAndSet(false, true)) {
      return;
    }
    try {
      if (verifier.verifyNow() == Availability.AVAILABLE) {
        completed.set(true);
        return;
      }

      ddlApplier.apply(dataSourceProperties.getPassword());
      if (verifier.verifyNow() == Availability.AVAILABLE) {
        completed.set(true);
        log.info("로컬 AI read-model 자동 구성과 검증을 완료했어.");
      } else {
        log.warn("로컬 AI read-model을 적용했지만 조회 계약 검증이 아직 통과하지 못했어. 다시 시도할게.");
      }
    } catch (RuntimeException exception) {
      log.warn(
          "로컬 AI read-model 자동 구성을 아직 완료하지 못했어. 원본 도메인 테이블 기동 후 다시 시도할게. cause={}",
          exception.getClass().getSimpleName());
    } finally {
      applying.set(false);
    }
  }
}
