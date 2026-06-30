package com.openat.drop.presentation.controller;

import com.openat.drop.application.dto.DropSnapshotInfo;
import com.openat.drop.application.usecase.DropQueryUseCase;
import com.openat.drop.presentation.dto.DropSnapshotResponse;
import com.openat.support.web.InternalApi;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/drops")
@InternalApi
@RequiredArgsConstructor
public class InternalDropQueryController {

  private final DropQueryUseCase dropQueryUseCase;

  @GetMapping("/{dropId}/order-snapshot")
  public ResponseEntity<DropSnapshotResponse> getDropSnapshot(@PathVariable UUID dropId) {
    DropSnapshotInfo snapshot = dropQueryUseCase.getDropSnapshot(dropId);
    return ResponseEntity.ok(DropSnapshotResponse.from(snapshot));
  }
}
