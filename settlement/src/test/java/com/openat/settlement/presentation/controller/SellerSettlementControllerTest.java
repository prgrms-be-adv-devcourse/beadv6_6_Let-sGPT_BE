package com.openat.settlement.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openat.common.auth.UserHeaders;
import com.openat.common.exception.GlobalExceptionHandler;
import com.openat.settlement.application.dto.FindSellerSettlementsQuery;
import com.openat.settlement.application.dto.FindSettlementOrdersQuery;
import com.openat.settlement.application.usecase.FailedSellerSettlementRetryUseCase;
import com.openat.settlement.application.usecase.SettlementQueryUseCase;
import com.openat.settlement.config.WebConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SellerSettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebConfig.class, GlobalExceptionHandler.class})
@DisplayName("판매자 정산 조회 컨트롤러")
class SellerSettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FailedSellerSettlementRetryUseCase failedSellerSettlementRetryUseCase;

    @MockitoBean
    private SettlementQueryUseCase settlementQueryUseCase;

    @Test
    @DisplayName("정산 주문 조회는 쿼리 파라미터가 아니라 판매자 헤더로 범위를 제한한다")
    void findSettlementOrders_usesSellerHeaderInsteadOfQueryParameter() throws Exception {
        UUID authenticatedSellerId = UUID.randomUUID();
        UUID requestedSellerId = UUID.randomUUID();
        given(settlementQueryUseCase.findSettlementOrders(any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/settlements/seller/orders")
                        .header(UserHeaders.SELLER_ID, authenticatedSellerId)
                        .param("sellerId", requestedSellerId.toString()))
                .andExpect(status().isOk());

        ArgumentCaptor<FindSettlementOrdersQuery> queryCaptor =
                ArgumentCaptor.forClass(FindSettlementOrdersQuery.class);
        then(settlementQueryUseCase).should().findSettlementOrders(queryCaptor.capture(), any());
        assertThat(queryCaptor.getValue().sellerId()).isEqualTo(authenticatedSellerId);
    }

    @Test
    @DisplayName("판매자별 정산 조회는 쿼리 파라미터가 아니라 판매자 헤더로 범위를 제한한다")
    void findSellerSettlements_usesSellerHeaderInsteadOfQueryParameter() throws Exception {
        UUID authenticatedSellerId = UUID.randomUUID();
        UUID requestedSellerId = UUID.randomUUID();
        given(settlementQueryUseCase.findSellerSettlements(any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/settlements/seller/sellers")
                        .header(UserHeaders.SELLER_ID, authenticatedSellerId)
                        .param("sellerId", requestedSellerId.toString()))
                .andExpect(status().isOk());

        ArgumentCaptor<FindSellerSettlementsQuery> queryCaptor =
                ArgumentCaptor.forClass(FindSellerSettlementsQuery.class);
        then(settlementQueryUseCase).should().findSellerSettlements(queryCaptor.capture(), any());
        assertThat(queryCaptor.getValue().sellerId()).isEqualTo(authenticatedSellerId);
    }

    @Test
    @DisplayName("판매자 헤더가 없으면 401로 거부한다")
    void findSettlementOrders_missingSellerHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/settlements/seller/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        then(settlementQueryUseCase).should(never()).findSettlementOrders(any(), any());
    }
}
