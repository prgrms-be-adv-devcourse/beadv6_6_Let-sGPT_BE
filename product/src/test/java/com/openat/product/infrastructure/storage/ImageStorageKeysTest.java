package com.openat.product.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("이미지 저장 키 생성")
class ImageStorageKeysTest {

  private static final String STAGING_PREFIX = "staging/";
  private static final String KEY_ID = "550e8400-e29b-41d4-a716-446655440000";
  private static final String FINAL_KEY = KEY_ID + ".png";
  private static final String STAGING_KEY = STAGING_PREFIX + FINAL_KEY;
  private static final String STAGING_KEY_PATTERN =
      "^staging/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png$";

  @Test
  @DisplayName("확장자로 staging 키를 만들면 UUID 앞에 staging 접두사를 붙인다")
  void newStagingKey_extension_returnsStagingUuidKey() {
    // given
    String extension = "png";

    // when
    String stagingKey = ImageStorageKeys.newStagingKey(extension);

    // then
    assertThat(stagingKey).matches(STAGING_KEY_PATTERN);
  }

  @Test
  @DisplayName("올바른 staging UUID 키이면 staging 키로 판별한다")
  void isStagingKey_validStagingKey_returnsTrue() {
    // given
    String stagingKey = STAGING_KEY;

    // when
    boolean result = ImageStorageKeys.isStagingKey(stagingKey);

    // then
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("staging 접두사 뒤가 UUID 형식이 아니면 staging 키가 아니다")
  void isStagingKey_invalidUuid_returnsFalse() {
    // given
    String stagingKey = "staging/not-a-uuid.png";

    // when
    boolean result = ImageStorageKeys.isStagingKey(stagingKey);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("올바른 UUID 단일 경로 키이면 final 키로 판별한다")
  void isFinalKey_validFinalKey_returnsTrue() {
    // given
    String finalKey = FINAL_KEY;

    // when
    boolean result = ImageStorageKeys.isFinalKey(finalKey);

    // then
    assertThat(result).isTrue();
  }

  @Test
  @DisplayName("UUID 형식이 아닌 단일 경로 키이면 final 키가 아니다")
  void isFinalKey_invalidUuid_returnsFalse() {
    // given
    String finalKey = "not-a-uuid.png";

    // when
    boolean result = ImageStorageKeys.isFinalKey(finalKey);

    // then
    assertThat(result).isFalse();
  }

  @Test
  @DisplayName("staging 키에서 접두사만 제거해 final 키를 만든다")
  void toFinalKey_stagingKey_removesPrefix() {
    // given
    String stagingKey = STAGING_KEY;

    // when
    String finalKey = ImageStorageKeys.toFinalKey(stagingKey);

    // then
    assertThat(finalKey).isEqualTo(FINAL_KEY);
  }

  @Test
  @DisplayName("staging 논리 키를 객체 키로 바꾸면 논리 접두사를 스토리지 접두사로 교체한다")
  void toStagingObjectKey_stagingKey_replacesLogicalPrefix() {
    // given
    String stagingKey = STAGING_KEY;
    String storagePrefix = "images/staging/";

    // when
    String objectKey = ImageStorageKeys.toStagingObjectKey(stagingKey, storagePrefix);

    // then
    assertThat(objectKey).isEqualTo("images/staging/" + FINAL_KEY);
  }

  @Test
  @DisplayName("final 논리 키를 객체 키로 바꾸면 스토리지 접두사를 붙인다")
  void toFinalObjectKey_finalKey_addsStoragePrefix() {
    // given
    String finalKey = FINAL_KEY;
    String storagePrefix = "images/final/";

    // when
    String objectKey = ImageStorageKeys.toFinalObjectKey(finalKey, storagePrefix);

    // then
    assertThat(objectKey).isEqualTo("images/final/" + FINAL_KEY);
  }
}
