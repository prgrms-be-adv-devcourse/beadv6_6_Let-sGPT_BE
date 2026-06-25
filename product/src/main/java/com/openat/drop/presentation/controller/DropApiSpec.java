package com.openat.drop.presentation.controller;

import com.openat.drop.presentation.dto.DropCreateRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

@Tag(name = "Drop", description = "드롭 API")
public interface DropApiSpec {

  @Operation(summary = "드롭 등록", description = "판매자가 자신의 상품으로 한정 수량 드롭을 등록한다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      headers = @Header(name = "Location", description = "생성된 리소스 URI"))
  @ApiErrorResponses
  ResponseEntity<Void> create(UUID sellerId, DropCreateRequest request);

  @Operation(
      summary = "드롭 삭제",
      description = "판매자가 자신의 드롭을 삭제한다. 오픈 전이면 soft delete, 오픈 후면 종료(CLOSE) 처리한다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiErrorResponses
  ResponseEntity<Void> delete(UUID sellerId, UUID dropId);
}
