package com.openat.recommendation.application.port.out;

import com.openat.recommendation.domain.model.PurchaseSignal;
import java.util.List;
import java.util.UUID;

public interface OrderSignalClient {

  List<PurchaseSignal> getPurchaseSignals(UUID memberId);
}
