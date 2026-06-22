package com.openat.support.docs;

import com.openat.common.error.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
  @ApiResponse(
      responseCode = "400",
      description = "입력값 검증 실패",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
  @ApiResponse(
      responseCode = "500",
      description = "서버 내부 오류",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
public @interface ApiErrorResponses {}
