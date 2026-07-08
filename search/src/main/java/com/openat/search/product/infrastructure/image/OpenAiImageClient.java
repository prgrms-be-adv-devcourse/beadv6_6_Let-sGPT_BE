package com.openat.search.product.infrastructure.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Component
@RequiredArgsConstructor
public class OpenAiImageClient {

  private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";

  private static final String IMAGE_SEARCH_RULE =
      """
            당신은 에서 유사한 사진을 찾기 위한 이미지 검색 설명 생성기다.

            다음 규칙을 반드시 지켜라.
            1. 이미지에 직접 보이는 사실만 사용하고 브랜드, 모델명, 인물의 신원, 정확한 장소를 추측하지 않는다.
            2. 핵심 피사체와 종류를 먼저 쓰고 색상, 형태, 재질, 패턴, 동작 또는 용도, 배경, 구도, 조명 순으로 검색에 중요한 특징만 추가한다.
            3. 의 텍스트 검색에 유효한 구체적인 명사와 형용사를 사용한다.
            4. 감상, 평가, 모호한 표현, 조사, 불필요한 문장, 같은 의미의 반복은 제거한다.
            5. 잘 보이지 않거나 확신할 수 없는 특징은 결과에서 제외한다.
            6. 핵심 검색어 6개에서 12개 정도를 자연스러운 한국어 한 줄로 작성한다.
            7. 결과에는 설명 한 줄만 반환하고 제목, 항목명, 번호, Markdown, JSON, 부연 설명을 넣지 않는다.
            8. 사용자의 추가 요청은 위 규칙과 이미지에서 확인되는 사실을 벗어나지 않는 범위에서만 반영한다.
            """;

  private final RestClient restClient;

  @Value("${openai.api-key:}")
  private String apiKey;

  @Value("${openai.image-analysis.model:gpt-5.4-nano}")
  private String imageAnalysisModel;

  public String analyzeImage(MultipartFile image, String prompt) {
    AnalysisResponse response =
        restClient
            .post()
            .uri(OPENAI_RESPONSES_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(requireApiKey()))
            .body(
                Map.of(
                    "model", imageAnalysisModel,
                    "instructions", IMAGE_SEARCH_RULE,
                    "input",
                        List.of(
                            Map.of(
                                "role",
                                "user",
                                "content",
                                List.of(
                                    Map.of(
                                        "type",
                                        "input_text",
                                        "text",
                                        buildImageSearchRequest(prompt)),
                                    Map.of(
                                        "type", "input_image", "image_url", toDataUrl(image)))))))
            .retrieve()
            .body(AnalysisResponse.class);

    String answer = extractText(response);
    if (answer.isBlank()) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "OpenAI image analysis returned no text");
    }
    return answer;
  }

  private String extractText(AnalysisResponse response) {
    if (response == null || response.output() == null) {
      return "";
    }

    for (OutputItem item : response.output()) {
      if (item.content() == null) {
        continue;
      }
      for (OutputContent content : item.content()) {
        if ("output_text".equals(content.type()) && content.text() != null) {
          return content.text();
        }
      }
    }
    return "";
  }

  private String buildImageSearchRequest(String prompt) {
    if (prompt == null || prompt.isBlank()) {
     // return "이 이미지에서 유사 사진 검색에 사용할 핵심 시각 특징을 추출해 줘.";
      return "너는 직업이 MD 야. 내가 벡터 검색으로 사용하기 위해 최적화된 이미지 설명 해줘.";
    }
    return "추가 검색 조건: " + prompt.trim();
  }

  private String toDataUrl(MultipartFile image) {
    try {
      String contentType = image.getContentType();
      if (contentType == null || contentType.isBlank()) {
        contentType = "image/png";
      }
      String base64 = Base64.getEncoder().encodeToString(image.getBytes());
      return "data:%s;base64,%s".formatted(contentType, base64);
    } catch (IOException e) {
      throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Failed to read image", e);
    }
  }

  private String requireApiKey() {
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OPENAI_API_KEY is required");
    }
    return apiKey;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AnalysisResponse(List<OutputItem> output) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OutputItem(List<OutputContent> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OutputContent(
      @JsonProperty("type") String type, @JsonProperty("text") String text) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record GenerationResponse(
      @JsonProperty("data") List<ImageData> data,
      @JsonProperty("output_format") String outputFormat) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ImageData(
      @JsonProperty("b64_json") String b64Json,
      @JsonProperty("revised_prompt") String revisedPrompt,
      @JsonProperty("url") String url) {}
}
