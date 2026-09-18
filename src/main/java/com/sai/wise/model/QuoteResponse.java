package com.sai.wise.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A Wise quote: a held FX rate with a fee and an expiry.
 *
 * <p>The expiry is the part partners get wrong. Wise is carrying the currency
 * risk while the quote is open, so it cannot stay open forever. Cache a quote,
 * or sit on one while a user fills in a form, and funding will fail.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteResponse(
        String id,
        String sourceCurrency,
        String targetCurrency,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        BigDecimal rate,
        Instant expirationTime,
        String status
) {
    public boolean isExpired() {
        return expirationTime != null && Instant.now().isAfter(expirationTime);
    }

    /** True if the quote has less than the configured buffer left on it. */
    public boolean expiresWithin(int seconds) {
        if (expirationTime == null) return false;
        return Instant.now().plusSeconds(seconds).isAfter(expirationTime);
    }

    public Money source() { return new Money(sourceAmount, sourceCurrency); }
    public Money target() { return new Money(targetAmount, targetCurrency); }
}
