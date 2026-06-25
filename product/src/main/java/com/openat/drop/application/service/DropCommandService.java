package com.openat.drop.application.service;

import com.openat.drop.application.dto.DropCreateCommand;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.domain.event.DropRegisteredEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.model.Product;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DropCommandService implements DropCommandUseCase {

  private final DropRepository dropRepository;
  private final ProductQueryUseCase productQueryUseCase;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  public UUID create(DropCreateCommand command) {
    Product product = productQueryUseCase.getOwnedProduct(command.productId(), command.sellerId());

    Drop newDrop =
        Drop.schedule()
            .product(product)
            .dropPrice(command.dropPrice())
            .totalQuantity(command.totalQuantity())
            .limitPerUser(command.limitPerUser())
            .openAt(command.openAt())
            .closeAt(command.closeAt())
            .build();

    Drop savedDrop = dropRepository.save(newDrop);
    eventPublisher.publishEvent(
        new DropRegisteredEvent(savedDrop.getId(), savedDrop.getOpenAt(), savedDrop.getCloseAt()));
    return savedDrop.getId();
  }
}
