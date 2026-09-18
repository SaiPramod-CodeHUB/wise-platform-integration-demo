package com.sai.wise.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * What a partner asks us for: move this much, from this currency to that one,
 * to this recipient.
 *
 * <p>clientReference is the partner's own id for the payment. We use it to make
 * the whole orchestration idempotent from THEIR side too — if their system
 * retries the request, they get the same transfer back, not a second one.
 */
public record TransferRequest(
        @NotBlank String clientReference,
        @NotBlank String sourceCurrency,
        @NotBlank String targetCurrency,
        @NotNull @Positive BigDecimal sourceAmount,
        @NotNull Long targetAccountId,
        String reference
) {}
