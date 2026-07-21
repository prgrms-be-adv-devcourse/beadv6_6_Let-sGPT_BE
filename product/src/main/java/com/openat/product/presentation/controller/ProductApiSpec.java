package com.openat.product.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.product.presentation.dto.ProductCreateRequest;
import com.openat.product.presentation.dto.ProductResponse;
import com.openat.product.presentation.dto.ProductSearchRequest;
import com.openat.product.presentation.dto.ProductUpdateRequest;
import com.openat.support.docs.ApiErrorResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

@Tag(name = "Product", description = "상품 API")
public interface ProductApiSpec {

  @Operation(summary = "상품 등록", description = "판매자가 신규 상품을 등록한다.")
  @ApiResponse(
      responseCode = "201",
      description = "생성 성공",
      headers = @Header(name = "Location", description = "생성된 리소스 URI"))
  @ApiErrorResponses
  ResponseEntity<Void> create(UUID sellerId, ProductCreateRequest request);

  @Operation(summary = "상품 단건 조회", description = "상품 id로 단건을 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<ProductResponse> getProduct(UUID id);

  @Operation(summary = "상품 목록 조회", description = "상품을 페이징·검색 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<ProductResponse>> searchProducts(
      @ParameterObject ProductSearchRequest request, Pageable pageable);

  @Operation(summary = "본인 상품 목록 조회", description = "판매자가 자신이 등록한 상품을 페이징·검색 조회한다.")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiErrorResponses
  ResponseEntity<PageResponse<ProductResponse>> searchMyProducts(
      UUID sellerId, @ParameterObject ProductSearchRequest request, Pageable pageable);

  @Operation(summary = "상품 수정", description = "판매자가 자신의 상품 정보를 수정한다.")
  @ApiResponse(responseCode = "204", description = "수정 성공")
  @ApiErrorResponses
  ResponseEntity<Void> update(UUID sellerId, UUID id, ProductUpdateRequest request);

  @Operation(summary = "상품 삭제", description = "판매자가 자신의 상품을 삭제한다.")
  @ApiResponse(responseCode = "204", description = "삭제 성공")
  @ApiErrorResponses
  ResponseEntity<Void> delete(UUID sellerId, UUID id);

  // NOTE(auth): @CurrentUser UUID sellerId = 활성 스토어 sellerInfoId(판매자 토큰 스코프). 상품은 회원이 아니라
  //   스토어(SellerInfo)에 귀속되며 write(create/update/delete)·본인 목록(/me)의 소유·필터 기준으로 쓴다.
  //   게이트웨이가 scoped JWT를 검증한 뒤 X-Seller-Id로 주입하고 CurrentUserArgumentResolver가 이를 바인딩한다.
  //   상세: FE docs/auth.md.
  //
  // NOTE(sellerName): 판매자 표시명(ProductResponse.sellerName)은 member 스토어 이벤트를 소비한 로컬 투영
  //   (seller 서브도메인 SellerStore)에서 배치 해석한다 — N+1·런타임 결합 없음. member 가 seller_registered/updated
  //   이벤트(payload: sellerInfoId, storeName)를 발행해야 실제로 채워짐(로컬은 시드로 표시). 상세: DECISIONS.md.
  //
  // NOTE(image): ProductImageController가 presigned PUT URL 발급과 final 이미지 조회를 제공한다
  //   (POST /api/v1/products/images/presign · GET /api/v1/products/images/{key}). 발급받은
  //   stagingKey를 thumbnailKey·imageKeys로 넘기면 상품 등록·수정 시 final 키로 승격한다.
  //   S3와 로컬 MinIO는 같은 어댑터를 쓴다. [screens/14]
}
