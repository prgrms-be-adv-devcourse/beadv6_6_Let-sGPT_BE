package com.openat.member.infrastructure.scheduler;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.repository.MemberRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴(논리 삭제) 후 30일이 지난 회원의 email/nickname을 익명화한다.
 *
 * <p>탈퇴 자체는 {@code Member.withdraw()}(deletedAt만 채움)로 이미 처리돼 있고, 이 스케줄러는
 * 그 유예기간이 끝난 뒤 "재가입 시 중복 체크에 안 걸리게" email/nickname을 치환하는 역할만
 * 한다(물리 삭제 아님 — 행은 그대로 남고 deletedAt도 유지된다).
 *
 * <p>order의 {@code OutboxCleanupScheduler}와 같은 성격의 작업(초 단위 정확도가 필요 없는
 * 하루 단위 하우스키핑)이라 그 컨벤션을 따라 매일 새벽 cron으로 실행한다 — payment/outbox
 * 발행처럼 초 단위로 자주 도는 폴링(fixedDelay)과는 다른 종류의 스케줄이다.
 *
 * <p>조회는 {@code Pageable}로 상한(BATCH_SIZE)을 둬 적체가 쌓여도 한 tick이 무한정 길어지지
 * 않게 하고, 항목별로 독립된 트랜잭션에서 처리해 한 건이 실패해도 나머지 배치에 영향이 없게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberAnonymizeScheduler {

    private static final int BATCH_SIZE = 100;
    private static final Duration GRACE_PERIOD = Duration.ofDays(30);

    private final MemberRepository memberRepository;

    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void anonymizeWithdrawnMembers() {
        LocalDateTime cutoff = LocalDateTime.now().minus(GRACE_PERIOD);
        List<Member> targets = memberRepository.findWithdrawnBefore(cutoff, BATCH_SIZE);
        for (Member member : targets) {
            try {
                anonymize(member.getId());
            } catch (RuntimeException exception) {
                log.error("[MemberAnonymizeScheduler] 익명화 실패, 다음 주기에 재시도: memberId={}",
                        member.getId(), exception);
            }
        }
        if (!targets.isEmpty()) {
            log.info("[MemberAnonymizeScheduler] 탈퇴 회원 익명화 처리. count={}, cutoff={}",
                    targets.size(), cutoff);
        }
    }

    // id로 다시 조회해 독립된 트랜잭션에서 처리한다 — 배치 조회 시점의 스냅샷을 그대로 쓰지 않고,
    // 그 사이 상태가 바뀌었을 가능성(예: 유예기간 중 사용자가 직접 복구)을 다시 확인한다.
    @Transactional
    public void anonymize(UUID memberId) {
        Member member = memberRepository.findByIdIncludingDeleted(memberId).orElse(null);
        if (member == null || !member.isDeleted()) {
            return;
        }
        member.anonymize();
    }
}
