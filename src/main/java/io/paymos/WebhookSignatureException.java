package io.paymos;

/** Invalid or malformed Paymos webhook signature. */
public final class WebhookSignatureException extends PaymosException {
  private static final long serialVersionUID = 1L;

  public WebhookSignatureException(String message) {
    super(message);
  }
}
