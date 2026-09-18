package com.sai.wise.service;

import com.sai.wise.model.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Why money is BigDecimal, demonstrated rather than asserted in a comment.
 */
class MoneyTest {

    @Test
    @DisplayName("Floating point cannot represent money — this is the bug we are avoiding")
    void floatingPointIsWrong() {
        double naive = 0.1 + 0.2;
        assertThat(naive).isNotEqualTo(0.3);          // 0.30000000000000004
        assertThat(new BigDecimal("0.1").add(new BigDecimal("0.2")))
                .isEqualByComparingTo("0.3");          // correct
    }

    @Test
    @DisplayName("Scale follows the currency: 2dp for USD, 0dp for JPY, 3dp for KWD")
    void scalePerCurrency() {
        assertThat(Money.of("1000.005", "USD").amount()).isEqualByComparingTo("1000.01");
        assertThat(Money.of("1000.4", "JPY").amount()).isEqualByComparingTo("1000");
        assertThat(Money.of("1.2345", "KWD").amount()).isEqualByComparingTo("1.235");
    }

    @Test
    @DisplayName("Rounding is HALF_UP — how a human expects currency to round")
    void roundsHalfUp() {
        assertThat(Money.of("10.555", "USD").amount()).isEqualByComparingTo("10.56");
        assertThat(Money.of("10.554", "USD").amount()).isEqualByComparingTo("10.55");
    }
}
