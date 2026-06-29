package com.openat.support.seed;

import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.seller.application.usecase.SellerStoreCommandUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * local/dev 데모 시드. 실서버 구동 시 카탈로그가 비지 않도록 상품·드롭·재고이력을 멱등 삽입한다(카테고리는 {@code data.sql}).
 *
 * <p>OPEN/SOLD_OUT 드롭의 잔여는 재고이력(원장) DEDUCT로 선반영한다 — 기동 워밍({@code DropCacheWarmer})이 {@code 총량 +
 * 원장합계}로 잔여를 계산하므로, 캐시를 직접 워밍하면 부트스트랩에 덮인다. {@code DropBootstrapRunner}보다 먼저 실행(@Order)해 시드된 드롭/원장을
 * 부트스트랩 워밍이 읽도록 한다.
 */
@Slf4j
@Component
@Profile({"local", "dev"})
@Order(0)
@RequiredArgsConstructor
public class SeedDataRunner implements ApplicationRunner {

  // 데모 스토어 식별자(sellerInfoId). 상품/드롭은 스토어에 귀속 — member 시드와 동일 값 사용 합의 필요.
  private static final UUID DEMO_SELLER_INFO_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID DEMO_BUYER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String DEMO_STORE_NAME = "오픈앳 스튜디오";

  private static final String[] PRODUCT_NAMES = {
    "오버사이즈 후디 차콜", "미니멀 크로스백", "잉크펜 세트 블랙", "와이어리스 이어버드",
    "아트토이 베어 화이트", "캔버스 토트백", "릴랙스 핏 스웨트팬츠", "시그니처 삭스 3팩",
    "그래픽 머그 세라믹", "메탈 키링 실버", "노트북 슬리브 13", "한정판 러너 SS26",
    "콜라보 캡 화이트", "스튜디오 노트 A5", "데스크 매트 우드", "피규어 컬렉터스 박스"
  };
  private static final long PRICE_BASE = 39_000L;
  private static final long PRICE_STEP = 12_000L;
  private static final int NO_PRICE_ORDER = 8; // 8번 상품은 가격 미정(null)

  // FE 목 갤러리(buildGallery)와 동일하게 썸네일 시드에 접미사를 붙여 추가 이미지를 파생한다.
  private static final String[] GALLERY_SUFFIXES = {"-b", "-c", "-d"};

