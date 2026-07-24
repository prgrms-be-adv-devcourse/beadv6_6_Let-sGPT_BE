package com.openat.chat.infrastructure.inference.tool;

import org.springframework.ai.chat.model.ToolContext;

final class AdminToolContexts {

  private AdminToolContexts() {}

  static AdminToolExecutionContext required(ToolContext toolContext) {
    Object value = toolContext.getContext().get(AdminToolExecutionContext.KEY);
    if (value instanceof AdminToolExecutionContext context) {
      return context;
    }
    throw new IllegalStateException("관리자 챗봇 도구 실행 컨텍스트가 없어요.");
  }
}
