package com.openat.search.application.usecase;

import com.openat.search.application.dto.SearchHealthInfo;

/**
 * 애플리케이션 계층의 유스케이스 인터페이스(포트). 프레젠테이션은 이 인터페이스에만 의존하고, 구현은 {@code application.service}가 제공한다.
 *
 * <p>클린 아키텍처 계층 배선 예시(health)다. 실제 검색 유스케이스로 대체한다.
 */
public interface SearchHealthUseCase {

  SearchHealthInfo checkHealth();
}
