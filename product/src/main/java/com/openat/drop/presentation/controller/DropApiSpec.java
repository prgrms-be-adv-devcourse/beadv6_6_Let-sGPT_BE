package com.openat.drop.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.drop.presentation.dto.DropCreateRequest;
import com.openat.drop.presentation.dto.DropResponse;
import com.openat.drop.presentation.dto.DropSearchRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
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

  @Operation(summary = "본인 드롭 목록 조회", description = "판매자가 자신의 상품으로 등록한 드롭을 페이징·검색 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<DropResponse>> searchMyDrops(
      UUID sellerId, @ParameterObject DropSearchRequest request, Pageable pageable);

  @Operation(
      summary = "드롭 단건 조회",
      description = "드롭 id로 단건을 조회한다. 상태(OPEN·SOLD_OUT)와 잔여 수량은 오픈 시각·재고로 파생된다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<DropResponse> getDrop(UUID dropId);

  @Operation(
      summary = "드롭 목록 조회",
      description = "드롭을 상태·카테고리·검색어로 페이징 조회한다. status 필터는 생명주기 구간(REGISTERED·OPEN·CLOSE) 기준이다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<DropResponse>> searchDrops(
      @ParameterObject DropSearchRequest request, Pageable pageable);

  @Operation(
      summary = "드롭 삭제",
      description = "판매자가 자신의 드롭을 삭제한다. 오픈 전이면 soft delete, 오픈 후면 종료(CLOSE) 처리한다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiErrorResponses
  ResponseEntity<Void> delete(UUID sellerId, UUID dropId);

  // NOTE(auth): @CurrentUser UUID sellerId = 활성 스토어 sellerInfoId(판매자 토큰 스코프). 드롭은 회원이 아니라
  //   스토어(SellerInfo)에 귀속되며 write(create/delete)·본인 드롭(/me)의 소유·필터 기준으로 쓴다.
  //   게이트웨이가 scoped JWT를 검증한 뒤 X-Seller-Id로 주입하고 CurrentUserArgumentResolver가 이를 바인딩한다.
  //   상세: FE docs/auth.md.

  // TODO(fe-api): DropResponse 에 판매자 표시명(sellerName/storeName) 미포함 → 드롭 카드·상세 벤더 표기에 필요.
  //   출처는 member SellerInfo.storeName. ProductResponse 와 동일 이슈(ProductApiSpec 참고). (현재 FE 는 MSW
  // provisional)
}
