package io.paymos;

import java.util.Objects;

/**
 * The one feed position that could not be rendered, and why.
 *
 * <p>Without it a stuck feed looks exactly like a quiet day: both are an empty page carrying a
 * cursor. Surface {@code depositId} and {@code reason} to whoever watches the integration. The
 * reason codes are {@code booked_auth_missing}, {@code inconsistent}, {@code deposit_missing},
 * {@code channel_missing}, {@code transfer_missing} and {@code not_confirmed}.
 */
public record PaymentChannelDepositFeedBlockage(String depositId, String reason) {
  public PaymentChannelDepositFeedBlockage {
    Objects.requireNonNull(depositId, "depositId is required");
    Objects.requireNonNull(reason, "reason is required");
  }
}
