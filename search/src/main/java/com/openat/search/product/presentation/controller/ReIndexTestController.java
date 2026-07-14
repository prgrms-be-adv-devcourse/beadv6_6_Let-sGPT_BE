package com.openat.search.product.presentation.controller;

import com.openat.search.product.application.dto.ReIndexTestResult;
import com.openat.search.product.application.service.ReIndexTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/searchs")
public class ReIndexTestController {

  private final ReIndexTestService reIndexTestService;

  @GetMapping("/reIndexTest")
  public ResponseEntity<ReIndexTestResult> reIndexTestGet() {
    return ResponseEntity.ok(reIndexTestService.reIndexTest());
  }

  @PostMapping("/reIndexTest")
  public ResponseEntity<ReIndexTestResult> reIndexTestPost() {
    return ResponseEntity.ok(reIndexTestService.reIndexTest());
  }
}
