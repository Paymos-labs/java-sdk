package io.paymos;

import com.fasterxml.jackson.annotation.JsonValue;

/** The server-calculated portion of an invoice payment to return. */
public enum RefundMode {
  FULL("full"),
  OVERPAYMENT("overpayment");

  private final String value;

  RefundMode(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }
}
