package com.openat.chat.domain.query;

import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import java.util.EnumSet;
import java.util.Set;

public enum InternalDataDomain {
  ORDER_SALES(EnumSet.of(Dataset.ORDER)),
  PAYMENT_REFUND(EnumSet.of(Dataset.PAYMENT, Dataset.REFUND)),
  SETTLEMENT_RECONCILIATION(
      EnumSet.of(
          Dataset.SETTLEMENT_ORDER,
          Dataset.SELLER_SETTLEMENT,
          Dataset.SETTLEMENT_BATCH,
          Dataset.SETTLEMENT_ADJUSTMENT,
          Dataset.RECONCILIATION,
          Dataset.RECONCILIATION_DISCREPANCY)),
  MEMBERSHIP(EnumSet.of(Dataset.MEMBER_CURRENT, Dataset.MEMBER_REGISTRATION)),
  CATALOG_INVENTORY(EnumSet.of(Dataset.PRODUCT, Dataset.DROP)),
  EVENT_SAGA_RELIABILITY(EnumSet.of(Dataset.EVENT_PIPELINE, Dataset.ORDER_SAGA));

  private final Set<Dataset> datasets;

  InternalDataDomain(Set<Dataset> datasets) {
    this.datasets = Set.copyOf(datasets);
  }

  public Set<Dataset> datasets() {
    return datasets;
  }

  public boolean supports(Dataset dataset) {
    return dataset != null && datasets.contains(dataset);
  }
}
