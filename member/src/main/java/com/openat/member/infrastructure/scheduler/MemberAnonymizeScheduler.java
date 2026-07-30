package com.openat.member.infrastructure.scheduler;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴(논리 삭제) 후 30일이 지난 회원의 email/nickname을 익명화한다.
 *
 * <p>탈퇴 자체는 {@code Member.withdraw()}(deletedAt만 채움)로 이미 처리돼 있고, 이 스케줄러는
 * 그 유예기간이 끝난 뒤 "재가입 시 중복 체크에 안 걸리게" email/nickname을 치환하는 역할만
 * 한다(물리 삭제 아님 — 행은 그대로 남고 deletedAt도 유지된다). 실제 익명화·트랜잭션 처리는
 * {@link MemberAnonymizeService}에 위임한다(self-invocation으로 인한 트랜잭션 미적용을
 * 피하기 위함 — 해당 클래스 문서 참고).
 *
 * <p>order의 {@code OutboxCleanupScheduler}와 같은 성격의 작업(초 단위 정확도가 필요 없는
 * 하루 단위 하우스키핑)이라 그 컨벤션을 따라 매일 새벽 cron으로 실행한다 — payment/outbox
 * 발행처럼 초 단위로 자주 도는 폴링(fixedDelay)과는 다른 종류의 스케줄이다.
 *
 * <p>조회는 매번 {@code BATCH_SIZE}로 상한을 둔 채, 이미 처리된 행은 {@code anonymizedAt}이
 * 채워져 다음 조회 조건에서 자연히 빠지는 걸 이용해 결과가 빈 배치가 나올 때까지 반복한다 —
 * 하루 실행 1회에 최대 BATCH_SIZE건만 처리하고 끝나면, 일일 탈퇴량이 그보다 많거나 적체가
 * 쌓였을 때 backlog가 영영 해소되지 않는 문제가 있었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberAnonymizeScheduler {

    private static final int BATCH_SIZE = 100;
    // 한 번 실행에서 반복할 최대 배치 수(=최대 100,000건/실행) — 어떤 항목이 매번 재조회되면서도
    // 계속 실패하는 병리적 상황에서조차 무한 루프에 빠지지 않도록 하는 안전장치.
    private static final int MAX_BATCH_ITERATIONS = 1_000;

    private final MemberRepository memberRepository;
    private final MemberAnonymizeService memberAnonymizeService;

    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void anonymizeWithdrawnMembers() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Member.WITHDRAWAL_GRACE_PERIOD);
        int totalAnonymized = 0;

        for (int iteration = 0; iteration < MAX_BATCH_ITERATIONS; iteration++) {
            List<Member> targets = memberRepository.findWithdrawnBefore(cutoff, BATCH_SIZE);
            if (targets.isEmpty()) {
                break;
            }
            for (Member member : targets) {
                try {
                    if (memberAnonymizeService.anonymize(member.getId(), cutoff)) {
                        totalAnonymized++;
                    }
                } catch (RuntimeException exception) {
                    log.error("[MemberAnonymizeScheduler] 익명화 실패, 다음 주기에 재시도: memberId={}",
                            member.getId(), exception);
                }
            }
            if (targets.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalAnonymized > 0) {
            log.info("[MemberAnonymizeScheduler] 탈퇴 회원 익명화 처리. count={}, cutoff={}",
                    totalAnonymized, cutoff);
        }
    }
}
