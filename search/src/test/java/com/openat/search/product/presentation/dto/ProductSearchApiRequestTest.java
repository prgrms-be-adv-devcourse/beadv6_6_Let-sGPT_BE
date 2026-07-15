package com.openat.search.product.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductSearchApiRequestTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"createdAt,desc", "price,asc", "price,desc"})
  void acceptsSupportedSortValues(String sort) {
    assertThat(validator.validate(request(sort))).isEmpty();
  }

  @Test
  void rejectsUnsupportedSortValue() {
    assertThat(validator.validate(request("updatedAt,desc")))
        .singleElement()
        .satisfies(violation -> assertThat(violation.getPropertyPath()).hasToString("sort"));
  }

  private ProductSearchApiRequest request(String sort) {
    return new ProductSearchApiRequest(null, null, null, null, 0, 20, sort);
  }
}
