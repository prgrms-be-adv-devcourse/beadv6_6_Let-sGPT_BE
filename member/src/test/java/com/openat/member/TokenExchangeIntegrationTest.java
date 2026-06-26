package com.openat.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openat.member.application.dto.CreateSellerInfoRequest;
import com.openat.member.application.dto.LoginRequest;
import com.openat.member.application.dto.RefreshRequest;
import com.openat.member.application.dto.SignUpRequest;
import com.openat.member.application.dto.TokenExchangeRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 전체 토큰 교환 흐름 통합 테스트.
 *
 * <p>전제: Docker Desktop이 실행 중이어야 합니다 (TestContainers 사용).
 *
 * <p>흐름:
 * <ol>
 *   <li>회원가입</li>
 *   <li>로그인 → accessToken / refreshToken</li>
 *   <li>판매자 등록 (ROLE_USER) → seller1Id, 역할 ROLE_SELLER로 승격</li>
 *   <li>토큰 재발급 → 재발급된 accessToken에 ROLE_SELLER 포함 검증</li>
 *   <li>판매자 재등록 (ROLE_SELLER) → seller2Id</li>
 *   <li>판매자 목록 조회 → 2건 확인</li>
 *   <li>RFC 8693 STS 토큰 교환 → scopedToken</li>
 *   <li>scopedToken 클레임 검증 및 출력 (게이트웨이가 주입할 컨텍스트)</li>
 * </ol>
 *
 * <p>컨텍스트 출력 참고:
 * product {@code CurrentUserArgumentResolver}는 팀원 테스트 스텁으로 X-User-Id를 읽으며,
 * scoped 토큰의 셀러 컨텍스트를 {@code @CurrentUser}로 직접 읽는 코드가 현재 없습니다.
 * 이 테스트는 scopedToken의 클레임을 직접 디코딩해 컨텍스트를 출력합니다.
 * 게이트웨이는 {@code sub}(sellerInfoId)를 {@code X-Seller-Id}로, {@code act.sub}(memberId)를
 * 감사 로그에 기록합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class TokenExchangeIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16")
                    .withDatabaseName("openat")
                    .withUsername("test")
                    .withPassword("test");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final KeyPair TEST_KEY_PAIR;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            TEST_KEY_PAIR = gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * jwt.* 프로퍼티를 dotted 키로 직접 주입한다.
     * application.yml의 ${JWT_*} placeholder는 우선순위가 낮은 YAML PropertySource에 있으므로
     * 여기서 주입된 값이 우선 적용되고 placeholder 해석은 발생하지 않는다.
     * springdotenv가 로드하는 .env 파일의 JWT_* 값도 무관하다.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("jwt.key-id", () -> "test-key");
        registry.add("jwt.private-key",
                () -> Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("jwt.public-key",
                () -> Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPublic().getEncoded()));
        registry.add("jwt.issuer", () -> "http://test-issuer");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("회원가입 → 로그인 → 판매자 등록 → 토큰 재발급 → 판매자 재등록 → STS 토큰 교환 → 컨텍스트 출력")
    void fullTokenExchangeFlow() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) TEST_KEY_PAIR.getPublic();

        // ── 1. 회원가입 ──────────────────────────────────────────────────────────
        String signupBody = objectMapper.writeValueAsString(
                new SignUpRequest("buyer@example.com", "SecurePass1!", "testbuyer"));
        String signupResult = mockMvc.perform(post("/api/v1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String memberId = objectMapper.readTree(signupResult).get("id").asText();
        System.out.println("[1] 회원가입 완료  memberId=" + memberId);

        // ── 2. 로그인 ─────────────────────────────────────────────────────────────
        String loginBody = objectMapper.writeValueAsString(
                new LoginRequest("buyer@example.com", "SecurePass1!"));
        String loginResult = mockMvc.perform(post("/api/v1/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode loginJson = objectMapper.readTree(loginResult);
        String initialAccessToken = loginJson.get("accessToken").asText();
        String refreshToken = loginJson.get("refreshToken").asText();
        System.out.println("[2] 로그인 완료");

        // ── 3. 첫 번째 판매자 등록 (ROLE_USER 상태) ──────────────────────────────
        // 게이트웨이가 주입하는 헤더를 테스트에서 직접 설정.
        String seller1Body = objectMapper.writeValueAsString(
                new CreateSellerInfoRequest("123-45-67890", "첫번째 가게"));
        String seller1Result = mockMvc.perform(post("/api/v1/seller/me")
                        .header("X-User-Id", memberId)
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seller1Body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String seller1Id = objectMapper.readTree(seller1Result).get("id").asText();
        System.out.println("[3] 판매자1 등록 완료  seller1Id=" + seller1Id + "  → DB 역할이 ROLE_SELLER로 승격됨");

        // ── 4. 토큰 재발급 (DB의 역할 승격을 accessToken에 반영) ─────────────────
        String refreshBody = objectMapper.writeValueAsString(new RefreshRequest(refreshToken));
        String refreshResult = mockMvc.perform(post("/api/v1/members/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode refreshJson = objectMapper.readTree(refreshResult);
        String sellerAccessToken = refreshJson.get("accessToken").asText();

        // 재발급 토큰에 ROLE_SELLER가 반영됐는지 직접 디코딩해 검증
        Claims refreshedClaims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(sellerAccessToken)
                .getPayload();
        @SuppressWarnings("unchecked")
        List<String> refreshedRoles = (List<String>) refreshedClaims.get("roles");
        assertThat(refreshedRoles).contains("SELLER");
        System.out.println("[4] 토큰 재발급 완료  roles=" + refreshedRoles + "  (ROLE_SELLER 포함 확인)");

        // ── 5. 두 번째 판매자 등록 (ROLE_SELLER 상태) ────────────────────────────
        String seller2Body = objectMapper.writeValueAsString(
                new CreateSellerInfoRequest("987-65-43210", "두번째 가게"));
        String seller2Result = mockMvc.perform(post("/api/v1/seller/me")
                        .header("X-User-Id", memberId)
                        .header("X-User-Roles", "ROLE_SELLER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seller2Body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String seller2Id = objectMapper.readTree(seller2Result).get("id").asText();
        System.out.println("[5] 판매자2 등록 완료  seller2Id=" + seller2Id);

        // ── 6. 판매자 목록 조회 (2건 확인) ─────────────────────────────────────
        String listResult = mockMvc.perform(get("/api/v1/seller/me")
                        .header("X-User-Id", memberId)
                        .header("X-User-Roles", "ROLE_SELLER"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode sellers = objectMapper.readTree(listResult);
        assertThat(sellers.size()).isEqualTo(2);
        // DB 반환 순서 비결정적이므로 인덱스 0을 STS 교환에 사용하고 자기일관 검증
        String firstSellerId = sellers.get(0).get("id").asText();
        System.out.println("[6] 판매자 목록 조회 완료  count=2  firstSellerId=" + firstSellerId);

        // ── 7. RFC 8693 STS 토큰 교환 ────────────────────────────────────────────
        // subject_token: 판매자 상태의 access 토큰
        // resource: 교환 대상 sellerInfo URI (테넌트 선택)
        String stsResult = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", TokenExchangeRequest.GRANT_TYPE_TOKEN_EXCHANGE)
                        .param("subject_token", sellerAccessToken)
                        .param("subject_token_type", TokenExchangeRequest.TOKEN_TYPE_JWT)
                        .param("requested_token_type", TokenExchangeRequest.TOKEN_TYPE_JWT)
                        .param("audience", TokenExchangeRequest.AUDIENCE_PRODUCT)
                        .param("scope", TokenExchangeRequest.SCOPE_PRODUCT_WRITE)
                        .param("resource", TokenExchangeRequest.RESOURCE_SELLER_PREFIX + firstSellerId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String scopedToken = objectMapper.readTree(stsResult).get("access_token").asText();
        System.out.println("[7] STS 토큰 교환 완료");

        // ── 8. scoped 토큰 클레임 검증 및 컨텍스트 출력 ─────────────────────────
        Claims scopedClaims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(scopedToken)
                .getPayload();

        // delegation 모델 검증
        assertThat(scopedClaims.get("typ", String.class)).isEqualTo("scoped");
        assertThat(scopedClaims.getSubject()).isEqualTo(firstSellerId);          // 테넌트(대상)
        assertThat(scopedClaims.getAudience()).containsExactly("openat-product");
        assertThat(scopedClaims.get("scope", String.class)).isEqualTo("product:write");

        @SuppressWarnings("unchecked")
        Map<String, Object> act = (Map<String, Object>) scopedClaims.get("act");
        assertThat(act.get("sub").toString()).isEqualTo(memberId);               // 행위자

        System.out.println();
        System.out.println("[8] ══ scoped 토큰 컨텍스트 (RFC 8693 delegation) ═══════════════════════════");
        System.out.println("  typ        : " + scopedClaims.get("typ"));
        System.out.println("  sub        : " + scopedClaims.getSubject()
                + "  ← sellerInfoId  (게이트웨이 → X-Seller-Id 헤더로 주입)");
        System.out.println("  act.sub    : " + act.get("sub")
                + "  ← memberId  (게이트웨이 위임 감사 로그에 기록)");
        System.out.println("  aud        : " + scopedClaims.getAudience());
        System.out.println("  scope      : " + scopedClaims.get("scope"));
        System.out.println("  iss        : " + scopedClaims.getIssuer());
        System.out.println("  exp        : " + scopedClaims.getExpiration());
        System.out.println("  jti        : " + scopedClaims.getId());
        System.out.println("  ─ product 서비스가 받을 헤더 ─────────────────────────────────────────────");
        System.out.println("  X-Seller-Id: " + scopedClaims.getSubject()
                + "  (게이트웨이가 sub 추출 후 주입)");
        System.out.println("  X-User-Id  : (없음 — scoped 토큰 경로에서 strip됨)");
        System.out.println("════════════════════════════════════════════════════════════════════════════");
    }
}
