package com.openat.product.infrastructure.persistence;

import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PostgresSearchProjectionReferenceLock implements SearchProjectionReferenceLock {

  private static final long SELLER_NAMESPACE = 0x53_45_4C_4C_45_52L;
  private static final long CATEGORY_NAMESPACE = 0x43_41_54_45_47_4FL;
  private static final String SHARED_LOCK_SQL = "SELECT pg_advisory_xact_lock_shared(?)";
  private static final String EXCLUSIVE_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";

  private final JdbcTemplate jdbcTemplate;

  @Override
  public void lockSellerForSnapshotRead(UUID sellerId) {
    lock(sellerId, SELLER_NAMESPACE, SHARED_LOCK_SQL);
  }

  @Override
  public void lockCategoryForSnapshotRead(UUID categoryId) {
    lock(categoryId, CATEGORY_NAMESPACE, SHARED_LOCK_SQL);
  }

  @Override
  public void lockSellerForReferenceWrite(UUID sellerId) {
    lock(sellerId, SELLER_NAMESPACE, EXCLUSIVE_LOCK_SQL);
  }

  @Override
  public void lockCategoryForReferenceWrite(UUID categoryId) {
    lock(categoryId, CATEGORY_NAMESPACE, EXCLUSIVE_LOCK_SQL);
  }

  private void lock(UUID referenceId, long namespace, String sql) {
    long lockKey =
        namespace
            ^ referenceId.getMostSignificantBits()
            ^ Long.rotateLeft(referenceId.getLeastSignificantBits(), 17);
    jdbcTemplate.execute(
        sql,
        (PreparedStatementCallback<Void>)
            statement -> {
              statement.setLong(1, lockKey);
              statement.execute();
              return null;
            });
  }
}
