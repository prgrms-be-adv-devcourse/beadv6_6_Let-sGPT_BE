package com.openat.search.product.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SearchIndexRefreshStateRepository {

  private static final String FIND_LAST_INDEXED_AT =
      """
      SELECT last_indexed_at
      FROM search.search_index_refresh_state
      WHERE state_key = ?
      """;

  private static final String UPSERT_LAST_INDEXED_AT =
      """
      INSERT INTO search.search_index_refresh_state (state_key, last_indexed_at, updated_at)
      VALUES (?, ?, now())
      ON CONFLICT (state_key)
      DO UPDATE SET last_indexed_at = EXCLUDED.last_indexed_at, updated_at = now()
      """;

  private final JdbcTemplate jdbcTemplate;

  public Optional<Instant> findLastIndexedAt(String stateKey) {
    try {
      return Optional.ofNullable(
          jdbcTemplate.queryForObject(
              FIND_LAST_INDEXED_AT,
              (rs, rowNum) -> rs.getTimestamp("last_indexed_at").toInstant(),
              stateKey));
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public void saveLastIndexedAt(String stateKey, Instant lastIndexedAt) {
    jdbcTemplate.update(UPSERT_LAST_INDEXED_AT, stateKey, Timestamp.from(lastIndexedAt));
  }
}
