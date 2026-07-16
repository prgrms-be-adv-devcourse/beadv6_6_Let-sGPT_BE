package com.openat.productimport.application.service;

import com.openat.category.application.usecase.CategoryQueryUseCase;
import com.openat.product.application.usecase.ImageStorageUseCase;
import com.openat.productimport.application.dto.ProductImportRow;
import com.openat.productimport.application.dto.ProductImportRowResult;
import com.openat.productimport.infrastructure.config.ProductImportProperties;
import com.openat.productimport.infrastructure.persistence.ProductImportReceiptRepository;
import com.openat.productimport.infrastructure.source.ProductImportSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductImportRowService {

  private static final int MAX_ADDITIONAL_IMAGES = 10;

  private final ProductImportReceiptRepository receiptRepository;
  private final CategoryQueryUseCase categoryQueryUseCase;
  private final ImageStorageUseCase imageStorageUseCase;
  private final ProductImportRegistrationService registrationService;
  private final ProductImportProperties properties;

  public ProductImportRowResult process(
      UUID sellerId,
      ProductImportRow row,
      ProductImportSource source,
      String location,
      boolean dryRun) {
    var existing = receiptRepository.findBySellerIdAndExternalId(sellerId, row.externalId());
    if (existing.isPresent()) {
      var receipt = existing.get();
      return ProductImportRowResult.skipped(receipt.getProductId(), receipt.getDropId());
    }

    ProductImportRow resolvedRow = resolveCategoryAndValidateSchedule(row);
    if (row.imageFiles().size() > MAX_ADDITIONAL_IMAGES) {
      throw new IllegalArgumentException("추가 이미지는 상품당 최대 10개까지 사용할 수 있습니다.");
    }

    ImageAsset thumbnail = loadImage(source, location, row.thumbnailFile());
    List<ImageAsset> additionalImages = new ArrayList<>();
    for (String imageFile : row.imageFiles()) {
      if (!imageFile.equals(row.thumbnailFile())) {
        additionalImages.add(loadImage(source, location, imageFile));
      }
    }

    if (dryRun) {
      return ProductImportRowResult.validated();
    }

    String thumbnailKey = imageStorageUseCase.store(thumbnail.content(), thumbnail.fileName());
    List<String> imageKeys =
        additionalImages.stream()
            .map(asset -> imageStorageUseCase.store(asset.content(), asset.fileName()))
            .toList();
    return registrationService.register(sellerId, resolvedRow, thumbnailKey, imageKeys);
  }

  private ProductImportRow resolveCategoryAndValidateSchedule(ProductImportRow row) {
    UUID resolvedCategoryId = row.categoryId();
    if (row.categoryId() != null) {
      var category = categoryQueryUseCase.getById(row.categoryId());
      if (row.categoryName() != null && !row.categoryName().equals(category.getName())) {
        throw new IllegalArgumentException("category_id와 category_name이 서로 다른 카테고리를 가리킵니다.");
      }
    } else if (row.categoryName() != null) {
      resolvedCategoryId = categoryQueryUseCase.getByName(row.categoryName()).getId();
    }
    if (row.hasDrop() && !row.openAt().isAfter(Instant.now())) {
      throw new IllegalArgumentException("open_at은 배치 실행 시각보다 뒤여야 합니다.");
    }
    return row.withCategoryId(resolvedCategoryId);
  }

  private ImageAsset loadImage(ProductImportSource source, String location, String relativePath) {
    byte[] content = source.read(location, relativePath, properties.maxImageBytes());
    validateImage(content, relativePath);
    return new ImageAsset(fileName(relativePath), content);
  }

  private static void validateImage(byte[] content, String path) {
    String extension = extension(path);
    boolean valid =
        switch (extension) {
          case ".jpg", ".jpeg" -> isJpeg(content);
          case ".png" -> isPng(content);
          case ".webp" -> isWebp(content);
          default -> false;
        };
    if (!valid) {
      throw new IllegalArgumentException("이미지는 실제 내용과 확장자가 일치하는 JPG, PNG, WebP 파일이어야 합니다: " + path);
    }
  }

  private static boolean isJpeg(byte[] content) {
    return content.length >= 3
        && (content[0] & 0xff) == 0xff
        && (content[1] & 0xff) == 0xd8
        && (content[2] & 0xff) == 0xff;
  }

  private static boolean isPng(byte[] content) {
    byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    if (content.length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if (content[index] != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isWebp(byte[] content) {
    return content.length >= 12
        && content[0] == 'R'
        && content[1] == 'I'
        && content[2] == 'F'
        && content[3] == 'F'
        && content[8] == 'W'
        && content[9] == 'E'
        && content[10] == 'B'
        && content[11] == 'P';
  }

  private static String extension(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static String fileName(String path) {
    String normalized = path.replace('\\', '/');
    int slash = normalized.lastIndexOf('/');
    return slash < 0 ? normalized : normalized.substring(slash + 1);
  }

  private record ImageAsset(String fileName, byte[] content) {}
}
