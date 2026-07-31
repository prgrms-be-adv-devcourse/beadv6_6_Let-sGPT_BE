package com.openat.member.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 조건 검증(deletedAt &lt;= cutoff, anonymizedAt IS NULL)과 email/nickname 치환은
 * {@code MemberJpaRepository.anonymizeIfEligible}의 원자적 native UPDATE가 SQL로 직접
 * 수행하므로(엔티티를 조회해 메모리에서 바꾸는 방식이 아님), 이 단위 테스트는 "리포지토리
 * 호출 위임 + 갱신 건수를 boolean으로 변환"만 검증한다. SQL 자체의 정확성(치환 값, 조건
 * 재검증, 동시성 안전성)은 {@link MemberAnonymizeIntegrationTest}가 실제 Postgres로 검증한다.
 */
class MemberAnonymizeServiceTest {

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberAnonymizeService service = new MemberAnonymizeService(memberRepository);

    @Test
    @DisplayName("리포지토리가 1건 갱신했다고 응답하면 true를 반환한다")
    void anonymize_whenRepositoryUpdatesOneRow_returnsTrue() {
        UUID memberId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();
        when(memberRepository.anonymizeIfEligible(eq(memberId), eq(cutoff), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean result = service.anonymize(memberId, cutoff);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("리포지토리가 0건 갱신했다고 응답하면(조건 불일치) false를 반환한다")
    void anonymize_whenRepositoryUpdatesNoRow_returnsFalse() {
        UUID memberId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now();
        when(memberRepository.anonymizeIfEligible(eq(memberId), eq(cutoff), any(LocalDateTime.class)))
                .thenReturn(0);

        boolean result = service.anonymize(memberId, cutoff);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("전달받은 cutoff를 그대로 조건부 UPDATE에 넘긴다")
    void anonymize_passesGivenCutoffToRepository() {
        UUID memberId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        when(memberRepository.anonymizeIfEligible(any(), any(), any())).thenReturn(1);

        service.anonymize(memberId, cutoff);

        verify(memberRepository).anonymizeIfEligible(eq(memberId), eq(cutoff), any(LocalDateTime.class));
    }
}
