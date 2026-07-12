package io.paymos;

/** Base exception for Paymos client, protocol, and response failures. */
public class PaymosException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates an exception with a human-readable message. */
  public PaymosException(String message) {
    super(message);
  }

  /** Creates an exception with its underlying cause. */
  public PaymosException(String message, Throwable cause) {
    super(message, cause);
  }
}
