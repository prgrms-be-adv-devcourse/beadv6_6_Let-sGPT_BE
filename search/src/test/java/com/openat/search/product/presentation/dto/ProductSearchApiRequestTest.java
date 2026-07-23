package com.openat.search.product.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProductSearchApiRequestTest {

  @Test
  void exposesOnlySearchAndPagingFields() {
    assertThat(
            Arrays.stream(ProductSearchApiRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
        .containsExactly("query", "categoryName", "startPrice", "endPrice", "page", "size");
  }
}
