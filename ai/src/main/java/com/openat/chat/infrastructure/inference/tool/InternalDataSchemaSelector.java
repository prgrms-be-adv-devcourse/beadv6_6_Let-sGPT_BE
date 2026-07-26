package com.openat.chat.infrastructure.inference.tool;

import com.openat.chat.domain.query.InternalDataDomain;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class InternalDataSchemaSelector {

  @Tool(
      name = "loadInternalDataSchemas",
      description =
          "OPENAT 내부 통계가 필요한 질문에서 상세 조회 스키마를 불러올 영역을 고른다. " + "질문에 필요한 영역을 빠짐없이 한 번에 선택한다.")
  public String loadInternalDataSchemas(
      @ToolParam(
              description =
                  "ORDER_SALES, PAYMENT_REFUND, SETTLEMENT_RECONCILIATION, MEMBERSHIP, "
                      + "CATALOG_INVENTORY, EVENT_SAGA_RELIABILITY 중 필요한 영역")
          List<InternalDataDomain> domains) {
    throw new UnsupportedOperationException("영역 선택 도구는 정의만 사용해요.");
  }
}
