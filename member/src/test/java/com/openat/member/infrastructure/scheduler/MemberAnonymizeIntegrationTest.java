package com.openat.member.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.repository.MemberRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 Spring 컨텍스트(진짜 {@code @Transactional} 프록시)와 실제 Postgres로 익명화·복구가
 * 실제로 커밋되는지, 그리고 둘이 같은 회원 행을 동시에 건드릴 때 서로를 덮어쓰지 않는지
 * 검증한다.
 *
 * <p>이 테스트가 존재하는 이유: 예전 구현은 {@code MemberAnonymizeScheduler}가 자기 자신의
 * {@code @Transactional} 메서드를 직접 호출(self-invocation)해 트랜잭션이 전혀 시작되지
 * 않았다. Mockito로 리포지토리를 흉내낸 단위 테스트는 프록시 경계 자체가 없어 이 문제를
 * 절대 잡아낼 수 없었다(리뷰 지적) — 그래서 실제 빈 그래프·실제 DB 커밋을 보는 테스트가
 * 별도로 필요하다.
 *
 * <p>lost-update 방지는 {@code @Version} 낙관적 락 대신 조건부 원자적 UPDATE
 * (MemberRepository.restoreIfWithinGracePeriod/anonymizeIfEligible)로 한다 — 새 NOT NULL
 * 컬럼을 추가하면, 이 레포처럼 별도 마이그레이션 도구 없이 Hibernate ddl-auto(update)만 쓰는
 * 환경에서는 이미 데이터가 있는 운영 테이블에 적용할 안전한 경로가 없기 때문이다(리뷰 지적 —
 * 실제로 최초 시도 때 시드 스크립트가 이 문제로 깨지는 걸 직접 확인했다). 조건부 UPDATE는
 * 기존 컬럼(deletedAt/anonymizedAt)만 쓰므로 스키마 변경이 전혀 필요 없다.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class MemberAnonymizeIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16")
                    .withDatabaseName("openat")
                    .withUsername("test")
                    .withPassword("test");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final KeyPair TEST_KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            TEST_KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("jwt.key-id", () -> "test-key");
        registry.add("jwt.private-key",
                () -> Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("jwt.public-key",
                () -> Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPublic().getEncoded()));
        registry.add("jwt.issuer", () -> "http://test-issuer");
    }

    @Autowired
    MemberAnonymizeScheduler scheduler;

    @Autowired
    MemberAnonymizeService memberAnonymizeService;

    @Autowired
    MemberRepository memberRepository;

    @Test
    @DisplayName("스케줄러 실행 후 유예기간이 지난 회원의 email/nickname 변경이 실제 DB에 커밋된다")
    void anonymizeWithdrawnMembers_actuallyCommitsToDatabase() {
        Member saved = saveWithdrawnMember("expired@b.com", "expired-nick");
        // 저장 직후엔 deletedAt이 "지금"이라 유예기간 안이므로, 스케줄러가 실제로 걸러내는지까지
        // 보기 위해 30일보다 더 지난 시각으로 직접 되돌린다(리포지토리 계층에서 update).
        backdateDeletedAt(saved.getId(), LocalDateTime.now().minusDays(31));

        scheduler.anonymizeWithdrawnMembers();

        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo(Member.ANONYMIZED_PREFIX + saved.getId() + "_expired@b.com");
        assertThat(reloaded.getNickname()).isEqualTo(Member.ANONYMIZED_PREFIX + saved.getId());
        assertThat(reloaded.getAnonymizedAt()).isNotNull();
    }

    @Test
    @DisplayName("유예기간 안인 회원은 스케줄러가 건드리지 않는다")
    void anonymizeWithdrawnMembers_leavesMembersWithinGracePeriodUntouched() {
        Member saved = saveWithdrawnMember("fresh@b.com", "fresh-nick"); // deletedAt = 지금, 유예기간 안

        scheduler.anonymizeWithdrawnMembers();

        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("fresh@b.com");
        assertThat(reloaded.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("익명화가 먼저 커밋되면, 뒤이은 복구 시도는 0건 갱신으로 끝나 email이 되돌아가지 않는다")
    void whenAnonymizeCommitsFirst_laterRestoreAttemptIsNoOp() {
        Member saved = saveWithdrawnMember("anon-wins@b.com", "anon-wins-nick");
        LocalDateTime cutoff = LocalDateTime.now();
        backdateDeletedAt(saved.getId(), cutoff.minusDays(1));

        boolean anonymized = memberAnonymizeService.anonymize(saved.getId(), cutoff);
        assertThat(anonymized).isTrue();

        // 뒤늦게 복구를 시도한다(조건부 UPDATE가 이 순간의 실제 DB 상태를 재검증) — 이미
        // anonymizedAt이 채워져 있으므로 0건으로 끝나야 한다.
        int restored = memberRepository.restoreIfWithinGracePeriod(saved.getId(), cutoff.minusDays(2));

        assertThat(restored).isZero();
        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).startsWith(Member.ANONYMIZED_PREFIX);
        assertThat(reloaded.isDeleted()).isTrue(); // deletedAt 자체는 유지(물리 삭제 아님)
    }

    @Test
    @DisplayName("복구가 먼저 커밋되면, 뒤이은 익명화 시도는 0건 갱신으로 끝나 원본 email이 유지된다")
    void whenRestoreCommitsFirst_laterAnonymizeAttemptIsNoOp() {
        Member saved = saveWithdrawnMember("restore-wins@b.com", "restore-wins-nick");
        LocalDateTime cutoff = LocalDateTime.now();
        backdateDeletedAt(saved.getId(), cutoff.minusDays(1));

        int restored = memberRepository.restoreIfWithinGracePeriod(saved.getId(), cutoff.minusDays(2));
        assertThat(restored).isEqualTo(1);

        // 뒤늦게 익명화를 시도한다 — deletedAt이 이미 null이라 "탈퇴 상태"라는 전제 자체가
        // 깨져 있으므로 0건으로 끝나야 한다.
        boolean anonymized = memberAnonymizeService.anonymize(saved.getId(), cutoff);

        assertThat(anonymized).isFalse();
        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("restore-wins@b.com");
        assertThat(reloaded.isDeleted()).isFalse();
        assertThat(reloaded.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("같은 회원을 두 번 복구 시도하면(예: 중복 클릭) 두 번째는 0건으로 안전하게 끝난다")
    void restoreIfWithinGracePeriod_calledTwice_secondAttemptIsNoOp() {
        Member saved = saveWithdrawnMember("double-click@b.com", "double-click-nick");
        LocalDateTime cutoff = LocalDateTime.now().minusDays(1);

        int first = memberRepository.restoreIfWithinGracePeriod(saved.getId(), cutoff);
        int second = memberRepository.restoreIfWithinGracePeriod(saved.getId(), cutoff);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    private Member saveWithdrawnMember(String email, String nickname) {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email(email)
                .password("encoded")
                .nickname(nickname)
                .build();
        member.withdraw();
        return memberRepository.save(member);
    }

    // 테스트 전용 백데이트 — 프로덕션 코드에는 deletedAt을 임의로 되돌리는 경로가 없어야 하므로
    // (실제로는 시간이 지나야만 유예기간이 만료됨) 리포지토리를 거치지 않고 직접 값을 되돌린다.
    private void backdateDeletedAt(UUID memberId, LocalDateTime deletedAt) {
        Member member = memberRepository.findByIdIncludingDeleted(memberId).orElseThrow();
        ReflectionTestUtils.setField(member, "deletedAt", deletedAt);
        memberRepository.save(member);
    }
}
