package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.Product;
import com.openat.product.domain.model.QProduct;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
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
  public void delete(Product product) {
    productJpaRepository.delete(product);
  }
}
