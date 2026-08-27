package io.paymos;

import java.util.Objects;

/**
 * One token accepted on a payment-channel rail.
 *
 * <p>An absent {@code minimumDeposit} means the rail cannot quote a minimum right now — never that
 * there is no minimum. Treat it as unknown and retry later rather than accepting any amount.
 */
public record PaymentChannelToken(String symbol, String minimumDeposit) {
  public PaymentChannelToken {
    Objects.requireNonNull(symbol, "symbol is required");
  }
}
