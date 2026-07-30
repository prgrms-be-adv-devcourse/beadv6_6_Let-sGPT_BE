package com.openat.member.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MemberAnonymizeServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberAnonymizeService service = new MemberAnonymizeService(memberRepository);

    @Test
    @DisplayName("유예기간이 지난 대로면 email/nickname을 memberId 기반으로 익명화하고 true를 반환한다")
    void anonymize_whenEligible_replacesFieldsAndReturnsTrue() {
        LocalDateTime cutoff = LocalDateTime.now();
        Member member = withdrawnMember("a@b.com", "nick", cutoff.minusDays(1));
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        boolean result = service.anonymize(member.getId(), cutoff);

        assertThat(result).isTrue();
        assertThat(member.getEmail()).isEqualTo("deleted_" + member.getId() + "_a@b.com");
        assertThat(member.getNickname()).isEqualTo("deleted_" + member.getId());
        assertThat(member.getAnonymizedAt()).isNotNull();
        assertThat(member.isDeleted()).isTrue(); // deletedAt 자체는 유지 — 물리 삭제가 아니다.
    }

    @Test
    @DisplayName("그 사이 유예기간 중 복구된 회원은 건드리지 않는다")
    void anonymize_whenRestoredMeanwhile_doesNothingAndReturnsFalse() {
        LocalDateTime cutoff = LocalDateTime.now();
        Member member = withdrawnMember("a@b.com", "nick", cutoff.minusDays(1));
        member.restore();
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        boolean result = service.anonymize(member.getId(), cutoff);

        assertThat(result).isFalse();
        assertThat(member.getEmail()).isEqualTo("a@b.com");
        assertThat(member.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("배치 조회 이후 재탈퇴로 deletedAt이 cutoff보다 뒤로 갱신됐으면 건드리지 않는다"
            + " (isDeleted()만 보면 놓치는 레이스 — 조건 전체를 재검증해야 함)")
    void anonymize_whenRewithdrawnAfterCutoff_doesNothingAndReturnsFalse() {
        LocalDateTime cutoff = LocalDateTime.now();
        // 배치 조회 시점엔 유예기간이 지난 상태였지만, 이 트랜잭션이 실제로 도는 사이
        // 복구 후 재탈퇴가 일어나 deletedAt이 cutoff 이후 시각으로 갱신된 상황을 흉내낸다.
        Member member = withdrawnMember("a@b.com", "nick", cutoff.plus(1, ChronoUnit.MINUTES));
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        boolean result = service.anonymize(member.getId(), cutoff);

        assertThat(result).isFalse();
        assertThat(member.getEmail()).isEqualTo("a@b.com");
    }

    @Test
    @DisplayName("이미 익명화된 회원은 다시 건드리지 않는다(멱등)")
    void anonymize_whenAlreadyAnonymized_doesNothingAndReturnsFalse() {
        LocalDateTime cutoff = LocalDateTime.now();
        Member member = withdrawnMember("a@b.com", "nick", cutoff.minusDays(1));
        member.anonymize();
        String anonymizedEmail = member.getEmail();
        when(memberRepository.findByIdIncludingDeleted(member.getId())).thenReturn(Optional.of(member));

        boolean result = service.anonymize(member.getId(), cutoff);

        assertThat(result).isFalse();
        assertThat(member.getEmail()).isEqualTo(anonymizedEmail); // 두 번째 익명화로 값이 또 바뀌지 않음
    }

    @Test
    @DisplayName("조회 자체가 안 되면(이미 삭제 등) false를 반환한다")
    void anonymize_whenMemberNotFound_returnsFalse() {
        UUID memberId = UUID.randomUUID();
        when(memberRepository.findByIdIncludingDeleted(memberId)).thenReturn(Optional.empty());

        boolean result = service.anonymize(memberId, LocalDateTime.now());

        assertThat(result).isFalse();
    }

    private Member withdrawnMember(String email, String nickname, LocalDateTime deletedAt) {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email(email)
                .password("encoded")
                .nickname(nickname)
                .build();
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(member, "deletedAt", deletedAt);
        return member;
    }
}
