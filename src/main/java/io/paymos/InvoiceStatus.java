package io.paymos;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/** Forward-compatible invoice status value. */
public record InvoiceStatus(@JsonValue String value) {
  public static final InvoiceStatus AWAITING_CLIENT = new InvoiceStatus("awaiting_client");
  public static final InvoiceStatus AWAITING_PAYMENT = new InvoiceStatus("awaiting_payment");
  public static final InvoiceStatus CONFIRMING = new InvoiceStatus("confirming");
  public static final InvoiceStatus UNDERPAID_WAITING = new InvoiceStatus("underpaid_waiting");
  public static final InvoiceStatus PAID = new InvoiceStatus("paid");
  public static final InvoiceStatus PAID_OVER = new InvoiceStatus("paid_over");
  public static final InvoiceStatus UNDERPAID = new InvoiceStatus("underpaid");
  public static final InvoiceStatus EXPIRED = new InvoiceStatus("expired");
  public static final InvoiceStatus CANCELLED = new InvoiceStatus("cancelled");

  /** Creates a known or future status from its wire value. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public InvoiceStatus {
    Objects.requireNonNull(value, "Invoice status is required");
    if (value.isBlank()) throw new IllegalArgumentException("Invoice status cannot be blank");
  }

  @Override
  public String toString() {
    return value;
  }
}
