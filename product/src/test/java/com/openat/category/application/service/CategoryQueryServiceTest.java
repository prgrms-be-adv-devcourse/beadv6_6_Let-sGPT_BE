package com.openat.category.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.common.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("카테고리 조회 서비스")
class CategoryQueryServiceTest {

  @InjectMocks private CategoryQueryService categoryQueryService;
  @Mock private CategoryRepository categoryRepository;

  @Test
  @DisplayName("존재하는 카테고리를 id로 조회한다")
  void getById_existing_returnsCategory() {
    // given
    UUID id = UUID.randomUUID();
    Category category = Category.create().name("의류").build();
    given(categoryRepository.findById(id)).willReturn(Optional.of(category));

    // when
    Category result = categoryQueryService.getById(id);

    // then
    assertThat(result).isEqualTo(category);
  }

  @Test
  @DisplayName("없는 카테고리를 조회하면 NOT_FOUND 예외를 던진다")
  void getById_notFound_throwsException() {
    // given
    UUID missingId = UUID.randomUUID();
    given(categoryRepository.findById(missingId)).willReturn(Optional.empty());

    // when & then
    assertThatThrownBy(() -> categoryQueryService.getById(missingId))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.NOT_FOUND);
  }

  @Test
  @DisplayName("전체 카테고리를 조회한다")
  void getAll_returnsAllCategories() {
    // given
    given(categoryRepository.findAll())
        .willReturn(
            List.of(Category.create().name("문구").build(), Category.create().name("의류").build()));

    // when
    List<Category> result = categoryQueryService.getAll();

    // then
    assertThat(result).hasSize(2);
  }
}
