package com.openat.product.application.service;

import com.openat.product.application.dto.ProductCreateCommand;
import com.openat.product.application.dto.ProductUpdateCommand;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.product.application.usecase.ProductCommandUseCase;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductCommandService implements ProductCommandUseCase {

  private final ImageStorageUseCase imageStorageUseCase;
  private final ProductCommandTransaction commandTransaction;

  @Override
  public UUID create(ProductCreateCommand command) {
    commandTransaction.validateCreate(command.categoryId());
    String thumbnailKey = promoteImageKey(command.thumbnailKey());
    List<String> imageKeys = promoteImageKeys(command.imageKeys());
    return commandTransaction.create(command, thumbnailKey, imageKeys);
  }

  @Override
  public void update(ProductUpdateCommand command) {
    commandTransaction.validateUpdate(command.id(), command.sellerId(), command.categoryId());
    String thumbnailKey = promoteImageKey(command.thumbnailKey());
    List<String> imageKeys = promoteImageKeys(command.imageKeys());
    commandTransaction.update(command, thumbnailKey, imageKeys);
  }

  @Override
  public void delete(UUID id, UUID sellerId) {
    commandTransaction.delete(id, sellerId);
  }

  private String promoteImageKey(String key) {
    if (key == null || key.isBlank()) {
      return key;
    }
    return imageStorageUseCase.promote(key);
  }

  private List<String> promoteImageKeys(List<String> imageKeys) {
    if (imageKeys == null) {
      return null;
    }
    return imageKeys.stream().map(this::promoteImageKey).toList();
  }
}
