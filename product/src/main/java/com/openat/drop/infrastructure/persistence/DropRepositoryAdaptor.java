package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.model.QDrop;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.DropSearchCondition;
import com.openat.product.domain.model.QProduct;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Instant;
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
public class DropRepositoryAdaptor implements DropRepository {

  private final DropJpaRepository dropJpaRepository;
  private final JPAQueryFactory queryFactory;

  @Override
  public Drop save(Drop drop) {
    return dropJpaRepository.save(drop);
  }

  @Override
  public void delete(Drop drop) {
    dropJpaRepository.delete(drop);
  }

  @Override
  public Optional<Drop> findById(UUID id) {
    return dropJpaRepository.findById(id);
  }

  @Override
  public Optional<Drop> findWithProductById(UUID id) {
    return dropJpaRepository.findWithProductById(id);
  }

  @Override
  public List<Drop> findAllByStatus(DropStatus status) {
    return dropJpaRepository.findAllByStatus(status);
  }

  @Override
  public List<Drop> findAllByProductId(UUID productId) {
    return dropJpaRepository.findAllByProduct_Id(productId);
  }

  @Override
  public Page<Drop> search(DropSearchCondition condition, Instant now, Pageable pageable) {
    QDrop drop = QDrop.drop;
    QProduct product = QProduct.product;

    BooleanBuilder where = new BooleanBuilder();
    where.and(statusPredicate(drop, condition.status(), now));
    if (condition.categoryId() != null) {
      where.and(product.category.id.eq(condition.categoryId()));
    }
    if (StringUtils.hasText(condition.keyword())) {
      where.and(product.name.contains(condition.keyword()));
    }
    if (condition.sellerId() != null) {
      where.and(product.sellerId.eq(condition.sellerId()));
    }

    List<Drop> content =
        queryFactory
            .selectFrom(drop)
            .join(drop.product, product)
            .fetchJoin()
            .leftJoin(product.category)
            .fetchJoin()
            .where(where)
            .orderBy(drop.openAt.desc())
            .offset(pageable.getOffset())
            .limit(pageable.getPageSize())
            .fetch();

    JPAQuery<Long> countQuery =
        queryFactory.select(drop.count()).from(drop).join(drop.product, product).where(where);

    return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
  }

  private Predicate statusPredicate(QDrop drop, DropStatus status, Instant now) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case REGISTERED -> drop.status.eq(DropStatus.REGISTERED).and(drop.openAt.gt(now));
      case OPEN, SOLD_OUT ->
          drop.status
              .eq(DropStatus.REGISTERED)
              .and(drop.openAt.loe(now))
              .and(drop.closeAt.isNull().or(drop.closeAt.gt(now)));
      case CLOSE ->
          drop.status
              .eq(DropStatus.CLOSE)
              .or(
                  drop.status
                      .eq(DropStatus.REGISTERED)
                      .and(drop.closeAt.isNotNull())
                      .and(drop.closeAt.loe(now)));
    };
  }
}
