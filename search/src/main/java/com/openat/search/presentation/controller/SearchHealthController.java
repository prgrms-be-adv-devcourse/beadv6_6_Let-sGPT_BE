package com.openat.search.presentation.controller;

import com.openat.search.application.usecase.SearchHealthUseCase;
import com.openat.search.presentation.dto.SearchHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프레젠테이션 계층 배선 예시(health). 실제 검색 API(예: {@code GET /api/v1/search?q=...})로 대체한다.
 *
 * <p>컨트롤러는 얇게 유지하고 유스케이스 인터페이스에만 의존한다.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchHealthController {

  private final SearchHealthUseCase searchHealthUseCase;

  @GetMapping("/health")
  public SearchHealthResponse health() {
    return SearchHealthResponse.from(searchHealthUseCase.checkHealth());
  }
}
