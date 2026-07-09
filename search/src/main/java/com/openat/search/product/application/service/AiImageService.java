package com.openat.search.product.application.service;

import com.openat.search.product.infrastructure.image.OpenAiImageClient;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiImageService {

  private final OpenAiImageClient openAiImageClient;

  @Transactional(readOnly = true)
  public AiImageAnalyzeResponse analyze(MultipartFile image, String prompt) {
    if (image == null || image.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is required");
    }

    String usedPrompt = normalizeAnalyzePrompt(prompt);
    String answer = openAiImageClient.analyzeImage(image, usedPrompt);
    return new AiImageAnalyzeResponse(usedPrompt, answer);
  }

  private String normalizeAnalyzePrompt(String prompt) {
    return (prompt == null || prompt.isBlank())
        ? "너는 직업이 MD 야. 벡터 검색으로 사용하기 위해 최적화된 이미지 분석 해줘."
        : prompt.trim();
  }

  private String normalizeGeneratePrompt(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "prompt is required");
    }
    return prompt.trim();
  }

  private String normalizeSize(String size) {
    return (size == null || size.isBlank()) ? "1024x1024" : size.trim();
  }
}
