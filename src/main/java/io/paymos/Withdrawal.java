package io.paymos;

import java.util.Objects;

/** Withdrawal returned by create, list, get, and mutation operations. */
public record Withdrawal(
    String withdrawalId,
    String externalOrderId,
    WithdrawalStatus status,
    boolean isFinal,
    boolean isTest,
    String amount,
    String fee,
    String currency,
    String network,
    String destinationAddress,
    String txHash,
    String explorerUrl,
    long createdAt,
    Long completedAt,
    Long failedAt,
    Long cancelledAt) {
  public Withdrawal {
    Objects.requireNonNull(withdrawalId, "withdrawalId is required");
    Objects.requireNonNull(externalOrderId, "externalOrderId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(amount, "amount is required");
    Objects.requireNonNull(currency, "currency is required");
    Objects.requireNonNull(network, "network is required");
    Objects.requireNonNull(destinationAddress, "destinationAddress is required");
  }
}
