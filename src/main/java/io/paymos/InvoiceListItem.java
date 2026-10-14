package io.paymos;

import java.util.Objects;

/**
 * Compact invoice returned by the list endpoint.
 *
 * <p>{@code paidLateAt}: invoice-late-payment additive late mark, Unix seconds; null while there is
 * no mark — the server omits the field then.
 */
public record InvoiceListItem(
    String invoiceId,
    String projectId,
    String externalOrderId,
    String clientId,
    InvoiceStatus status,
    boolean isFinal,
    boolean isTest,
    String amount,
    String currency,
    String network,
    long createdAt,
    Long expiresAt,
    Long completedAt,
    Long paidLateAt) {
  public InvoiceListItem {
    Objects.requireNonNull(invoiceId, "invoiceId is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(externalOrderId, "externalOrderId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(amount, "amount is required");
    Objects.requireNonNull(currency, "currency is required");
  }
}
