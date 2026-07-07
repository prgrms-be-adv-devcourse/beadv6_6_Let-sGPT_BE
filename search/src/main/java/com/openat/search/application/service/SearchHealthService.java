package com.openat.search.application.service;

import com.openat.search.application.dto.SearchHealthInfo;
import com.openat.search.application.usecase.SearchHealthUseCase;
import org.springframework.stereotype.Service;

@Service
public class SearchHealthService implements SearchHealthUseCase {

  @Override
  public SearchHealthInfo checkHealth() {
    return new SearchHealthInfo("search-service", "UP");
  }
}
