package com.openat.member.infrastructure.scheduler;

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
 * 별도 빈으로 분리해 스케줄러가 주입받은 프록시를 통해 호출하게 하면 이 문제가 사라진다
 * (order/payment의 outbox 발행기가 스케줄러와 분리된 것과 동일한 이유).
 *
 * <p>실제 갱신은 엔티티를 조회해 메모리에서 바꾸고 저장하는 방식이 아니라
 * {@link MemberRepository#anonymizeIfEligible}의 원자적 조건부 UPDATE로 한다. 이 회원 행은
 * 로그인 복구({@code MemberService.restore()})도 동시에 건드릴 수 있는데, "조회 후 판단해서
 * 저장"하는 방식이면 둘 중 나중에 커밋되는 쪽이 먼저 커밋된 상대 변경을 덮어쓰는
 * lost-update가 생긴다. 원자적 UPDATE는 그 순간의 DB 실제 상태를 직접 재검증하므로
 * 이 문제가 없다(둘 중 하나만 반영되고, 나머지는 조용히 0건으로 스킵됨).
 */
@Component
@RequiredArgsConstructor
public class MemberAnonymizeService {

    private final MemberRepository memberRepository;

    /** @return 실제로 익명화됐으면 true, 조건에 안 맞아(이미 복구됨 등) 스킵됐으면 false. */
    @Transactional
    public boolean anonymize(UUID memberId, LocalDateTime cutoff) {
        int updated = memberRepository.anonymizeIfEligible(memberId, cutoff, LocalDateTime.now());
        return updated > 0;
    }
}
