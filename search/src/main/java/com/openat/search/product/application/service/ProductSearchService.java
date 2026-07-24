package com.openat.search.product.application.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.openat.search.product.application.dto.ProductSearchResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;
  private static final int KNN_RERANK_MULTIPLIER = 5;
  private static final int MIN_RERANK_CANDIDATES = 100;
  private static final int KNN_CANDIDATE_MULTIPLIER = 10;
  private static final int MAX_KNN_CANDIDATES = 10_000;
  private static final float KNN_RESCORE_OVERSAMPLE = 5.0F;
  private static final float VECTOR_SCORE_WEIGHT = 0.85F;
  private static final float LEXICAL_SCORE_WEIGHT = 0.15F;
  private static final float MIN_RELATIVE_RELEVANCE_RATIO = 0.90F;
  private static final String[] KOREAN_PARTICLE_SUFFIXES = {
    "으로", "에서", "에게", "까지", "부터", "처럼", "보다", "과", "와", "이", "가",
    "은", "는", "을", "를", "의", "에", "로", "도", "만"
  };
  private static final Set<String> SEARCH_STOP_WORDS =
      Set.of(
          "들어간",
          "들어있는",
          "포함된",
          "있는",
          "없는",
          "어울리는",
          "사용하는",
          "사용할",
          "좋은",
          "제품",
          "상품");
  private static final String[] VECTOR_SEARCH_SOURCE_INCLUDES = {
    "id",
    "sellerId",
    "name",
    "description",
    "categoryId",
    "categoryName",
    "sellerName",
    "price",
    "thumbnailKey",
    "imageKeys",
    "imgDescription",
    "createdAt",
    "updatedAt",
    "deletedAt"
  };

  private final ElasticsearchOperations elasticsearchOperations;
  private final ProductEmbeddingService productEmbeddingService;
  private final MeterRegistry meterRegistry;

  public Page<ProductSearchResult> search(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size) {
    String type = StringUtils.hasText(queryText) ? "vector" : "keyword";
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      return doSearch(queryText, categoryName, startPrice, endPrice, page, size);
    } finally {
      sample.stop(meterRegistry.timer("search.query", "type", type));
    }
  }

  private Page<ProductSearchResult> doSearch(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      Integer page,
      Integer size) {
    int normalizedPage = normalizePage(page);
    int normalizedSize = normalizeSize(size);

    if (StringUtils.hasText(queryText)) {
      return vectorSearch(
          queryText,
          categoryName,
          startPrice,
          endPrice,
          PageRequest.of(normalizedPage, normalizedSize));
    }

    PageRequest pageable =
        PageRequest.of(
            normalizedPage, normalizedSize, Sort.by(Sort.Direction.DESC, "createdAt"));

    NativeQuery query =
        NativeQuery.builder()
            .withQuery(
                queryBuilder ->
                    queryBuilder.bool(
                        bool ->
                            bool.filter(buildSearchFilters(categoryName, startPrice, endPrice))))
            .withPageable(pageable)
            .build();

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    var content = searchHits.stream().map(this::toResult).toList();
    return new PageImpl<>(content, pageable, searchHits.getTotalHits());
  }

  private Page<ProductSearchResult> vectorSearch(
      String queryText,
      String categoryName,
      Long startPrice,
      Long endPrice,
      PageRequest pageable) {
    String normalizedQuery = queryText.trim();
    float[] queryVector =
        productEmbeddingService
            .embed(normalizedQuery)
            .filter(embedding -> embedding.length > 0)
            .orElseThrow(
                () -> new IllegalStateException("Product search query embedding is empty."));

    int requestedWindow = Math.addExact((int) pageable.getOffset(), pageable.getPageSize());
    int candidateWindow =
        Math.min(
            MAX_KNN_CANDIDATES,
            Math.max(MIN_RERANK_CANDIDATES, requestedWindow * KNN_RERANK_MULTIPLIER));
    int numCandidates =
        Math.min(
            MAX_KNN_CANDIDATES,
            Math.max(candidateWindow, candidateWindow * KNN_CANDIDATE_MULTIPLIER));
    PageRequest candidatePageable = PageRequest.of(0, candidateWindow);

    NativeQuery query =
        NativeQuery.builder()
            .withKnnSearches(
                knn ->
                    knn.field("embedding")
                        .queryVector(toFloatList(queryVector))
                        .k(candidateWindow)
                        .numCandidates(numCandidates)
                        .rescoreVector(rescore -> rescore.oversample(KNN_RESCORE_OVERSAMPLE))
                        .filter(buildSearchFilters(categoryName, startPrice, endPrice)))
            .withPageable(candidatePageable)
            .withSourceFilter(
                FetchSourceFilter.of(
                    builder -> builder.withIncludes(VECTOR_SEARCH_SOURCE_INCLUDES)))
            .build();

    var searchHits = elasticsearchOperations.search(query, ProductDocument.class);
    Comparator<ProductSearchResult> resultComparator =
        Comparator.comparing(
            ProductSearchResult::score, Comparator.nullsLast(Comparator.reverseOrder()));

    var scoredResults =
        searchHits.stream()
            .map(searchHit -> toHybridResult(searchHit, normalizedQuery))
            .toList();
    var productTypeAwareResults =
        retainExplicitProductTypeMatches(scoredResults, normalizedQuery);
    var colorAwareResults =
        retainExplicitColorMatchesIfAvailable(productTypeAwareResults, normalizedQuery);
    var rankedResults =
        retainRelevantVectorResults(colorAwareResults).stream()
            .sorted(resultComparator)
            .toList();

    return new PageImpl<>(pageContent(rankedResults, pageable), pageable, rankedResults.size());
  }

  private List<Query> buildSearchFilters(String categoryName, Long startPrice, Long endPrice) {
    List<Query> filters = new ArrayList<>();

    filters.add(
        Query.of(
            query ->
                query.bool(
                    bool ->
                        bool.mustNot(
                            Query.of(
                                mustNot -> mustNot.exists(exists -> exists.field("deletedAt")))))));

    if (StringUtils.hasText(categoryName)) {
      filters.add(
          Query.of(
              query ->
                  query.match(match -> match.field("categoryName").query(categoryName.trim()))));
    }

    if (startPrice != null || endPrice != null) {
      filters.add(
          Query.of(
              query ->
                  query.range(
                      range ->
                          range.number(
                              number -> {
                                number.field("price");
                                if (startPrice != null) {
                                  number.gte(startPrice.doubleValue());
                                }
                                if (endPrice != null) {
                                  number.lte(endPrice.doubleValue());
                                }
                                return number;
                              }))));
    }

    return filters;
  }

  private ProductSearchResult toResult(SearchHit<ProductDocument> searchHit) {
    return new ProductSearchResult(searchHit.getContent(), safeScore(searchHit.getScore()));
  }

  private ProductSearchResult toHybridResult(
      SearchHit<ProductDocument> searchHit, String queryText) {
    ProductDocument document = searchHit.getContent();
    float vectorScore = scoreOrZero(safeScore(searchHit.getScore()));
    float lexicalScore = lexicalScore(queryText, document);
    float hybridScore =
        (vectorScore * VECTOR_SCORE_WEIGHT) + (lexicalScore * LEXICAL_SCORE_WEIGHT);
    return new ProductSearchResult(document, hybridScore);
  }

  private List<ProductSearchResult> retainRelevantVectorResults(
      List<ProductSearchResult> results) {
    float bestScore =
        results.stream()
            .map(ProductSearchResult::score)
            .filter(score -> score != null && Float.isFinite(score))
            .max(Float::compare)
            .orElse(0.0F);
    if (bestScore <= 0.0F) {
      return List.of();
    }

    float minimumRelevantScore = bestScore * MIN_RELATIVE_RELEVANCE_RATIO;
    return results.stream()
        .filter(
            result ->
                result.score() != null
                    && Float.isFinite(result.score())
                    && result.score() >= minimumRelevantScore)
        .toList();
  }

  private List<ProductSearchResult> retainExplicitColorMatchesIfAvailable(
      List<ProductSearchResult> results, String queryText) {
    Set<ColorFamily> requestedColors = ColorFamily.findIn(queryText);
    if (requestedColors.isEmpty()) {
      return results;
    }

    List<ProductSearchResult> colorMatches =
        results.stream()
            .filter(result -> matchesAnyColor(result.document(), requestedColors))
            .toList();
    return colorMatches.isEmpty() ? results : colorMatches;
  }

  private List<ProductSearchResult> retainExplicitProductTypeMatches(
      List<ProductSearchResult> results, String queryText) {
    Set<ProductType> requestedProductTypes = ProductType.findIn(queryText);
    if (requestedProductTypes.isEmpty()) {
      return results;
    }

    return results.stream()
        .filter(result -> matchesAnyProductType(result.document(), requestedProductTypes))
        .toList();
  }

  private boolean matchesAnyProductType(
      ProductDocument document, Set<ProductType> requestedProductTypes) {
    String imageProductType = normalizeText(document.imgDescription());
    int firstAttributeEnd = imageProductType.indexOf(',');
    if (firstAttributeEnd >= 0) {
      imageProductType = imageProductType.substring(0, firstAttributeEnd).trim();
    }
    String productTypeContext =
        String.join(
            " ",
            normalizeText(document.name()),
            imageProductType);
    Set<ProductType> primaryProductTypes = ProductType.findIn(productTypeContext);
    String fallbackProductTypeContext = normalizeText(document.description());
    Set<ProductType> documentProductTypes =
        primaryProductTypes.isEmpty()
            ? ProductType.findIn(fallbackProductTypeContext)
            : primaryProductTypes;
    return requestedProductTypes.stream()
        .anyMatch(
            requestedType ->
                documentProductTypes.contains(requestedType)
                    || (requestedType.generic
                        && (requestedType.matches(productTypeContext)
                            || requestedType.matches(fallbackProductTypeContext))));
  }

  private boolean matchesAnyColor(ProductDocument document, Set<ColorFamily> requestedColors) {
    String productColorContext =
        String.join(" ", normalizeText(document.name()), normalizeText(document.imgDescription()));
    return requestedColors.stream().anyMatch(color -> color.matches(productColorContext));
  }

  private List<Float> toFloatList(float[] embedding) {
    List<Float> vector = new ArrayList<>(embedding.length);
    for (float value : embedding) {
      vector.add(value);
    }
    return vector;
  }

  private float lexicalScore(String queryText, ProductDocument document) {
    Set<String> terms = searchTerms(queryText);
    if (terms.isEmpty()) {
      return 0.0F;
    }

    String productContext =
        String.join(
            " ",
            normalizeText(document.name()),
            normalizeText(document.description()),
            normalizeText(document.imgDescription()));
    long matchedTerms = terms.stream().filter(productContext::contains).count();
    return (float) matchedTerms / terms.size();
  }

  private boolean containsSearchExpression(String text, String expression) {
    return isHangulTerm(expression) ? containsSearchTerm(text, expression) : text.contains(expression);
  }

  private boolean containsSearchTerm(String text, String term) {
    if (!isHangulTerm(term)) {
      return text.contains(term);
    }

    int fromIndex = 0;
    while (fromIndex < text.length()) {
      int matchIndex = text.indexOf(term, fromIndex);
      if (matchIndex < 0) {
        return false;
      }

      int matchEnd = matchIndex + term.length();
      boolean startsAtWordBoundary =
          matchIndex == 0 || !Character.isLetterOrDigit(text.codePointBefore(matchIndex));
      boolean endsAtWordBoundary =
          matchEnd == text.length() || !Character.isLetterOrDigit(text.codePointAt(matchEnd));
      if (startsAtWordBoundary
          && (endsAtWordBoundary || hasStandaloneColorSuffix(text, matchEnd))) {
        return true;
      }
      fromIndex = matchEnd;
    }
    return false;
  }

  private boolean hasStandaloneColorSuffix(String text, int matchEnd) {
    int suffixEnd = matchEnd + 1;
    return suffixEnd <= text.length()
        && text.startsWith("색", matchEnd)
        && (suffixEnd == text.length()
            || !Character.isLetterOrDigit(text.codePointAt(suffixEnd)));
  }

  private boolean isHangulTerm(String value) {
    return !value.isBlank()
        && value.codePoints().allMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
  }

  private Set<String> searchTerms(String queryText) {
    Set<String> terms = new LinkedHashSet<>();
    String normalizedQuery = normalizeText(queryText);
    if (normalizedQuery.isBlank()) {
      return terms;
    }

    for (String token : normalizedQuery.split("\\s+")) {
      Set<String> colorAliases = ColorFamily.findExplicitAliasesIn(token);
      if (!colorAliases.isEmpty()) {
        terms.addAll(colorAliases);
        continue;
      }

      String normalizedToken = stripKoreanParticle(token);
      if (!SEARCH_STOP_WORDS.contains(normalizedToken)
          && isMeaningfulSearchTerm(normalizedToken)) {
        terms.add(normalizedToken);
      }
    }

    return terms;
  }

  private String stripKoreanParticle(String token) {
    for (String suffix : KOREAN_PARTICLE_SUFFIXES) {
      if (token.endsWith(suffix) && token.length() - suffix.length() >= 2) {
        return token.substring(0, token.length() - suffix.length());
      }
    }
    return token;
  }

  private boolean isMeaningfulSearchTerm(String token) {
    if (token.length() >= 2) {
      return true;
    }
    return token.codePoints().anyMatch(codePoint -> codePoint >= 0xAC00 && codePoint <= 0xD7A3);
  }

  private String normalizeText(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT).trim();
  }

  private List<ProductSearchResult> pageContent(
      List<ProductSearchResult> rankedResults, PageRequest pageable) {
    int fromIndex = Math.min((int) pageable.getOffset(), rankedResults.size());
    int toIndex = Math.min(fromIndex + pageable.getPageSize(), rankedResults.size());
    return rankedResults.subList(fromIndex, toIndex);
  }

  private float scoreOrZero(Float score) {
    return score == null || Float.isNaN(score) || Float.isInfinite(score) ? 0.0F : score;
  }

  private Float safeScore(float score) {
    if (Float.isNaN(score) || Float.isInfinite(score)) {
      return null;
    }
    return score;
  }

  private int normalizePage(Integer page) {
    if (page == null || page < 0) {
      return 0;
    }
    return page;
  }

  private int normalizeSize(Integer size) {
    if (size == null || size <= 0) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  private enum ColorFamily {
    BLACK("검정", "검은", "블랙", "흑색", "차콜", "다크그레이"),
    WHITE("흰색", "하얀", "화이트", "백색", "아이보리", "오프화이트"),
    RED("빨강", "빨간", "붉은", "레드", "다홍", "루비", "크림슨", "버건디"),
    BLUE("파랑", "파란", "블루", "청색", "남색", "네이비"),
    GREEN("초록", "녹색", "그린", "연두", "카키", "올리브"),
    YELLOW("노랑", "노란", "옐로", "금색", "골드"),
    BROWN("갈색", "브라운", "고동색", "카멜", "초콜릿"),
    GRAY("회색", "그레이", "실버", "은색"),
    PINK("분홍", "핑크", "로즈", "마젠타", "진분홍"),
    PURPLE("보라", "퍼플", "바이올렛", "자주"),
    ORANGE("주황", "오렌지", "테라코타"),
    BEIGE("베이지", "크림", "미색", "샌드");

    private final Set<String> aliases;

    ColorFamily(String... aliases) {
      this.aliases = Set.of(aliases);
    }

    private boolean matches(String text) {
      return aliases.stream().anyMatch(text::contains);
    }

    private boolean isExplicitlyMentionedIn(String text) {
      return aliases.stream().anyMatch(alias -> containsExplicitAlias(text, alias));
    }

    private static boolean containsExplicitAlias(String text, String alias) {
      int fromIndex = 0;
      while (fromIndex < text.length()) {
        int matchIndex = text.indexOf(alias, fromIndex);
        if (matchIndex < 0) {
          return false;
        }

        int matchEnd = matchIndex + alias.length();
        boolean startsAtWordBoundary =
            matchIndex == 0 || !Character.isLetterOrDigit(text.codePointBefore(matchIndex));
        boolean endsAtWordBoundary =
            matchEnd == text.length() || !Character.isLetterOrDigit(text.codePointAt(matchEnd));
        boolean endsWithColorSuffix =
            !alias.endsWith("색")
                && text.startsWith("색", matchEnd)
                && (matchEnd + 1 == text.length()
                    || !Character.isLetterOrDigit(text.codePointAt(matchEnd + 1)));
        if (startsAtWordBoundary && (endsAtWordBoundary || endsWithColorSuffix)) {
          return true;
        }
        fromIndex = matchEnd;
      }
      return false;
    }

    private static Set<ColorFamily> findIn(String text) {
      String normalizedText = text == null ? "" : text.toLowerCase(Locale.ROOT);
      Set<ColorFamily> colors = EnumSet.noneOf(ColorFamily.class);
      for (ColorFamily color : values()) {
        if (color.isExplicitlyMentionedIn(normalizedText)) {
          colors.add(color);
        }
      }
      return colors;
    }

    private static Set<String> findExplicitAliasesIn(String text) {
      String normalizedText = text == null ? "" : text.toLowerCase(Locale.ROOT);
      Set<String> aliases = new LinkedHashSet<>();
      for (ColorFamily color : values()) {
        color.aliases.stream()
            .filter(alias -> containsExplicitAlias(normalizedText, alias))
            .forEach(aliases::add);
      }
      return aliases;
    }
  }

  private enum ProductType {
    // Footwear
    DRESS_SHOES(
        "구두",
        "정장화",
        "드레스 슈즈",
        "드레스슈즈",
        "옥스퍼드",
        "옥스포드",
        "더비 슈즈",
        "더비슈즈",
        "더비화",
        "로퍼"),
    BOOTS(
        "부츠",
        "부츠화",
        "워커",
        "첼시부츠",
        "첼시 부츠",
        "레인부츠",
        "레인 부츠",
        "등산화",
        "트레킹화",
        "하이킹화",
        "방한화"),
    SNEAKERS(
        "스니커즈", "운동화", "러닝화", "러닝 슈즈", "러닝슈즈", "슬립온 운동화", "러너"),
    SANDALS("샌들", "슬리퍼", "플립플롭"),
    FOOTWEAR(
        true,
        new String[] {"신발", "슈즈"},
        "신발",
        "슈즈",
        "구두",
        "정장화",
        "로퍼",
        "스니커즈",
        "운동화",
        "부츠",
        "워커",
        "샌들",
        "슬리퍼"),

    // Bags and fashion accessories
    CROSSBODY_BAGS(
        "크로스백",
        "크로스 백",
        "크로스바디백",
        "크로스바디 백",
        "메신저백",
        "메신저 백",
        "슬링백",
        "슬링 백"),
    BACKPACKS("백팩", "배낭", "데이팩", "스포츠 백팩"),
    CARRIERS(
        "하드쉘 캐리어",
        "여행용 캐리어",
        "기내용 캐리어",
        "수하물 캐리어",
        "캐리어 세트",
        "캐리어"),
    TOTE_BAGS("토트백", "토트 백", "에코백", "쇼퍼백", "쇼퍼 백"),
    TRAVEL_BAGS(
        "여행용 가방",
        "여행 가방",
        "여행가방",
        "더플백",
        "더플 백",
        "보스턴백",
        "보스턴 백"),
    POUCHES(
        "코스메틱 파우치",
        "화장품 파우치",
        "노트북 파우치",
        "노트북 슬리브",
        "파우치"),
    DEVICE_BAGS(
        "노트북 가방",
        "디지털 기기 가방",
        "카메라 가방",
        "브리프케이스",
        "서류 가방",
        "서류가방"),
    HANDBAGS("핸드백", "숄더백", "숄더 백"),
    GOLF_BAGS("골프백", "골프 가방"),
    BAGS(
        true,
        new String[] {"가방"},
        "가방",
        "백팩",
        "배낭",
        "크로스백",
        "크로스 백",
        "토트백",
        "토트 백",
        "에코백",
        "캐리어",
        "파우치",
        "핸드백",
        "숄더백",
        "메신저백",
        "슬링백",
        "골프백",
        "브리프케이스"),
    BELTS("벨트", "허리띠"),
    WALLETS("지갑", "카드지갑", "카드 지갑"),
    SCARVES("스카프", "머플러", "넥워머"),
    JEWELRY("반지", "귀걸이", "목걸이", "팔찌", "주얼리"),
    HATS("모자", "캡", "비니", "바라클라바"),
    JACKETS("재킷", "자켓", "후디", "후드 집업", "아우터"),
    PANTS("바지", "팬츠", "스웨트팬츠", "하의"),
    SOCKS("양말", "삭스"),

    // Furniture and home fabric
    CHAIRS(
        "사무용 의자",
        "게이밍 의자",
        "식탁 의자",
        "라운지 체어",
        "오피스 체어",
        "디자인 체어",
        "암체어",
        "안락의자",
        "의자",
        "체어"),
    BENCHES("벤치"),
    STOOLS("바 스툴", "풋스툴", "스툴"),
    SOFAS("소파", "카우치", "데이베드"),
    OTTOMANS("오토만"),
    BEDS("침대 프레임", "수납형 침대", "침대", "헤드보드"),
    MATTRESSES("매트리스"),
    CUSHIONS("쿠션", "베개"),
    RUGS("인테리어 러그", "러그", "카페트", "카펫"),
    CURTAINS("커튼", "블라인드"),
    BEDDING("침구 세트", "베딩 세트", "침구", "베딩", "블랭킷", "이불"),
    STORAGE_FURNITURE(
        "수납 박스",
        "수납박스",
        "수납함",
        "수납장",
        "서랍장",
        "수납 바스켓",
        "수납 바구니",
        "수납 오거나이저",
        "철제 바구니"),
    SHELVES("수납 선반", "벽선반", "벽 선반", "선반", "책장"),
    DESKS_AND_DINING_TABLES(
        "사무용 책상",
        "컴퓨터 책상",
        "원목 책상",
        "작업대",
        "식탁",
        "책상"),
    TABLES(
        "사이드 테이블",
        "인테리어 테이블",
        "접이식 테이블",
        "원형 테이블",
        "목재 테이블",
        "나무 테이블",
        "탁자",
        "테이블"),
    CABINETS("거실장", "티비장", "tv 수납장", "콘솔장", "캐비닛"),
    CARTS("쇼핑 카트", "수납 카트", "이동식 카트", "트레이 카트", "수레"),
    MIRRORS("거울", "전신 거울", "벽거울"),
    WALL_ART(
        "월 아트",
        "벽 장식",
        "액자",
        "그림",
        "포스터",
        "캔버스 아트",
        "추상화",
        "설계도",
        "도면"),
    VASES_AND_PLANTERS("화병", "꽃병", "플랜터", "화분"),
    FANS("실링팬", "천장형 선풍기", "선풍기"),
    PARASOLS("파라솔", "차양막", "캐노피"),
    FABRICS("디자인 스와치", "직물 원단", "직조 원단", "섬유 원단", "원단"),
    TOWELS("타월 세트", "수건 세트", "운동용 타월", "타월", "수건"),
    HOME_MATS(
        false,
        new String[] {"인테리어 매트", "현관 매트", "바닥 매트"},
        "인테리어 매트",
        "현관 매트",
        "바닥 매트",
        "직사각형 매트"),
    LAUNDRY_BASKETS("세탁 바구니", "세탁물 수거함", "빨래 바구니"),
    DRYING_RACKS("건조대", "빨래 건조대"),
    CLOTHES_RACKS("옷걸이", "행거"),

    // Lighting
    LIGHTING(
        "펜던트 조명",
        "천장 조명",
        "벽부형 조명",
        "스트링 조명",
        "스탠드 조명",
        "테이블 램프",
        "데스크 램프",
        "투광등",
        "조명",
        "램프",
        "랜턴"),
    LIGHT_BULBS("led 전구", "백열전구", "전구"),

    // Kitchen and tableware
    TABLEWARE(
        "식기 세트",
        "테이블웨어 세트",
        "테이블웨어",
        "접시",
        "플레이트",
        "식기",
        "커트러리"),
    TEA_PRODUCTS("티 컬렉션", "티백", "허브티", "블랙티", "차 세트"),
    COOKWARE("조리도구 세트", "프라이팬", "냄비", "쿠킹 포트", "베이킹 팬", "그리들"),
    CUPS_AND_BOTTLES(
        "머그컵",
        "머그",
        "드링킹 컵",
        "물병",
        "보틀",
        "텀블러",
        "컵"),
    BOTTLE_RACKS("보틀 랙", "와인 랙", "병 수납대"),
    SERVING_TRAYS("서빙 트레이", "서빙 쟁반", "쟁반"),
    KITCHEN_BOWLS("세라믹 볼", "나무 볼", "키친 볼", "믹싱 볼"),
    KITCHEN_TOOLS(
        "캔 오프너",
        "쿠키 커터",
        "채칼",
        "주방 집게",
        "금속 집게",
        "거품기",
        "조리 도구",
        "조리도구"),
    COFFEE_MACHINES("커피 머신", "커피머신", "캡슐 커피 머신"),
    KITCHEN_APPLIANCES("인덕션", "푸드 프로세서", "주방 가전"),

    // Office and stationery
    NOTEBOOKS_AND_PAPER(
        "스프링 노트",
        "스케치북",
        "프린트 용지",
        "복사용지",
        "노트",
        "메모지",
        "카드",
        "봉투"),
    WRITING_TOOLS("필기구 세트", "잉크펜", "볼펜", "마킹 펜", "연필", "펜슬"),
    BINDERS_AND_FILES("파일 바인더", "수납 바인더", "파일 폴더", "클립보드", "바인더"),
    BOARDS("화이트보드", "메모 보드", "계획표", "칠판"),
    DESK_ORGANIZERS("데스크 오거나이저", "연필꽂이", "서류 수납대", "문서 수납함"),
    DESK_MATS("데스크 매트"),
    LABELS("디자인 라벨", "라벨지", "라벨"),

    // Electronics and appliances
    LAPTOPS("노트북 컴퓨터", "랩톱 컴퓨터", "노트북 pc", "랩톱", "노트북"),
    MONITORS("컴퓨터 모니터", "데스크탑 모니터", "모니터", "디스플레이"),
    AUDIO_DEVICES(
        "블루투스 스피커",
        "무선 이어버드",
        "와이어리스 이어버드",
        "유선 이어폰",
        "이어폰",
        "스피커"),
    POWER_ACCESSORIES("멀티탭", "보조배터리", "스마트 플러그", "전원 어댑터", "충전 어댑터"),
    CABLES("usb 케이블", "연결 케이블", "충전 케이블"),
    SCREEN_PROTECTORS("화면 보호 필름", "액정 보호 필름", "보호 필름"),
    KEYBOARDS("기계식 키보드", "무선 키보드", "키보드"),
    TRIPODS("삼각대", "카메라 삼각대"),
    PHONES("무선 전화기", "스마트폰", "휴대전화"),
    VACUUMS("진공청소기", "청소기"),
    REFRIGERATORS("냉장고"),

    // Pet products
    PET_BEDS("펫 베드", "반려동물 침대", "펫 하우스", "개집", "고양이 집"),
    PET_CARRIERS("반려견 이동장", "반려동물 이동장", "펫 캐리어", "동물용 케이지"),
    PET_FENCES("반려동물 울타리", "애견 운동장", "안전 펜스"),
    PET_SUPPLIES(
        "고양이 사료",
        "강아지 사료",
        "펫 라이프 액세서리",
        "반려동물 용품",
        "반려동물 미용 도구",
        "펫 미용 도구"),

    // Sports and outdoor products
    EXERCISE_MATS("요가 매트", "트레이닝 매트", "운동용 매트", "에어 매트리스"),
    WEIGHTS("덤벨", "바벨", "케틀벨", "웨이트"),
    RESISTANCE_BANDS("트레이닝 밴드", "저항 밴드", "운동 밴드"),
    SPORTS_BALLS("축구공", "농구공", "배구공", "스포츠 볼"),
    MASSAGE_TOOLS("마사지 롤러", "마사지 스틱", "폼롤러"),
    SPORTS_GEAR(
        "풋볼 용품",
        "야구 용품",
        "테니스 용품",
        "골프 액세서리",
        "트레이닝 액세서리",
        "요가 액세서리",
        "사이클링 액세서리"),
    CAMPING_GEAR("캠핑 액세서리", "아웃도어 쉘터", "텐트", "천막", "캠핑 용품"),
    BICYCLES("전기 자전거", "접이식 자전거", "자전거"),
    PRESSURE_WASHERS("고압 세척기", "압력 세척기"),
    PROTECTIVE_GEAR("보호 장비", "방탄 조끼", "구급 키트", "복싱 글러브"),

    // Collectibles and hobbies
    FIGURES("컬렉터블 피규어", "아트토이", "피규어"),
    TOYS_AND_GAMES(
        "빌딩 블록", "보드게임", "보드 게임", "퍼즐", "슬라임 토이", "컬렉터블 토이"),
    GAME_CONTROLLERS("게임 컨트롤러", "게임 패드"),
    MUSICAL_INSTRUMENTS("현악기", "악기 액세서리", "악기"),

    // Other concrete product types present in the index
    KEYRINGS("키링", "열쇠고리"),
    CANDLE_HOLDERS("캔들 홀더", "촛대"),
    FAUCETS("싱크대 수전", "주방 수전", "수전"),
    TISSUE_HOLDERS("휴지 걸이", "휴지걸이"),
    FACE_MASKS("면 마스크", "페이스 마스크"),
    DISPENSERS("손 세정제 디스펜서", "디스펜서"),
    ADHESIVES("글루", "접착제"),
    SUNSCREEN("선크림", "자외선 차단제");

    private final boolean generic;
    private final Set<String> queryAliases;
    private final Set<String> documentAliases;

    ProductType(String... aliases) {
      this(false, aliases, aliases);
    }

    ProductType(boolean generic, String[] queryAliases, String... documentAliases) {
      this.generic = generic;
      this.queryAliases = Set.of(queryAliases);
      this.documentAliases = Set.of(documentAliases);
    }

    private boolean matches(String text) {
      return documentAliases.stream().anyMatch(text::contains);
    }

    private static Set<ProductType> findIn(String text) {
      String normalizedText = text == null ? "" : text.toLowerCase(Locale.ROOT);
      Set<ProductType> bestMatches = EnumSet.noneOf(ProductType.class);
      int longestAliasLength = 0;
      for (ProductType productType : values()) {
        for (String alias : productType.queryAliases) {
          if (!normalizedText.contains(alias)) {
            continue;
          }
          if (alias.length() > longestAliasLength) {
            bestMatches.clear();
            longestAliasLength = alias.length();
          }
          if (alias.length() == longestAliasLength) {
            bestMatches.add(productType);
          }
        }
      }

      boolean hasSpecificMatch = bestMatches.stream().anyMatch(productType -> !productType.generic);
      if (hasSpecificMatch) {
        bestMatches.removeIf(productType -> productType.generic);
      }
      return bestMatches;
    }
  }

}
