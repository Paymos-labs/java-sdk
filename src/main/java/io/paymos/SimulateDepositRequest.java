package io.paymos;

import java.util.Objects;

/**
 * Parameters for simulating a sandbox payment-channel deposit.
 *
 * <p>{@code network} is required — it is the rail the simulated deposit lands on. {@code stage} is
 * optional and omitted from the request entirely when null, which the API reads as confirmed.
 */
public record SimulateDepositRequest(
    String amount, String currency, String network, SimulateDepositStage stage) {
  public SimulateDepositRequest {
    require(amount, "amount");
    require(currency, "currency");
    require(network, "network");
  }

  /** Simulates a confirmed deposit, the stage an integration wants by default. */
  public SimulateDepositRequest(String amount, String currency, String network) {
    this(amount, currency, network, null);
  }

  private static void require(String value, String name) {
    Objects.requireNonNull(value, name + " is required");
    if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
  }
}
