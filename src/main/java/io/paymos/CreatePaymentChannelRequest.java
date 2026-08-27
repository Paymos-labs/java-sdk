package io.paymos;

import java.util.Objects;

/**
 * Parameters for creating a payment channel.
 *
 * <p>The pair is the idempotency key: repeating the same {@code projectId} and {@code externalId}
 * returns the channel that already exists with a 200 instead of creating a second one, so it is
 * safe to call on every checkout.
 */
public record CreatePaymentChannelRequest(String projectId, String externalId) {
  public CreatePaymentChannelRequest {
    require(projectId, "projectId");
    require(externalId, "externalId");
    if (externalId.length() > 128)
      throw new IllegalArgumentException("externalId cannot exceed 128 characters");
  }

  private static void require(String value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
  }
}
