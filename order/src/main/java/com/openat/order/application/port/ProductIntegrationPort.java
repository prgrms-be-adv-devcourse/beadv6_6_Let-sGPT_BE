package com.openat.order.application.port;

import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import java.util.UUID;

public interface ProductIntegrationPort {

    OrderSnapshotInfo fetchOrderSnapshot(UUID dropId);

    void decreaseStock(UUID dropId, StockDecreaseCommand command);

    void restoreStock(UUID dropId, StockRestoreCommand command);
}
