package com.openat.member.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.repository.MemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberAnonymizeSchedulerTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberAnonymizeScheduler scheduler = new MemberAnonymizeScheduler(memberRepository);

    @Test
    @DisplayName("탈퇴 유예기간이 지난 회원의 email/nickname을 memberId 기반으로 익명화한다")
    void anonymize_whenStillWithdrawn_replacesEmailAndNickname() {
        Member member = withdrawnMember("a@b.com", "nick");
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        scheduler.anonymize(member.getId());

        assertThat(member.getEmail()).isEqualTo("deleted_" + member.getId() + "_a@b.com");
        assertThat(member.getNickname()).isEqualTo("deleted_" + member.getId());
        assertThat(member.getAnonymizedAt()).isNotNull();
        assertThat(member.isDeleted()).isTrue(); // deletedAt 자체는 유지 — 물리 삭제가 아니다.
    }

    @Test
    @DisplayName("그 사이 유예기간 중 복구된 회원은 건드리지 않는다")
    void anonymize_whenRestoredMeanwhile_doesNothing() {
        Member member = withdrawnMember("a@b.com", "nick");
        member.restore();
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        scheduler.anonymize(member.getId());

        assertThat(member.getEmail()).isEqualTo("a@b.com");
        assertThat(member.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("배치 중 한 건이 예외여도 나머지 처리를 계속한다")
    void anonymizeWithdrawnMembers_whenOneFails_continuesBatch() {
        Member poison = withdrawnMember("poison@b.com", "poison-nick");
        Member healthy = withdrawnMember("healthy@b.com", "healthy-nick");
        when(memberRepository.findWithdrawnBefore(any(), anyInt())).thenReturn(List.of(poison, healthy));
        // poison 항목은 재조회 자체가 실패하는 상황(예: 동시성 이슈)을 흉내낸다.
        when(memberRepository.findByIdIncludingDeleted(poison.getId()))
                .thenThrow(new IllegalStateException("boom"));
        when(memberRepository.findByIdIncludingDeleted(healthy.getId())).thenReturn(Optional.of(healthy));

        scheduler.anonymizeWithdrawnMembers();

        assertThat(healthy.getAnonymizedAt()).isNotNull();
        assertThat(poison.getAnonymizedAt()).isNull();
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
