package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.Product;
import com.openat.product.domain.model.QProduct;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.domain.repository.ProductTombstone;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryAdaptor implements ProductRepository {

  private final ProductJpaRepository productJpaRepository;
  private final JPAQueryFactory queryFactory;

  @Override
  public Product save(Product product) {
    return productJpaRepository.save(product);
  }

  @Override
  public Optional<Product> findById(UUID id) {
    return productJpaRepository.findById(id);
  }

  @Override
  public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
    QProduct product = QProduct.product;

    BooleanBuilder where = new BooleanBuilder();
    if (condition.categoryId() != null) {
      where.and(product.category.id.eq(condition.categoryId()));
    }
    if (StringUtils.hasText(condition.keyword())) {
      where.and(product.name.contains(condition.keyword()));
    }
    if (condition.sellerId() != null) {
      where.and(product.sellerId.eq(condition.sellerId()));
    }

    List<Product> content =
        queryFactory
            .selectFrom(product)
            .leftJoin(product.category)
            .fetchJoin()
            .where(where)
            .orderBy(product.createdAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    JPAQuery<Long> countQuery = queryFactory.select(product.count()).from(product).where(where);

    return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
  }

  @Override
  public List<Product> searchChangedAliveSince(Instant changedAfter) {
    QProduct product = QProduct.product;
    return queryFactory
        .selectFrom(product)
        .leftJoin(product.category)
        .fetchJoin()
        .where(product.updatedAt.gt(changedAfter))
        .fetch();
  }

  @Override
  public List<ProductTombstone> searchTombstonesSince(Instant changedAfter) {
    return productJpaRepository.searchTombstonesSince(changedAfter).stream()
        .map(row -> new ProductTombstone(toUuid(row[0]), toInstant(row[1])))
        .toList();
  }

  @Override
  public void delete(Product product) {
    productJpaRepository.delete(product);
  }

  private static UUID toUuid(Object value) {
    if (value instanceof UUID uuid) {
      return uuid;
    }
    return UUID.fromString(value.toString());
  }

  private static Instant toInstant(Object value) {
    return switch (value) {
      case Instant instant -> instant;
      case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
      case Timestamp timestamp -> timestamp.toInstant();
      default -> throw new IllegalStateException("지원하지 않는 삭제 시각 타입: " + value.getClass().getName());
    };
  }
}
