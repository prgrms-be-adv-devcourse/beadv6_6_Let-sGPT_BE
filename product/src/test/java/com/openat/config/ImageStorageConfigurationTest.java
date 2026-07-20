package com.openat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.infrastructure.storage.LocalImageStorageAdaptor;
import com.openat.product.infrastructure.storage.S3ImageStorageAdaptor;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("이미지 저장소 조건부 배선")
class ImageStorageConfigurationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("저장소 프로퍼티가 없으면 로컬 어댑터만 등록한다")
  void imageStorage_propertyMissing_registersLocalOnly() {
    // given
    ApplicationContextRunner contextRunner = contextRunner();

    // when & then
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ImageStorageUseCase.class);
          assertThat(context).hasSingleBean(LocalImageStorageAdaptor.class);
          assertThat(context).doesNotHaveBean(S3ImageStorageAdaptor.class);
        });
  }

  @Test
  @DisplayName("저장소 프로퍼티가 local이면 로컬 어댑터만 등록한다")
  void imageStorage_local_registersLocalOnly() {
    // given
    ApplicationContextRunner contextRunner =
        contextRunner().withPropertyValues("product.image.storage=local");

    // when & then
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ImageStorageUseCase.class);
          assertThat(context).hasSingleBean(LocalImageStorageAdaptor.class);
          assertThat(context).doesNotHaveBean(S3ImageStorageAdaptor.class);
        });
  }

  @Test
  @DisplayName("저장소 프로퍼티가 s3이면 S3 어댑터만 등록한다")
  void imageStorage_s3_registersS3Only() {
    // given
    ApplicationContextRunner contextRunner =
        contextRunner()
            .withSystemProperties("aws.region=ap-northeast-2")
            .withPropertyValues("product.image.storage=s3", "product.image.s3.bucket=test-bucket");

    // when & then
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ImageStorageUseCase.class);
          assertThat(context).hasSingleBean(S3ImageStorageAdaptor.class);
          assertThat(context).doesNotHaveBean(LocalImageStorageAdaptor.class);
        });
  }

  @Test
  @DisplayName("S3 bucket이 없으면 명시적 설정 검증으로 컨텍스트 기동에 실패한다")
  void imageStorage_s3WithoutBucket_failsValidation() {
    // given
    ApplicationContextRunner contextRunner =
        contextRunner()
            .withSystemProperties("aws.region=ap-northeast-2")
            .withPropertyValues("product.image.storage=s3");

    // when & then
    contextRunner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(BindValidationException.class)
              .hasStackTraceContaining("bucket");
        });
  }

  private ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            LocalImageStorageAdaptor.class, S3ImageStorageAdaptor.class, S3StorageConfig.class)
        .withPropertyValues("product.image.local-dir=" + tempDir);
  }
}
