package com.openat.search.product.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProductDbPageReaderTest {

  @Test
  void mapsTheRequestedProductFieldsFromSql() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ResultSet resultSet = mock(ResultSet.class);
    ProductDbPageReader reader = new ProductDbPageReader(jdbcTemplate, 50);
    UUID productId = UUID.fromString("c0a8db65-9f78-11f3-819f-78f38765000c");
    UUID categoryId = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    Instant createdAt = Instant.parse("2026-07-19T05:57:33.670Z");
    Instant updatedAt = Instant.parse("2026-07-19T05:57:33.670Z");

    when(resultSet.getObject("id", UUID.class)).thenReturn(productId);
    when(resultSet.getString("name")).thenReturn("루미에르 슬립온 로퍼 SH-0007");
    when(resultSet.getString("description")).thenReturn("상품 설명");
    when(resultSet.getObject("category_id", UUID.class)).thenReturn(categoryId);
    when(resultSet.getString("category_name")).thenReturn("의류");
    when(resultSet.getString("seller_name")).thenReturn("ABO LIMITED");
    when(resultSet.getLong("price")).thenReturn(121_000L);
    when(resultSet.getString("thumbnail_key"))
        .thenReturn("9cf58780-94b8-4b69-839a-754f54d7aef0.jpg");
    when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
    when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(updatedAt));
    when(resultSet.getTimestamp("deleted_at")).thenReturn(null);

    when(jdbcTemplate.query(
            eq(ProductDbPageReader.FIRST_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(50)))
        .thenAnswer(
            invocation -> {
              RowMapper<ProductDocument> rowMapper = invocation.getArgument(1);
              return List.of(rowMapper.mapRow(resultSet, 0));
            });

    ProductDocument product = reader.read();

    assertThat(product)
        .isEqualTo(
            new ProductDocument(
                productId.toString(),
                "루미에르 슬립온 로퍼 SH-0007",
                "상품 설명",
                categoryId.toString(),
                "의류",
                "ABO LIMITED",
                121_000L,
                "9cf58780-94b8-4b69-839a-754f54d7aef0.jpg",
                null,
                null,
                createdAt,
                updatedAt,
                null));
  }

  @Test
  void readsAllProductsInFiftyItemKeysetPages() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    ProductDbPageReader reader = new ProductDbPageReader(jdbcTemplate, 50);

    List<ProductDocument> firstPage = products(1, 50);
    List<ProductDocument> secondPage = products(51, 1);
    UUID firstCursor = UUID.fromString(firstPage.getLast().id());
    UUID secondCursor = UUID.fromString(secondPage.getLast().id());

    when(jdbcTemplate.query(
            eq(ProductDbPageReader.FIRST_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(50)))
        .thenReturn(firstPage);
    when(jdbcTemplate.query(
            eq(ProductDbPageReader.NEXT_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(firstCursor),
            eq(50)))
        .thenReturn(secondPage);
    when(jdbcTemplate.query(
            eq(ProductDbPageReader.NEXT_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(secondCursor),
            eq(50)))
        .thenReturn(List.of());

    List<ProductDocument> selected = new ArrayList<>();
    ProductDocument product;
    while ((product = reader.read()) != null) {
      selected.add(product);
    }

    assertThat(selected).containsExactlyElementsOf(concat(firstPage, secondPage));
    verify(jdbcTemplate)
        .query(
            eq(ProductDbPageReader.FIRST_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(50));
    verify(jdbcTemplate)
        .query(
            eq(ProductDbPageReader.NEXT_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(firstCursor),
            eq(50));
    verify(jdbcTemplate)
        .query(
            eq(ProductDbPageReader.NEXT_PAGE_SQL),
            ArgumentMatchers.<RowMapper<ProductDocument>>any(),
            eq(secondCursor),
            eq(50));
  }

  private List<ProductDocument> products(int start, int count) {
    List<ProductDocument> products = new ArrayList<>(count);
    for (int index = start; index < start + count; index++) {
      String id = "00000000-0000-0000-0000-%012d".formatted(index);
      products.add(
          new ProductDocument(
              id,
              "상품 " + index,
              "상품 설명 " + index,
              null,
              null,
              null,
              1_000L,
              null,
              null,
              null,
              Instant.parse("2026-07-19T05:57:33.670Z"),
              Instant.parse("2026-07-19T05:57:33.670Z"),
              null));
    }
    return products;
  }

  private List<ProductDocument> concat(List<ProductDocument> first, List<ProductDocument> second) {
    List<ProductDocument> result = new ArrayList<>(first.size() + second.size());
    result.addAll(first);
    result.addAll(second);
    return result;
  }
}
