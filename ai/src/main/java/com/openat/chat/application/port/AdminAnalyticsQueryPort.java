package com.openat.chat.application.port;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan;

public interface AdminAnalyticsQueryPort {

  boolean isAvailable();

  AdminAnalyticsQueryResult query(AdminAnalyticsQueryPlan.Query plan);
}
