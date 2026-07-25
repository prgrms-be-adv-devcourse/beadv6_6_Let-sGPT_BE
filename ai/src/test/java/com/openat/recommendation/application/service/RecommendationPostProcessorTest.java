package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationPostProcessorTest {

  private final RecommendationPostProcessor processor =
      new RecommendationPostProcessor(new ObjectMapper());

  @Test
  void process_withValidJson_parsesSections() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var result =
        processor.process(
            "{\"sections\":[{\"title\":\"추천\",\"items\":[2,1]}]}",
            List.of(first, second));

    assertThat(result)
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("추천");
              assertThat(section.productIds()).containsExactly(second, first);
            });
  }

  @Test
  void process_whenResponseIsNotJson_throwsSoCallerCanFallback() {
    assertThatThrownBy(() -> processor.process("not-json", List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void process_whenSectionsFieldIsMissing_throwsSoCallerCanFallback() {
    assertThatThrownBy(() -> processor.process("{}", List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void process_whenSectionHasNoItems_skipsSectionAndReturnsEmpty() {
    assertThat(processor.process("{\"sections\":[{\"title\":\"x\"}]}", List.of())).isEmpty();
  }

  @Test
  void process_acceptsNumericStringsAndSkipsMalformedItems() {
    UUID valid = UUID.randomUUID();
    String json =
        """
        {"sections":[{"title":"추천","items":["1","not-a-number"]}]}
        """;

    var result = processor.process(json, List.of(valid));

    assertThat(result)
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("추천");
              assertThat(section.productIds()).containsExactly(valid);
            });
  }

  @Test
  void process_whenOneSectionHasNullFields_skipsOnlyThatSection() {
    UUID valid = UUID.randomUUID();
    String json =
        """
        {"sections":[{"title":null,"items":[1]},{"title":"추천","items":[1]}]}
        """;

    var result = processor.process(json, List.of(valid));

    assertThat(result)
        .singleElement()
        .satisfies(section -> assertThat(section.title()).isEqualTo("추천"));
  }

  @Test
  void process_whenResponseIsWrappedInCodeFence_stripsFenceBeforeParsing() {
    UUID id = UUID.randomUUID();
    String fenced =
        "```json\n{\"sections\":[{\"title\":\"추천\",\"items\":[1]}]}\n```";

    var result = processor.process(fenced, List.of(id));

    assertThat(result)
        .singleElement()
        .satisfies(section -> assertThat(section.productIds()).containsExactly(id));
  }

  @Test
  void process_whenResponseIsWrappedInSingleLineCodeFence_stripsFenceBeforeParsing() {
    UUID id = UUID.randomUUID();
    String fenced = "```json{\"sections\":[{\"title\":\"추천\",\"items\":[1]}]}```";

    var result = processor.process(fenced, List.of(id));

    assertThat(result)
        .singleElement()
        .satisfies(section -> assertThat(section.productIds()).containsExactly(id));
  }

  @Test
  void process_dropsOutOfRangeIndicesAndDeduplicatesIndicesGlobally() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    String json =
        """
        {"sections":[
          {"title":"첫째","items":[1,0,21,-1]},
          {"title":"둘째","items":[1,2]},
          {"title":"빈 행","items":[0,21,-1]}
        ]}
        """;

    var result = processor.process(json, List.of(first, second));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).productIds()).containsExactly(first);
    assertThat(result.get(1).productIds()).containsExactly(second);
  }

  @Test
  void process_truncatesTitleToThirtyCharactersAndSkipsBlankTitles() {
    UUID longTitleId = UUID.randomUUID();
    UUID blankTitleId = UUID.randomUUID();
    UUID whitespaceTitleId = UUID.randomUUID();
    String longTitle = "가".repeat(31);
    String json =
        """
        {"sections":[
          {"title":"%s","items":[1]},
          {"title":"","items":[2]},
          {"title":"   ","items":[3]}
        ]}
        """
            .formatted(longTitle);

    var result = processor.process(json, List.of(longTitleId, blankTitleId, whitespaceTitleId));

    assertThat(result)
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.title()).isEqualTo("가".repeat(30));
              assertThat(section.productIds()).containsExactly(longTitleId);
            });
  }

  @Test
  void process_truncatesTitleByCodePointsWithoutSplittingSurrogatePairs() {
    UUID id = UUID.randomUUID();
    String emojiTitle = "😀".repeat(31);
    String json = "{\"sections\":[{\"title\":\"" + emojiTitle + "\",\"items\":[1]}]}";

    var result = processor.process(json, List.of(id));

    assertThat(result)
        .singleElement()
        .satisfies(
            section -> {
              String title = section.title();
              assertThat(title.codePointCount(0, title.length())).isEqualTo(30);
              assertThat(title).isEqualTo("😀".repeat(30));
            });
  }
}
