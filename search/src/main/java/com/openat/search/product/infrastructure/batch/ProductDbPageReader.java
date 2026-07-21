package com.openat.search.product.infrastructure.batch;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.jdbc.core.JdbcTemplate;

/** 상품 DB를 UUID 커서 기준으로 일정 개수씩 조회하는 Spring Batch ItemReader. */
public class ProductDbPageReader implements ItemReader<ProductDocument> {

  static final String FIRST_PAGE_SQL =
      """
      SELECT p.id,
             p.name,
             p.description,
             p.category_id,
             c.name AS category_name,
             ss.store_name AS seller_name,
             p.price,
             p.thumbnail_key,
             p.created_at,
             p.updated_at,
             p.deleted_at
        FROM product.products p
        LEFT JOIN product.categories c
          ON c.id = p.category_id
        LEFT JOIN product.seller_stores ss
          ON ss.seller_info_id = p.seller_id
       ORDER BY p.id ASC
       LIMIT ?
      """;

  static final String NEXT_PAGE_SQL =
      """
      SELECT p.id,
             p.name,
             p.description,
             p.category_id,
             c.name AS category_name,
             ss.store_name AS seller_name,
             p.price,
             p.thumbnail_key,
             p.created_at,
             p.updated_at,
             p.deleted_at
        FROM product.products p
        LEFT JOIN product.categories c
          ON c.id = p.category_id
        LEFT JOIN product.seller_stores ss
          ON ss.seller_info_id = p.seller_id
       WHERE p.id > ?
       ORDER BY p.id ASC
       LIMIT ?
      """;

  private final JdbcTemplate jdbcTemplate;
  private final int pageSize;

  private Iterator<ProductDocument> currentPage = Collections.emptyIterator();
  private UUID lastProductId;

  public ProductDbPageReader(JdbcTemplate jdbcTemplate, int pageSize) {
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be greater than zero");
    }
    this.jdbcTemplate = jdbcTemplate;
    this.pageSize = pageSize;
  }

  @Override
  public synchronized ProductDocument read() {
    while (!currentPage.hasNext()) {
      List<ProductDocument> products = selectNextPage();
      if (products.isEmpty()) {
        return null;
      }

      lastProductId = UUID.fromString(products.getLast().id());
      currentPage = products.iterator();
    }

    return currentPage.next();
  }

  private List<ProductDocument> selectNextPage() {
    if (lastProductId == null) {
      return jdbcTemplate.query(FIRST_PAGE_SQL, this::mapProduct, pageSize);
    }
    return jdbcTemplate.query(NEXT_PAGE_SQL, this::mapProduct, lastProductId, pageSize);
  }

  private ProductDocument mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
    return new ProductDocument(
        resultSet.getObject("id", UUID.class).toString(),
        resultSet.getString("name"),
        resultSet.getString("description"),
        nullableUuid(resultSet, "category_id"),
        resultSet.getString("category_name"),
        resultSet.getString("seller_name"),
        nullableLong(resultSet, "price"),
        resultSet.getString("thumbnail_key"),
        null,
        null,
        nullableInstant(resultSet, "created_at"),
        nullableInstant(resultSet, "updated_at"),
        nullableInstant(resultSet, "deleted_at"));
  }

  private String nullableUuid(ResultSet resultSet, String columnName) throws SQLException {
    UUID value = resultSet.getObject(columnName, UUID.class);
    return value == null ? null : value.toString();
  }

  private Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
    long value = resultSet.getLong(columnName);
    return resultSet.wasNull() ? null : value;
  }

  private Instant nullableInstant(ResultSet resultSet, String columnName) throws SQLException {
    Timestamp value = resultSet.getTimestamp(columnName);
    return value == null ? null : value.toInstant();
  }
}
