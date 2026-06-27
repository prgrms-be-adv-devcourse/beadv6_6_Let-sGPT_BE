package com.openat.order.infrastructure.client;

import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "product-service", url = "${services.product.url}")
public interface ProductInternalApiClient {

    @GetMapping("/internal/drops/{dropId}/order-snapshot")
    OrderSnapshotResponse fetchOrderSnapshot(@PathVariable UUID dropId);

    @PostMapping("/internal/drops/{dropId}/stock-deductions")
    void decreaseStock(@PathVariable UUID dropId, @RequestBody StockChangeRequest request);

    @PostMapping("/internal/drops/{dropId}/stock-rollbacks")
    void restoreStock(@PathVariable UUID dropId, @RequestBody StockChangeRequest request);
}
