package com.openat.product.fixture;

import com.openat.product.domain.model.Product;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

public final class ProductFixture {

  private ProductFixture() {}

  public static Product uncategorized(UUID sellerId) {
    return Product.create().sellerId(sellerId).name("기본 굿즈").price(10_000L).build();
  }

  public static Product persisted(UUID id, UUID sellerId) {
    Product product = uncategorized(sellerId);
    ReflectionTestUtils.setField(product, "id", id); // 영속된 것처럼 id 주입
    return product;
  }
}
