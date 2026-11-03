package io.paymos;

import java.util.Objects;

/** Read-only refund with server-calculated decimal strings and its withdrawal ID. */
public record Refund(
    String type,
    String projectId,
    String withdrawalId,
    String invoiceId,
    RefundMode mode,
    String externalOrderId,
    WithdrawalStatus status,
    boolean isFinal,
    boolean isTest,
    String amount,
    String fee,
    String currency,
    String network,
    String destinationAddress,
    String firstSender,
    String txHash,
    String explorerUrl,
    long createdAt,
    Long completedAt,
    Long failedAt,
    Long cancelledAt) {
  public Refund {
    Objects.requireNonNull(withdrawalId, "withdrawalId is required");
    Objects.requireNonNull(invoiceId, "invoiceId is required");
    Objects.requireNonNull(mode, "mode is required");
    Objects.requireNonNull(externalOrderId, "externalOrderId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(amount, "amount is required");
    Objects.requireNonNull(currency, "currency is required");
    Objects.requireNonNull(network, "network is required");
    Objects.requireNonNull(destinationAddress, "destinationAddress is required");
    Objects.requireNonNull(firstSender, "firstSender is required");
  }
}
