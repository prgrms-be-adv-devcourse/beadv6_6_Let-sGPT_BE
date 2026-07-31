package com.openat.member.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.member.domain.model.Member;
import com.openat.member.domain.model.PlatformType;
import com.openat.member.domain.repository.MemberRepository;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 Spring 컨텍스트(진짜 {@code @Transactional} 프록시)와 실제 Postgres로 익명화가
 * 실제로 커밋되는지 검증한다.
 *
 * <p>이 테스트가 존재하는 이유: 예전 구현은 {@code MemberAnonymizeScheduler}가 자기 자신의
 * {@code @Transactional} 메서드를 직접 호출(self-invocation)해 트랜잭션이 전혀 시작되지
 * 않았다. Mockito로 리포지토리를 흉내낸 단위 테스트는 프록시 경계 자체가 없어 이 문제를
 * 절대 잡아낼 수 없었다(리뷰 지적) — 그래서 실제 빈 그래프·실제 DB 커밋을 보는 테스트가
 * 별도로 필요하다.
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
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email("expired@b.com")
                .password("encoded")
                .nickname("expired-nick")
                .build();
        member.withdraw();
        Member saved = memberRepository.save(member);
        // 저장 직후엔 deletedAt이 "지금"이라 유예기간 안이므로, 스케줄러가 실제로 걸러내는지까지
        // 보기 위해 30일보다 더 지난 시각으로 직접 되돌린다(리포지토리 계층에서 update).
        backdateDeletedAt(saved.getId(), LocalDateTime.now().minusDays(31));

        scheduler.anonymizeWithdrawnMembers();

        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("deleted_" + saved.getId() + "_expired@b.com");
        assertThat(reloaded.getNickname()).isEqualTo("deleted_" + saved.getId());
        assertThat(reloaded.getAnonymizedAt()).isNotNull();
    }

    @Test
    @DisplayName("유예기간 안인 회원은 스케줄러가 건드리지 않는다")
    void anonymizeWithdrawnMembers_leavesMembersWithinGracePeriodUntouched() {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email("fresh@b.com")
                .password("encoded")
                .nickname("fresh-nick")
                .build();
        member.withdraw(); // deletedAt = 지금, 유예기간 안
        Member saved = memberRepository.save(member);

        scheduler.anonymizeWithdrawnMembers();

        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("fresh@b.com");
        assertThat(reloaded.getAnonymizedAt()).isNull();
    }

    @Test
    @DisplayName("복구와 익명화가 동시에 같은 회원을 변경하려 하면, 나중에 커밋되는 쪽이 낙관적 락"
            + " 충돌로 실패해 식별정보가 뒤섞이는 lost-update가 나지 않는다")
    void concurrentRestoreAndAnonymize_secondCommitFailsInsteadOfCorruptingState() {
        Member member = Member.builder()
                .platformType(PlatformType.LOCAL)
                .email("race@b.com")
                .password("encoded")
                .nickname("race-nick")
                .build();
        member.withdraw();
        Member saved = memberRepository.save(member);
        LocalDateTime cutoff = LocalDateTime.now();
        backdateDeletedAt(saved.getId(), cutoff.minusDays(1));

        // "동시에 읽은" 두 트랜잭션을 흉내: 복구 쪽이 먼저 행을 읽어 자기 세션에 들고 있는다
        // (이 시점의 version은 아직 익명화 전 값 — 진짜 동시 요청이면 이 상태로 대기하다 나중에
        // 커밋을 시도하는 상황과 같다).
        Member staleSnapshotForRestore = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();

        // 익명화가 그 사이 먼저 커밋된다(별도 트랜잭션 — MemberAnonymizeService가 직접 조회부터
        // 커밋까지 자체적으로 처리하므로 위 stale 스냅샷과는 완전히 독립된 세션이다).
        boolean anonymized = memberAnonymizeService.anonymize(saved.getId(), cutoff);
        assertThat(anonymized).isTrue();

        // 미리 읽어둔(버전 stale) 스냅샷으로 뒤늦게 복구를 커밋하려 하면 낙관적 락 충돌로
        // 실패해야 한다 — 실패하지 않고 성공해버리면 그 순간 익명화된 email/nickname이
        // 복구 스냅샷의 옛 값(원본 email 등)으로 덮어써진다(=lost-update, 이번 수정 전 버그).
        assertThatThrownBy(() -> {
            staleSnapshotForRestore.restore();
            memberRepository.save(staleSnapshotForRestore);
        }).isInstanceOf(OptimisticLockingFailureException.class);

        // 최종 DB 상태는 익명화된 상태 그대로 — 실패한 복구 커밋이 이를 덮어쓰지 않았다.
        Member reloaded = memberRepository.findByIdIncludingDeleted(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).startsWith("deleted_");
        assertThat(reloaded.isDeleted()).isTrue();
    }

    // 테스트 전용 백데이트 — 프로덕션 코드에는 deletedAt을 임의로 되돌리는 경로가 없어야 하므로
    // (실제로는 시간이 지나야만 유예기간이 만료됨) 리포지토리를 거치지 않고 직접 값을 되돌린다.
    private void backdateDeletedAt(java.util.UUID memberId, LocalDateTime deletedAt) {
        Member member = memberRepository.findByIdIncludingDeleted(memberId).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(member, "deletedAt", deletedAt);
        memberRepository.save(member);
    }
}
