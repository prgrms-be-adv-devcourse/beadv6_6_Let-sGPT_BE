package com.openat.drop.presentation.controller;

import com.openat.common.web.Locations;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.presentation.dto.DropCreateRequest;
import com.openat.support.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/drops")
@RequiredArgsConstructor
public class DropController implements DropApiSpec {

  private final DropCommandUseCase dropCommandUseCase;

  @Override
  @PostMapping
  public ResponseEntity<Void> create(
      @CurrentUser UUID sellerId, @Valid @RequestBody DropCreateRequest request) {
    UUID dropId = dropCommandUseCase.create(request.toCommand(sellerId));
    return ResponseEntity.created(Locations.fromCurrentRequest(dropId)).build();
  }
}
