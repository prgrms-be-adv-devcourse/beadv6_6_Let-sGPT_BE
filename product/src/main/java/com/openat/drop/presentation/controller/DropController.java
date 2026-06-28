package com.openat.drop.presentation.controller;

import com.openat.common.response.PageResponse;
import com.openat.common.web.Locations;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.application.usecase.DropQueryUseCase;
import com.openat.drop.presentation.dto.DropCreateRequest;
import com.openat.drop.presentation.dto.DropResponse;
import com.openat.drop.presentation.dto.DropSearchRequest;
import com.openat.support.auth.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/drops")
@RequiredArgsConstructor
public class DropController implements DropApiSpec {

  private final DropCommandUseCase dropCommandUseCase;
  private final DropQueryUseCase dropQueryUseCase;

  @Override
  @PostMapping
  public ResponseEntity<Void> create(
      @CurrentUser UUID sellerId, @Valid @RequestBody DropCreateRequest request) {
    UUID dropId = dropCommandUseCase.create(request.toCommand(sellerId));
    return ResponseEntity.created(Locations.fromCurrentRequest(dropId)).build();
  }

  @Override
  @GetMapping("/me")
  public ResponseEntity<PageResponse<DropResponse>> searchMyDrops(
      @CurrentUser UUID sellerId, @ModelAttribute DropSearchRequest request, Pageable pageable) {
    Page<DropResponse> page =
        dropQueryUseCase
            .searchDrops(request.toCondition(sellerId), pageable)
            .map(DropResponse::from);
    return ResponseEntity.ok(PageResponse.of(page));
  }

  @Override
  @GetMapping("/{dropId}")
  public ResponseEntity<DropResponse> getDrop(@PathVariable UUID dropId) {
    return ResponseEntity.ok(DropResponse.from(dropQueryUseCase.getDrop(dropId)));
  }

  @Override
  @GetMapping
  public ResponseEntity<PageResponse<DropResponse>> searchDrops(
      @ModelAttribute DropSearchRequest request, Pageable pageable) {
    Page<DropResponse> page =
        dropQueryUseCase.searchDrops(request.toCondition(), pageable).map(DropResponse::from);
    return ResponseEntity.ok(PageResponse.of(page));
  }

  @Override
  @DeleteMapping("/{dropId}")
  public ResponseEntity<Void> delete(@CurrentUser UUID sellerId, @PathVariable UUID dropId) {
    dropCommandUseCase.delete(dropId, sellerId);
    return ResponseEntity.noContent().build();
  }
}
