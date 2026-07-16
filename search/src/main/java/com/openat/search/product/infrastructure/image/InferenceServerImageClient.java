package com.openat.search.product.infrastructure.image;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/** OpenAI Responses API 규격으로 자체 추론 서버의 이미지 분석을 호출한다. */
@Component
public class InferenceServerImageClient {

  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

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
  private final URI responsesUri;
  private final String apiKey;
  private final String imageAnalysisModel;

  public InferenceServerImageClient(
      RestClient restClient,
      @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${openai.api-key:}") String apiKey,
      @Value("${openai.image-analysis.model:gpt-5.4-nano}") String imageAnalysisModel) {
    this.restClient = restClient;
    this.responsesUri = endpoint(baseUrl, "responses");
    this.apiKey = apiKey;
    this.imageAnalysisModel = imageAnalysisModel;
  }

  public String analyzeImage(MultipartFile image, String prompt) {
    AnalysisResponse response =
        restClient
            .post()
            .uri(responsesUri)
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
          INTERNAL_SERVER_ERROR, "Inference server image analysis returned no text");
    }
    return answer;
  }

  private String extractText(AnalysisResponse response) {
    if (response == null || response.output() == null) {
      return "";
    }

    for (OutputItem item : response.output()) {
      if (item == null || item.content() == null) {
        continue;
      }
      for (OutputContent content : item.content()) {
        if (content != null && "output_text".equals(content.type()) && content.text() != null) {
          return content.text();
        }
      }
    }
    return "";
  }

  private String buildImageSearchRequest(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return "너는 고도의 기술을 가진 상품 MD 직업을 가졌어. 검색에 최적화된 이미지 설명 해줘.(다양하게 검색어가 나오게 많이 표현해줘)";
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
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "Inference server API key is required");
    }
    return apiKey;
  }

  private static URI endpoint(String baseUrl, String path) {
    String normalized = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return URI.create(normalized + "/" + path);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record AnalysisResponse(List<OutputItem> output) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OutputItem(List<OutputContent> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OutputContent(
      @JsonProperty("type") String type, @JsonProperty("text") String text) {}
}
