package com.openat.drop.application.port;

import java.util.UUID;

public interface DropStockMetricsPort {

  void register(UUID dropId);
}
