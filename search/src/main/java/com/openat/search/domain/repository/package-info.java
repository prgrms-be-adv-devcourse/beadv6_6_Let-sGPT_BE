/**
 * 도메인 저장소 포트(인터페이스). 애플리케이션·도메인은 이 인터페이스에만 의존하고, 실제 구현(색인·조회 어댑터)은 {@code
 * infrastructure.persistence}가 제공한다(의존성 역전).
 */
package com.openat.search.domain.repository;
