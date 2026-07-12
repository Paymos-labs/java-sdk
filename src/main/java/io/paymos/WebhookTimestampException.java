package io.paymos;

/** Paymos webhook timestamp outside the configured replay tolerance. */
public final class WebhookTimestampException extends PaymosException {
  private static final long serialVersionUID = 1L;

  public WebhookTimestampException(String message) {
    super(message);
  }
}
