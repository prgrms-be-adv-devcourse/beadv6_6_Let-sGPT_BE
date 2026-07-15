package com.openat.search.product.presentation.controller;

import com.openat.search.product.application.dto.ProductSearchSyncTestResponse;
import com.openat.search.product.application.service.ProductTopicProduceTestService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/searchs")
public class ProductSearchSyncTestController {

  private final ProductTopicProduceTestService productTopicProduceTestService;

  @GetMapping(value = "/search-sync-test", produces = MediaType.APPLICATION_JSON_VALUE)
  public List<ProductSearchSyncTestResponse> searchSyncTest(
      @RequestParam(defaultValue = "1970-01-01T00:00:00.000Z") String changedAfter) {
    return productTopicProduceTestService.searchSyncTestProducts(Instant.parse(changedAfter));
  }
}
