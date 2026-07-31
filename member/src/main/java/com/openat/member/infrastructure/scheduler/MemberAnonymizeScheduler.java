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
 *
 * <p>다만 이 "빈 배치가 나올 때까지 반복"은 배치 전체가 계속 같은 이유로 실패하는 상황(예:
 * 데이터 이상으로 특정 행들이 매번 예외를 던짐)에 취약하다 — 실패한 행은 {@code anonymizedAt}이
 * 안 채워지니 다음 조회에서도 똑같이 뽑히고, 아무 진전 없이 같은 100건을 계속 재시도하며 DB에
 * 부하를 주고 에러 로그만 쌓을 수 있다. 그래서 연속으로 진전(성공 건수)이 없는 배치가
 * {@code MAX_CONSECUTIVE_NO_PROGRESS}회 나오면 이번 실행을 즉시 중단한다 — 백오프 없이
 * 무제한 재시도하는 대신, 실패 원인을 조사할 시간을 벌고 다음 날 다시 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberAnonymizeScheduler {

    private static final int BATCH_SIZE = 100;
    // 한 번 실행에서 반복할 최대 배치 수. 병리적 상황(같은 대상이 계속 실패)은 이 값이 아니라
    // 연속 무진전 서킷브레이커(아래)가 훨씬 일찍(3회 만에) 끊으므로, 여기는 정상적으로 진전하는
    // 배치까지 조기 절단하지 않도록 넉넉하게 잡는다(최대 1,000,000건/실행 — 이 정도 규모의
    // 일일 탈퇴량은 사실상 상정하지 않지만, 그래도 무한 루프에 대한 최종 안전장치로 남겨둔다).
    private static final int MAX_BATCH_ITERATIONS = 10_000;
    // 이 횟수만큼 연속으로 "조회는 됐는데 한 건도 익명화하지 못한" 배치가 나오면 중단한다.
    private static final int MAX_CONSECUTIVE_NO_PROGRESS = 3;

    private final MemberRepository memberRepository;
    private final MemberAnonymizeService memberAnonymizeService;

    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void anonymizeWithdrawnMembers() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Member.WITHDRAWAL_GRACE_PERIOD);
        int totalAnonymized = 0;
        int consecutiveNoProgress = 0;

        for (int iteration = 0; iteration < MAX_BATCH_ITERATIONS; iteration++) {
            List<Member> targets = memberRepository.findWithdrawnBefore(cutoff, BATCH_SIZE);
            if (targets.isEmpty()) {
                break;
            }

            int anonymizedInBatch = 0;
            for (Member member : targets) {
                try {
                    if (memberAnonymizeService.anonymize(member.getId(), cutoff)) {
                        anonymizedInBatch++;
                    }
                } catch (RuntimeException exception) {
                    log.error("[MemberAnonymizeScheduler] 익명화 실패, 다음 주기에 재시도: memberId={}",
                            member.getId(), exception);
                }
            }
            totalAnonymized += anonymizedInBatch;

            if (anonymizedInBatch == 0) {
                consecutiveNoProgress++;
                if (consecutiveNoProgress >= MAX_CONSECUTIVE_NO_PROGRESS) {
                    log.error("[MemberAnonymizeScheduler] {}회 연속 진전 없음 — 이번 실행 중단(다음 주기에 재시도). "
                                    + "동일 대상이 계속 실패하고 있을 수 있음. cutoff={}",
                            consecutiveNoProgress, cutoff);
                    break;
                }
            } else {
                consecutiveNoProgress = 0;
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
