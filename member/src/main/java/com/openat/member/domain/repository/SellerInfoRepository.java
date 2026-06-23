package com.openat.member.domain.repository;

import com.openat.member.domain.model.SellerInfo;
import java.util.Optional;
import java.util.UUID;

/**
 * 영속성 기술(JPA)에 의존하지 않는 도메인 포트.
 * 구현체는 {@code infrastructure.persistence.SellerInfoRepositoryAdaptor} 참고.
 *
 * <p>member-SellerInfo는 1:N이지만, 논리적 삭제(soft delete)되지 않은(활성) 행은
 * 회원당 항상 0개 또는 1개만 존재한다는 불변식을 비즈니스 로직(application 계층)에서 보장한다.
 */
public interface SellerInfoRepository {

    SellerInfo save(SellerInfo sellerInfo);

    /** 논리적 삭제되지 않은 것 중 가장 최신 1건. 활성 행이 여러 개라도 안전하게 최신 것만 반환. */
    Optional<SellerInfo> findActiveByMemberId(UUID memberId);

    boolean existsActiveByMemberId(UUID memberId);
}
