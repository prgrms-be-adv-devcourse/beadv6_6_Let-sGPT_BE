package com.openat.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.ScanCursor;
import com.openat.payment.infrastructure.persistence.converter.EncryptedStringConverter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// TTL 스캐너 커서 페이지네이션 회귀(42P18) 검증 — PR #314가 도입한 단일 쿼리
// `(:cursor IS NULL OR createdAt > :cursor ...)`는 null 커서 바인드에서 Postgres가 파라미터 타입을
// 추론하지 못해(42P18 "could not determine data type of parameter") 매 사이클 즉시 실패했다. 이 회귀는
// H2로는 재현되지 않고 실제 Postgres에서만 드러나므로 Testcontainers Postgres로 검증한다(order 모듈과 동일 관례).
// 첫 페이지(커서 null)/후속 페이지(커서 있음)를 별도 쿼리로 분리한 수정이 세 경로 모두에서 실제로 실행되는지 확인.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PaymentRepositoryAdaptor.class, EncryptedStringConverter.class})
@TestPropertySource(
    properties = {
      "spring.sql.init.mode=never",
      // application-local.yml의 ${DB_USER}/${DB_PASSWORD} 플레이스홀더 해소용(실제 접속은 @ServiceConnection이 대체).
      "spring.datasource.username=test",
      "spring.datasource.password=test",
      // EncryptedStringConverter가 부트스트랩되도록 유효한 Base64 32바이트 키 주입(pgPaymentKey는 null이라 실사용은 없음).
      "payment.encryption.key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    })
@DisplayName("결제 TTL 스캐너 커서 쿼리 - 실제 Postgres 회귀(42P18)")
class PaymentStalePendingCursorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private PaymentRepository paymentRepository;
  @PersistenceContext private EntityManager entityManager;

  private static final LocalDateTime NOW = LocalDateTime.now();
  // 마지노선 이전(stale) 4건 — createdAt 오름차순, 마지막 둘은 createdAt 동률로 id 타이브레이크를 강제.
  private static final UUID ID1 = idOf(1);
  private static final UUID ID2 = idOf(2);
  private static final UUID ID3 = idOf(3);
  private static final UUID ID4 = idOf(4);
  private static final LocalDateTime T1 = NOW.minusMinutes(30);
  private static final LocalDateTime T2 = NOW.minusMinutes(20);
  private static final LocalDateTime T_SAME = NOW.minusMinutes(10); // ID3, ID4 동률
  private static final LocalDateTime THRESHOLD = NOW.minusMinutes(5);

  private void seedStaleRows() {
    insertPayment(ID1, Payment.Status.PAYMENT_PENDING, T1);
    insertPayment(ID2, Payment.Status.PAYMENT_PENDING, T2);
    insertPayment(ID3, Payment.Status.PAYMENT_PENDING, T_SAME);
    insertPayment(ID4, Payment.Status.PAYMENT_PENDING, T_SAME);
    // 노이즈 — 마지노선 이후(아직 stale 아님)와 PENDING 아닌 상태는 결과에서 제외돼야 한다.
    insertPayment(idOf(5), Payment.Status.PAYMENT_PENDING, NOW.minusMinutes(1));
    insertPayment(idOf(6), Payment.Status.APPROVED, T1);
    entityManager.flush();
  }

  // 첫 페이지 — 커서 null. #314 회귀 지점: null 커서 바인드가 42P18로 즉시 실패하던 경로.
  @Test
  @DisplayName("커서 null(첫 페이지)이 42P18 없이 오래된 순으로 stale PENDING을 반환한다")
  void firstPage_nullCursor_returnsStaleOrderedWithoutTypeInferenceError() {
    seedStaleRows();

    List<Payment> page = paymentRepository.findStalePending(THRESHOLD, null, 10);

    assertThat(page).extracting(Payment::getId).containsExactly(ID1, ID2, ID3, ID4);
  }

  // 첫 페이지 실행 자체가 예외 없이 끝나는지 별도 단언(회귀의 핵심 증빙).
  @Test
  @DisplayName("커서 null 조회가 InvalidDataAccessResourceUsageException(42P18)을 던지지 않는다")
  void firstPage_nullCursor_doesNotThrow() {
    seedStaleRows();
    assertThatCode(() -> paymentRepository.findStalePending(THRESHOLD, null, 10))
        .doesNotThrowAnyException();
  }

  // 후속 페이지 — 커서 존재. 커서((T2, ID2)) 초과분만 반환.
  @Test
  @DisplayName("커서 존재(후속 페이지)는 커서 이후 행만 오래된 순으로 반환한다")
  void subsequentPage_withCursor_returnsRowsStrictlyAfterCursor() {
    seedStaleRows();

    List<Payment> page =
        paymentRepository.findStalePending(THRESHOLD, new ScanCursor(T2, ID2), 10);

    assertThat(page).extracting(Payment::getId).containsExactly(ID3, ID4);
  }

  // createdAt 동률 + id 타이브레이크 — 커서((T_SAME, ID3))가 같은 시각 앞 행이어도, 같은 시각의 다음 행(ID4)에
  // 도달하고 커서 행(ID3)은 재반환하지 않는다. 단일 createdAt 커서였다면 같은 시각 후속 행을 영원히 건너뛴다.
  @Test
  @DisplayName("createdAt 동률이면 id 타이브레이크로 같은 시각의 후속 행에 도달한다")
  void tie_onCreatedAt_advancesByIdTiebreak() {
    seedStaleRows();

    List<Payment> page =
        paymentRepository.findStalePending(THRESHOLD, new ScanCursor(T_SAME, ID3), 10);

    assertThat(page).extracting(Payment::getId).containsExactly(ID4);
  }

  // 고정 id — 같은 createdAt 안에서 순서를 결정론적으로 통제(상위 비트 0이라 Java·Postgres 정렬이 일치).
  private static UUID idOf(int n) {
    return new UUID(0L, n);
  }

  private void insertPayment(UUID id, Payment.Status status, LocalDateTime createdAt) {
    // 네이티브 INSERT — @CreatedDate 감사(@EnableJpaAuditing은 슬라이스 밖)·암호화 컨버터를 우회해 created_at을
    // 정확히 통제한다. pg_payment_key는 NULL(컨버터 실사용 없음). 읽기는 리포지토리 → 엔티티 매핑을 그대로 거친다.
    entityManager
        .createNativeQuery(
            "INSERT INTO payment.payments "
                + "(id, order_id, member_id, amount, method, status, refunded_amount, pg_recon_status, "
                + "idempotency_key, created_at) "
                + "VALUES (:id, :orderId, :memberId, 1000, 'PG', :status, 0, 'NOT_CHECKED', :idem, :createdAt)")
        .setParameter("id", id)
        .setParameter("orderId", UUID.randomUUID())
        .setParameter("memberId", UUID.randomUUID())
        .setParameter("status", status.name())
        .setParameter("idem", "idem-" + UUID.randomUUID())
        .setParameter("createdAt", createdAt)
        .executeUpdate();
  }
}
