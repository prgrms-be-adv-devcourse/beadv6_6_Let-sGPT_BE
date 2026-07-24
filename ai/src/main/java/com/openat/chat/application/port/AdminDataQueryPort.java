package com.openat.chat.application.port;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.domain.query.AdminDataQueryPlan;

public interface AdminDataQueryPort {

  boolean isAvailable();

  AdminDataQueryResult.Metric countExpiredPaymentPendingOrders();

  AdminDataQueryResult.OrderLookup lookupOrder(AdminDataQueryPlan.OrderLookup plan);
}
