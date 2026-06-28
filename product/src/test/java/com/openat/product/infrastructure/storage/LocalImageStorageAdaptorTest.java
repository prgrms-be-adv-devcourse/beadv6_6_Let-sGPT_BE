package com.openat.product.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.common.exception.BusinessException;
import com.openat.product.domain.error.ProductErrorCode;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("로컬 이미지 저장소")
class LocalImageStorageAdaptorTest {

  @TempDir Path tempDir;
  private LocalImageStorageAdaptor adaptor;

  @BeforeEach
  void setUp() {
    adaptor = new LocalImageStorageAdaptor(tempDir.toString());
  }

  @Test
  @DisplayName("저장한 이미지를 키로 다시 읽으면 동일한 바이트를 반환한다")
  void store_thenLoad_roundtrips() {
    // given
    byte[] content = "image-bytes".getBytes();

    // when
    String key = adaptor.store(content, "photo.PNG");

    // then
    assertThat(key).endsWith(".png");
    assertThat(adaptor.load(key)).isEqualTo(content);
  }

  @Test
  @DisplayName("없는 키를 조회하면 IMAGE_NOT_FOUND 예외를 던진다")
  void load_missing_throwsNotFound() {
    // when & then
    assertThatThrownBy(() -> adaptor.load("missing.png"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
  }

  @Test
  @DisplayName("상위 경로 탈출 키는 저장 디렉터리 밖을 읽지 못하고 IMAGE_NOT_FOUND를 던진다")
  void load_pathTraversal_throwsNotFound() {
    // when & then
    assertThatThrownBy(() -> adaptor.load("../../etc/passwd"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
  }
}
