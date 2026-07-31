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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    // 테스트 전용 백데이트 — 프로덕션 코드에는 deletedAt을 임의로 되돌리는 경로가 없어야 하므로
    // (실제로는 시간이 지나야만 유예기간이 만료됨) 리포지토리를 거치지 않고 직접 값을 되돌린다.
    private void backdateDeletedAt(java.util.UUID memberId, LocalDateTime deletedAt) {
        Member member = memberRepository.findByIdIncludingDeleted(memberId).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(member, "deletedAt", deletedAt);
        memberRepository.save(member);
    }
}
