package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.application.service.OperationContextRegistry;
import com.openat.chat.application.service.OperationContextRegistry.Selection;
import com.openat.chat.domain.knowledge.OperationContextId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class OperationContextTools {

  private static final Logger log = LoggerFactory.getLogger(OperationContextTools.class);

  private final OperationContextRegistry contextRegistry;

  public OperationContextTools(OperationContextRegistry contextRegistry) {
    this.contextRegistry = contextRegistry;
  }

  @Tool(
      name = "getOpenAtOperationsContext",
      description =
          "OPENAT 또는 우리 플랫폼의 정체성, 구조, 운영·관리 방법, 점검, 보고와 엑셀 활용에 관한 확인된 내부 문서를 가져온다. 이런 질문에는 반드시 사용한다.")
  public AdminToolResult getOpenAtOperationsContext(
      @ToolParam(
              description =
                  "필요한 영역 목록. PLATFORM, ORDER_PAYMENT, CATALOG_INVENTORY, MEMBER_ACCESS, SETTLEMENT, RELIABILITY, REPORTING, OFFICE_PRODUCTIVITY 중 질문과 직접 관련된 영역만 선택한다.")
          List<OperationContextId> contextIds,
      ToolContext toolContext) {
    AdminToolExecutionContext context = AdminToolContexts.required(toolContext);
    context.started();
    try {
      Selection selection = contextRegistry.select(contextIds);
      OperationContextFacts facts =
          new OperationContextFacts(selection.included(), selection.omitted(), selection.content());
      if (!selection.omitted().isEmpty()) {
        return context.completed(
            AdminToolResult.partial(
                "OPERATION_CONTEXT_BUDGET_EXCEEDED", "입력 예산 안에서 직접 관련된 운영 문서만 제공했어요.", facts));
      }
      return context.completed(AdminToolResult.success(facts));
    } catch (IllegalArgumentException exception) {
      return context.completed(
          AdminToolResult.failed("OPERATION_CONTEXT_INVALID", exception.getMessage()));
    } catch (RuntimeException exception) {
      log.warn("운영 컨텍스트 도구 실패 errorType={}", exception.getClass().getSimpleName());
      return context.completed(
          AdminToolResult.failed("OPERATION_CONTEXT_FAILED", "운영 문서를 가져오지 못했어요."));
    }
  }

  public record OperationContextFacts(
      List<OperationContextId> includedContextIds,
      List<OperationContextId> omittedContextIds,
      String content) {}
}
