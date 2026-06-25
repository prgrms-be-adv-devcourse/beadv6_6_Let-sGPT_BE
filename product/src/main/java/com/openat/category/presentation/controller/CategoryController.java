package com.openat.category.presentation.controller;

import com.openat.category.application.usecase.CategoryCommandUseCase;
import com.openat.category.presentation.dto.CategoryCreateRequest;
import com.openat.category.presentation.dto.CategoryUpdateRequest;
import com.openat.common.web.Locations;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController implements CategoryApiSpec {

  private final CategoryCommandUseCase categoryCommandUseCase;

  @Override
  @PostMapping
  public ResponseEntity<Void> create(@Valid @RequestBody CategoryCreateRequest request) {
    UUID categoryId = categoryCommandUseCase.create(request.toCommand());
    return ResponseEntity.created(Locations.fromCurrentRequest(categoryId)).build();
  }

  @Override
  @PatchMapping("/{id}")
  public ResponseEntity<Void> update(
      @PathVariable UUID id, @Valid @RequestBody CategoryUpdateRequest request) {
    categoryCommandUseCase.update(request.toCommand(id));
    return ResponseEntity.noContent().build();
  }

  @Override
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    categoryCommandUseCase.delete(id);
    return ResponseEntity.noContent().build();
  }
}
