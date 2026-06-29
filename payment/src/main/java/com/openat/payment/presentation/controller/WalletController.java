package com.openat.payment.presentation.controller;

import com.openat.common.auth.CurrentUser;
import com.openat.common.auth.UserContext;
import com.openat.payment.application.usecase.WalletUseCase;
import com.openat.payment.presentation.dto.WalletBalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet", description = "지갑 잔액 조회")
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

    private final WalletUseCase walletUseCase;

    public WalletController(WalletUseCase walletUseCase) {
        this.walletUseCase = walletUseCase;
    }

    @Operation(summary = "지갑 잔액 조회", description = "지갑이 없는 회원은 0을 반환한다.")
    @GetMapping
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @Parameter(description = "인증된 회원 정보(게이트웨이 주입)", required = true)
            @CurrentUser UserContext userContext) {
        UUID memberId = UUID.fromString(userContext.userId());
        long balance = walletUseCase.getBalance(memberId);
        return ResponseEntity.ok(new WalletBalanceResponse(balance));
    }
}