  private final CategoryRepository categoryRepository;
  private final ProductRepository productRepository;
  private final DropRepository dropRepository;
  private final StockHistoryRepository stockHistoryRepository;
  private final SellerStoreCommandUseCase sellerStoreCommandUseCase;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (alreadySeeded()) {
      return;
    }
    sellerStoreCommandUseCase.upsert(DEMO_SELLER_INFO_ID, DEMO_STORE_NAME);
    Map<String, Product> productsByName = seedProducts();
    int dropCount = seedDrops(productsByName, Instant.now());
    log.info("데모 시드 삽입 완료 - 상품 {}건, 드롭 {}건", productsByName.size(), dropCount);
  }

  private boolean alreadySeeded() {
    return productRepository
            .search(new ProductSearchCondition(null, null, null), PageRequest.of(0, 1))
            .getTotalElements()
        > 0;
  }

  private Map<String, Product> seedProducts() {
    List<Category> categories = categoryRepository.findAll();
    Map<String, Product> productsByName = new HashMap<>();
    for (int order = 1; order <= PRODUCT_NAMES.length; order++) {
      String name = PRODUCT_NAMES[order - 1];
      Product product =
          Product.create()
              .sellerId(DEMO_SELLER_INFO_ID)
              .name(name)
              .description(descriptionOf(name))
              .category(categoryFor(categories, order))
              .price(priceOf(order))
              .thumbnailKey(imageUrl(order, ""))
              .imageKeys(galleryOf(order))
              .build();
      productsByName.put(name, productRepository.save(product));
    }
    return productsByName;
  }

  private String descriptionOf(String name) {
    return name + " — 한정 수량으로 만나는 openAt 단독 상품. 소재와 마감에 집중한 시즌 에디션입니다.";
  }

  private String imageUrl(int order, String suffix) {
    return "https://picsum.photos/seed/openat-" + order + suffix + "/640/800";
  }

  private List<String> galleryOf(int order) {
    List<String> gallery = new ArrayList<>();
    for (String suffix : GALLERY_SUFFIXES) {
      gallery.add(imageUrl(order, suffix));
    }
    return gallery;
  }

  private Long priceOf(int order) {
    if (order == NO_PRICE_ORDER) {
      return null;
    }
    return PRICE_BASE + (order - 1) * PRICE_STEP;
  }

  private Category categoryFor(List<Category> categories, int order) {
    if (categories.isEmpty()) {
      return null;
    }
    return categories.get((order - 1) % categories.size());
  }

  private int seedDrops(Map<String, Product> productsByName, Instant now) {
    List<DropSpec> specs = dropSpecs();
    for (DropSpec spec : specs) {
      Instant openAt = now.plus(spec.openOffset());
      Instant closeAt = null;
      if (spec.closeOffset() != null) {
        closeAt = now.plus(spec.closeOffset());
      }
      Drop drop =
          dropRepository.save(
              Drop.schedule()
                  .product(productsByName.get(spec.productName()))
                  .dropPrice(spec.dropPrice())
                  .totalQuantity(spec.totalQuantity())
                  .openAt(openAt)
                  .closeAt(closeAt)
                  .build());
      seedRemaining(drop, spec, now);
    }
    return specs.size();
  }

  /** 오픈 구간 드롭의 잔여를 원장 DEDUCT로 선반영한다(기동 워밍이 총량+원장으로 잔여를 계산하므로). */
  private void seedRemaining(Drop drop, DropSpec spec, Instant now) {
    boolean live =
        !drop.getOpenAt().isAfter(now)
            && (drop.getCloseAt() == null || drop.getCloseAt().isAfter(now));
    int deduct = spec.totalQuantity() - spec.remaining();
    if (!live || deduct <= 0) {
      return;
    }
    stockHistoryRepository.save(
        StockHistory.record()
            .dropId(drop.getId())
            .orderId(UUID.randomUUID())
            .buyerId(DEMO_BUYER_ID)
            .changeType(StockChangeType.DEDUCT)
            .quantity(deduct)
            .build());
  }

  private List<DropSpec> dropSpecs() {
    return List.of(
        new DropSpec("한정판 러너 SS26", 219_000L, 100, 37, Duration.ofDays(-1), null),
        new DropSpec("오버사이즈 후디 차콜", 139_000L, 50, 8, Duration.ofDays(-1), Duration.ofDays(7)),
        new DropSpec("콜라보 캡 화이트", 59_000L, 200, 152, Duration.ofDays(-2), null),
        new DropSpec("미니멀 크로스백", 89_000L, 150, 64, Duration.ofDays(-3), null),
        new DropSpec("아트토이 베어 화이트", 129_000L, 80, 80, Duration.ofDays(2), null),
        new DropSpec("피규어 컬렉터스 박스", 329_000L, 60, 60, Duration.ofDays(3), null),
        new DropSpec("와이어리스 이어버드", 159_000L, 120, 120, Duration.ofDays(5), null),
        new DropSpec("릴랙스 핏 스웨트팬츠", 49_000L, 300, 0, Duration.ofDays(-10), Duration.ofDays(-1)),
        new DropSpec("캔버스 토트백", 39_000L, 60, 0, Duration.ofDays(-2), null),
        new DropSpec("그래픽 머그 세라믹", 24_000L, 90, 0, Duration.ofDays(-3), null));
  }

  private record DropSpec(
      String productName,
      long dropPrice,
      int totalQuantity,
      int remaining,
      Duration openOffset,
      Duration closeOffset) {}
}
