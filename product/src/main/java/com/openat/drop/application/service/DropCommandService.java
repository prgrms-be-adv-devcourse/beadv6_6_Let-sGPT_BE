package com.openat.drop.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropCreateCommand;
import com.openat.drop.application.usecase.DropCommandUseCase;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.event.DropRegisteredEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.product.application.usecase.ProductQueryUseCase;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
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

  @Override
  public void delete(UUID dropId, UUID sellerId) {
    Drop drop =
        dropRepository
            .findById(dropId)
            .orElseThrow(() -> new BusinessException(DropErrorCode.NOT_FOUND));
    if (!drop.getProduct().getSellerId().equals(sellerId)) {
      throw new BusinessException(DropErrorCode.NOT_OWNER);
    }
    if (drop.getStatus() == DropStatus.CLOSE) {
      return;
    }

    if (drop.isBeforeOpen(Instant.now())) {
      dropRepository.delete(drop);
      eventPublisher.publishEvent(new DropDeletedEvent(dropId));
    } else {
      drop.close();
      eventPublisher.publishEvent(new DropClosedEvent(dropId));
    }
  }

  @EventListener
  public void onProductDeleted(ProductDeletedEvent event) {
    Instant now = Instant.now();
    List<Drop> drops = dropRepository.findAllByProductId(event.productId());

    boolean hasLiveDrop = drops.stream().anyMatch(drop -> drop.isLive(now));
    if (hasLiveDrop) {
      throw new BusinessException(DropErrorCode.OPEN_EXISTS);
    }

    for (Drop drop : drops) {
      dropRepository.delete(drop);
      eventPublisher.publishEvent(new DropDeletedEvent(drop.getId()));
    }
  }
}
