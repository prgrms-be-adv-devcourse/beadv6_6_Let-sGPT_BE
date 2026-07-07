package com.openat.search.presentation.dto;

import com.openat.search.application.dto.SearchHealthInfo;

/** 프레젠테이션 응답 DTO(~Response). 서비스 반환(~Info)을 외부 표현으로 변환한다. */
public record SearchHealthResponse(String service, String status) {

  public static SearchHealthResponse from(SearchHealthInfo info) {
    return new SearchHealthResponse(info.service(), info.status());
  }
}
