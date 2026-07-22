package com.openat.chat.domain.knowledge;

public enum OperationContextId {
  PLATFORM("platform.md"),
  ORDER_PAYMENT("orders-payments.md"),
  CATALOG_INVENTORY("catalog-inventory.md"),
  MEMBER_ACCESS("members.md"),
  SETTLEMENT("settlement.md"),
  RELIABILITY("reliability.md"),
  REPORTING("reporting.md"),
  OFFICE_PRODUCTIVITY("office-productivity.md");

  private final String resourceName;

  OperationContextId(String resourceName) {
    this.resourceName = resourceName;
  }

  public String resourceName() {
    return resourceName;
  }
}
