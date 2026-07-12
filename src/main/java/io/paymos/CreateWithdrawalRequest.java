package io.paymos;

import java.util.Objects;

/** Parameters for creating a withdrawal. */
public record CreateWithdrawalRequest(
    String destinationAddress,
    String network,
    String currency,
    String amount,
    String externalOrderId) {
  public CreateWithdrawalRequest {
    require(destinationAddress, "destinationAddress");
    require(network, "network");
    require(currency, "currency");
    require(amount, "amount");
    require(externalOrderId, "externalOrderId");
  }

  private static void require(String value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
  }
}
