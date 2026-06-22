package com.openat.member.domain.repository;

import com.openat.member.domain.model.Member;
import java.util.Optional;
import java.util.UUID;

/**
 * 영속성 기술(JPA)에 의존하지 않는 도메인 포트.
 * 구현체는 {@code infrastructure.persistence.MemberRepositoryAdaptor} 참고.
 */
public interface MemberRepository {

    Member save(Member member);

    Optional<Member> findById(UUID id);

    Optional<Member> findByEmail(String email);

    /** 탈퇴(논리적 삭제) 여부와 무관하게 전체에서 조회. 로그인 시 탈퇴 계정 판별용. */
    Optional<Member> findByEmailIncludingDeleted(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);
}
