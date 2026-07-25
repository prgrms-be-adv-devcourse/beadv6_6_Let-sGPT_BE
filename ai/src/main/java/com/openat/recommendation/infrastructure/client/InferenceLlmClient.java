package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openat.recommendation.application.port.out.LlmClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class InferenceLlmClient implements LlmClient {

  private final RestClient restClient;
  private final String apiKey;
  private final String model;

  public InferenceLlmClient(
      @Qualifier("inferenceRestClient") RestClient restClient,
      @Value("${inference.api-key}") String apiKey,
      @Value("${inference.model}") String model) {
    this.restClient = restClient;
    this.apiKey = apiKey;
    this.model = model;
  }

  @Override
  public String complete(String prompt) {
    ChatCompletionResponse response = requireBody(
        restClient
            .post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .body(
                new ChatCompletionRequest(
                    model,
                    List.of(new Message("user", prompt)),
                    0.3,
                    1000,
                    new ResponseFormat("json_object")))
            .retrieve()
            .body(ChatCompletionResponse.class),
        "Inference response content is empty");
    if (response.choices() == null
        || response.choices().isEmpty()
        || response.choices().getFirst().message() == null) {
      throw new RestClientException("Inference response content is empty");
    }
    return requireBody(
        response.choices().getFirst().message().content(),
        "Inference response content is empty");
  }

  private record ChatCompletionRequest(
      String model,
      List<Message> messages,
      double temperature,
      int max_tokens,
      ResponseFormat response_format) {}

  private record ResponseFormat(String type) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Message(String role, String content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChatCompletionResponse(List<Choice> choices) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Choice(Message message) {}
}
