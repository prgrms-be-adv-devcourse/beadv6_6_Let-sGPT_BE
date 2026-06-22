package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.repository.MemberRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberRepository} 포트를 {@link MemberJpaRepository}(Spring Data JPA)로 구현하는 어댑터.
 */
@Repository
@RequiredArgsConstructor
public class MemberRepositoryAdaptor implements MemberRepository {

    private final MemberJpaRepository memberJpaRepository;

    @Override
    public Member save(Member member) {
        return memberJpaRepository.save(member);
    }

    @Override
    public Optional<Member> findById(UUID id) {
        return memberJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return memberJpaRepository.findByEmailAndDeletedAtIsNull(email);
    }

    @Override
    public Optional<Member> findByEmailIncludingDeleted(String email) {
        return memberJpaRepository.findByEmail(email);
    }

    @Override
    public boolean existsByEmail(String email) {
        return memberJpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsByNickname(String nickname) {
        return memberJpaRepository.existsByNickname(nickname);
    }
}
