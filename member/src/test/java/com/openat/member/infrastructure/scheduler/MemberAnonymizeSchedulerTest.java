package com.openat.member.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 스케줄러의 배치 순회/드레인/예외 격리만 검증한다. 실제 익명화 로직·트랜잭션은
 * {@link MemberAnonymizeServiceTest}(단위)와 {@link MemberAnonymizeIntegrationTest}(실제 Spring
 * 프록시+DB 커밋 검증)에서 다룬다 — 여기서 MemberAnonymizeService를 mock으로 대체하는 것도,
 * self-invocation 버그(스케줄러가 자기 자신의 @Transactional 메서드를 직접 부르던 것)를
 * 애초에 구조적으로 재현할 수 없게 하기 위함이다.
 */
class MemberAnonymizeSchedulerTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberAnonymizeService memberAnonymizeService = mock(MemberAnonymizeService.class);
    private final MemberAnonymizeScheduler scheduler =
            new MemberAnonymizeScheduler(memberRepository, memberAnonymizeService);

    @Test
    @DisplayName("한 배치(BATCH_SIZE 미만)만 있으면 한 번만 조회하고 끝낸다")
    void anonymizeWithdrawnMembers_whenSingleShortBatch_queriesOnce() {
        List<Member> partial = membersOf(3);
        when(memberRepository.findWithdrawnBefore(any(), anyInt())).thenReturn(partial);
        when(memberAnonymizeService.anonymize(any(UUID.class), any(LocalDateTime.class))).thenReturn(true);

        scheduler.anonymizeWithdrawnMembers();

        verify(memberRepository, times(1)).findWithdrawnBefore(any(), eq(100));
        verify(memberAnonymizeService, times(3)).anonymize(any(UUID.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("배치가 가득 차서 돌아오면 빈 결과가 나올 때까지 반복 조회해 백로그를 다 비운다")
    void anonymizeWithdrawnMembers_whenBacklogExceedsBatchSize_drainsAllBatches() {
        List<Member> firstBatch = membersOf(100);
        List<Member> secondBatch = membersOf(40);
        when(memberRepository.findWithdrawnBefore(any(), anyInt()))
                .thenReturn(firstBatch)
                .thenReturn(secondBatch)
                .thenReturn(List.of());
        when(memberAnonymizeService.anonymize(any(UUID.class), any(LocalDateTime.class))).thenReturn(true);

        scheduler.anonymizeWithdrawnMembers();

        // 100건(꽉 참) → 다음 배치 조회, 40건(미만) → 그걸로 종료. 세 번째(빈 결과) 호출까지는
        // 필요 없다 — size < BATCH_SIZE 에서 이미 멈춘다.
        verify(memberRepository, times(2)).findWithdrawnBefore(any(), eq(100));
        verify(memberAnonymizeService, times(140)).anonymize(any(UUID.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("배치 중 한 건이 예외를 던져도 나머지 건 처리를 계속한다")
    void anonymizeWithdrawnMembers_whenOneFails_continuesBatch() {
        Member poison = withdrawnMember("poison@b.com", "poison-nick");
        Member healthy = withdrawnMember("healthy@b.com", "healthy-nick");
        when(memberRepository.findWithdrawnBefore(any(), anyInt())).thenReturn(List.of(poison, healthy));
        when(memberAnonymizeService.anonymize(eq(poison.getId()), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("boom"));
        when(memberAnonymizeService.anonymize(eq(healthy.getId()), any(LocalDateTime.class)))
                .thenReturn(true);

        scheduler.anonymizeWithdrawnMembers();

        verify(memberAnonymizeService).anonymize(eq(poison.getId()), any(LocalDateTime.class));
        verify(memberAnonymizeService).anonymize(eq(healthy.getId()), any(LocalDateTime.class));
    }

    private List<Member> membersOf(int count) {
        List<Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            members.add(withdrawnMember("member" + i + "@b.com", "nick" + i));
        }
        return members;
    }

    private Member withdrawnMember(String email, String nickname) {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email(email)
                .password("encoded")
                .nickname(nickname)
                .build();
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        member.withdraw();
        return member;
    }
}
