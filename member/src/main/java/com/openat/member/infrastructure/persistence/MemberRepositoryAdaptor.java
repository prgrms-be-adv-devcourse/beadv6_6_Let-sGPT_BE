package com.openat.member.infrastructure.persistence;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

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
    public Optional<Member> findByIdIncludingDeleted(UUID id) {
        return memberJpaRepository.findById(id);
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

    @Override
    public List<Member> findWithdrawnBefore(LocalDateTime cutoff, int limit) {
        return memberJpaRepository.findByDeletedAtLessThanEqualAndAnonymizedAtIsNullOrderByDeletedAtAsc(
                cutoff, PageRequest.of(0, limit));
    }

    @Override
    public int restoreIfWithinGracePeriod(UUID id, LocalDateTime cutoff) {
        return memberJpaRepository.restoreIfWithinGracePeriod(id, cutoff);
    }

    @Override
    public int anonymizeIfEligible(UUID id, LocalDateTime cutoff, LocalDateTime now) {
        return memberJpaRepository.anonymizeIfEligible(id, cutoff, now);
    }
}
