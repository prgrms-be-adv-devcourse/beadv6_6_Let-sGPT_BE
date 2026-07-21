package com.openat.product.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("이미지 저장 키 생성")
class ImageStorageKeysTest {

  private static final String UUID_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

  @Test
  @DisplayName("대문자 확장자는 소문자로 정규화한다")
  void newKey_uppercaseExtension_normalizesToLowercase() {
    // given
    String originalFilename = "photo.PNG";

    // when
    String key = ImageStorageKeys.newKey(originalFilename);

    // then
    assertThat(key).endsWith(".png");
    assertThat(key.substring(0, key.length() - 4)).matches(UUID_PATTERN);
  }

  @Test
  @DisplayName("slash가 포함된 확장자는 제거해 단일 경로 세그먼트 키를 만든다")
  void newKey_slashInSuffix_omitsExtension() {
    // given
    String originalFilename = "photo.png/evil";

    // when
    String key = ImageStorageKeys.newKey(originalFilename);

    // then
    assertBareKey(key);
  }

  @Test
  @DisplayName("특수문자가 포함된 확장자는 제거해 단일 경로 세그먼트 키를 만든다")
  void newKey_specialCharacterInSuffix_omitsExtension() {
    // given
    String originalFilename = "photo.jp*g";

    // when
    String key = ImageStorageKeys.newKey(originalFilename);

    // then
    assertBareKey(key);
  }

  @Test
  @DisplayName("확장자가 없으면 UUID 단일 경로 세그먼트 키를 만든다")
  void newKey_withoutExtension_returnsBareKey() {
    // given
    String originalFilename = "photo";

    // when
    String key = ImageStorageKeys.newKey(originalFilename);

    // then
    assertBareKey(key);
  }

  @Test
  @DisplayName("10자를 초과한 확장자는 제거해 단일 경로 세그먼트 키를 만든다")
  void newKey_excessiveExtension_omitsExtension() {
    // given
    String originalFilename = "photo.abcdefghijk";

    // when
    String key = ImageStorageKeys.newKey(originalFilename);

    // then
    assertBareKey(key);
  }

  private void assertBareKey(String key) {
    assertThat(key).matches(UUID_PATTERN).doesNotContain("/", "\\");
  }
}
