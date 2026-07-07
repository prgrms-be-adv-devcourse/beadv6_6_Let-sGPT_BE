package com.openat.drop.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DropErrorCode implements ErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "DROP_NOT_FOUND", "존재하지 않는 드롭입니다."),
  NOT_OWNER(HttpStatus.FORBIDDEN, "DROP_NOT_OWNER", "드롭에 대한 권한이 없습니다."),
  OPEN_EXISTS(HttpStatus.CONFLICT, "DROP_OPEN_EXISTS", "진행 중인 드롭이 있어 삭제할 수 없습니다."),
  NOT_OPEN(HttpStatus.BAD_REQUEST, "DROP_NOT_OPEN", "아직 오픈 전입니다."),
  SOLD_OUT(HttpStatus.CONFLICT, "DROP_SOLD_OUT", "재고가 없습니다."),
  LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "DROP_LIMIT_EXCEEDED", "1인 구매 한도를 초과했습니다."),
  CLOSED(HttpStatus.CONFLICT, "DROP_CLOSED", "종료된 드롭입니다."),
  NOT_CACHED(HttpStatus.CONFLICT, "DROP_NOT_CACHED", "현재 활성화되지 않은 드롭입니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
