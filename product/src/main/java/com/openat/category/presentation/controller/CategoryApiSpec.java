package com.openat.category.presentation.controller;

import com.openat.category.presentation.dto.CategoryCreateRequest;
import com.openat.category.presentation.dto.CategoryUpdateRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@Tag(name = "Category", description = "카테고리 API")
public interface CategoryApiSpec {

  @Operation(summary = "카테고리 등록", description = "신규 상품 카테고리를 등록한다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      headers = @Header(name = "Location", description = "생성된 리소스 URI"))
  @ApiErrorResponses
  ResponseEntity<Void> create(CategoryCreateRequest request);

  @Operation(summary = "카테고리 수정", description = "카테고리명을 수정한다.")
  @ApiResponse(responseCode = "204", description = "수정 성공")
  @ApiErrorResponses
  ResponseEntity<Void> update(UUID id, CategoryUpdateRequest request);

  @Operation(summary = "카테고리 삭제", description = "카테고리를 삭제한다. 참조 상품은 미분류로 전환된다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiErrorResponses
  ResponseEntity<Void> delete(UUID id);
}
