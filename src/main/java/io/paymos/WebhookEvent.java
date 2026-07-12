package io.paymos;

import java.util.Objects;

/** Verified webhook event envelope. */
public record WebhookEvent<T>(
    String eventId, String eventType, int version, long occurredAt, T data) {
  public WebhookEvent {
    Objects.requireNonNull(eventId, "eventId is required");
    Objects.requireNonNull(eventType, "eventType is required");
    Objects.requireNonNull(data, "data is required");
    if (eventId.isBlank() || eventType.isBlank() || version < 1 || occurredAt < 0)
      throw new IllegalArgumentException("Webhook event envelope is invalid");
  }
}
