package com.openat.member.infrastructure.scheduler;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 1건 익명화를 별도 트랜잭션에서 처리한다.
 *
 * <p>이 로직을 {@link MemberAnonymizeScheduler}의 메서드로 두고 스케줄러가 자기 자신을
 * ({@code this.anonymize(...)}) 호출하면, Spring의 {@code @Transactional}은 AOP 프록시를
 * 거쳐야만 적용되는데 self-invocation은 프록시를 우회해 트랜잭션이 전혀 시작되지 않는다.
 * 그 결과 repository 조회 자체는 되지만(조회 메서드 자체의 짧은 트랜잭션), 그 트랜잭션이
 * 끝나는 즉시 엔티티가 detached 상태가 되어 이후의 {@code member.anonymize()} 필드 변경이
 * dirty checking으로 저장되지 않는다 — 즉 조용히 아무 것도 커밋되지 않는다.
 *
 * <p>별도 빈으로 분리해 스케줄러가 주입받은 프록시를 통해 호출하게 하면 이 문제가 사라진다
 * (order/payment의 outbox 발행기가 스케줄러와 분리된 것과 동일한 이유).
 */
@Component
@RequiredArgsConstructor
public class MemberAnonymizeService {

    private final MemberRepository memberRepository;

    /**
     * @return 실제로 익명화됐으면 true. 배치 조회 시점과 이 트랜잭션이 실제로 실행되는 시점
     *     사이에 상태가 바뀌었을 수 있어(예: 그 사이 복구 후 재탈퇴로 deletedAt이 갱신됨),
     *     원래 배치 조회 조건(deletedAt &lt;= cutoff, 아직 미익명화)을 여기서 다시 검증한다.
     */
    @Transactional
    public boolean anonymize(UUID memberId, LocalDateTime cutoff) {
        Member member = memberRepository.findByIdIncludingDeleted(memberId).orElse(null);
        if (member == null || !member.isEligibleForAnonymization(cutoff)) {
            return false;
        }
        member.anonymize();
        return true;
    }
}
