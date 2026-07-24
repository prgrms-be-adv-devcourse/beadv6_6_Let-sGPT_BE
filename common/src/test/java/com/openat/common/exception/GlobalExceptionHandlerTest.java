package com.openat.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void 매칭되는_라우트가_없으면_404_NOT_FOUND로_응답한다() {
    NoResourceFoundException e =
        new NoResourceFoundException(HttpMethod.GET, "/no/such/path", "no/such/path");

    ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(e);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo(CommonErrorCode.NOT_FOUND.getCode());
  }
}
