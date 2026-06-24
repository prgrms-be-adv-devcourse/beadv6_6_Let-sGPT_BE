package com.openat.category.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.openat.category.application.dto.CategoryCreateCommand;
import com.openat.category.application.dto.CategoryUpdateCommand;
import com.openat.category.domain.error.CategoryErrorCode;
import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.common.exception.BusinessException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("카테고리 명령 서비스")
class CategoryCommandServiceTest {

  @InjectMocks private CategoryCommandService categoryCommandService;
  @Mock private CategoryRepository categoryRepository;

  @Nested
  @DisplayName("카테고리 생성")
  class Create {

    @Test
    @DisplayName("중복된 이름이면 DUPLICATE_NAME 예외를 던진다")
    void create_nameDuplicated_throwsException() {
      // given
      String duplicatedName = "의류";
      CategoryCreateCommand command = new CategoryCreateCommand(duplicatedName);
      given(categoryRepository.existsByName(duplicatedName)).willReturn(true);

      // when & then
      assertThatThrownBy(() -> categoryCommandService.create(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.DUPLICATE_NAME);
      then(categoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("중복되지 않은 이름이면 저장 후 생성된 id를 반환한다")
    void create_validName_returnsSavedId() {
      // given
      String name = "의류";
      CategoryCreateCommand command = new CategoryCreateCommand(name);
      given(categoryRepository.existsByName(name)).willReturn(false);

      UUID savedId = UUID.randomUUID();
      Category savedCategory = categoryWithId(savedId, name);
      given(categoryRepository.save(any(Category.class))).willReturn(savedCategory);

      // when
      UUID result = categoryCommandService.create(command);

      // then
      assertThat(result).isEqualTo(savedId);
    }
  }

  @Nested
  @DisplayName("카테고리 수정")
  class Update {

    @Test
    @DisplayName("이름이 그대로면 변경 없이 종료한다")
    void update_sameName_returnsWithoutSaving() {
      // given
      String sameName = "의류";
      Category category = categoryWithId(UUID.randomUUID(), sameName);
      CategoryUpdateCommand command = new CategoryUpdateCommand(category.getId(), sameName);
      given(categoryRepository.findById(category.getId())).willReturn(Optional.of(category));

      // when
      categoryCommandService.update(command);

      // then
      then(categoryRepository).should(never()).existsByName(any());
      assertThat(category.getName()).isEqualTo(sameName);
    }

    @Test
    @DisplayName("다른 이름이 이미 존재하면 DUPLICATE_NAME 예외를 던진다")
    void update_differentNameDuplicated_throwsException() {
      // given
      Category category = categoryWithId(UUID.randomUUID(), "의류");
      String duplicatedName = "액세서리";
      CategoryUpdateCommand command = new CategoryUpdateCommand(category.getId(), duplicatedName);
      given(categoryRepository.findById(category.getId())).willReturn(Optional.of(category));
      given(categoryRepository.existsByName(duplicatedName)).willReturn(true);

      // when & then
      assertThatThrownBy(() -> categoryCommandService.update(command))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.DUPLICATE_NAME);
    }

    @Test
    @DisplayName("다른 이름이 사용 가능하면 이름을 변경한다")
    void update_differentNameAvailable_updatesName() {
      // given
      Category category = categoryWithId(UUID.randomUUID(), "의류");
      String newName = "액세서리";
      CategoryUpdateCommand command = new CategoryUpdateCommand(category.getId(), newName);
      given(categoryRepository.findById(category.getId())).willReturn(Optional.of(category));
      given(categoryRepository.existsByName(newName)).willReturn(false);

      // when
      categoryCommandService.update(command);

      // then
      assertThat(category.getName()).isEqualTo(newName);
    }
  }

  @Nested
  @DisplayName("카테고리 삭제")
  class Delete {

    @Test
    @DisplayName("존재하는 카테고리를 삭제한다")
    void delete_existingId_deletesCategory() {
      // given
      Category category = categoryWithId(UUID.randomUUID(), "의류");
      given(categoryRepository.findById(category.getId())).willReturn(Optional.of(category));

      // when
      categoryCommandService.delete(category.getId());

      // then
      then(categoryRepository).should().delete(category);
    }

    @Test
    @DisplayName("없는 카테고리를 삭제하면 NOT_FOUND 예외를 던지고 삭제하지 않는다")
    void delete_notFound_throwsException() {
      // given
      UUID missingId = UUID.randomUUID();
      given(categoryRepository.findById(missingId)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> categoryCommandService.delete(missingId))
          .isInstanceOf(BusinessException.class)
          .hasFieldOrPropertyWithValue("errorCode", CategoryErrorCode.NOT_FOUND);
      then(categoryRepository).should(never()).delete(any());
    }
  }

  private Category categoryWithId(UUID id, String name) {
    Category category = Category.create().name(name).build();
    ReflectionTestUtils.setField(category, "id", id);
    return category;
  }
}
