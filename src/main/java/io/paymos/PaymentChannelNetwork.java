package io.paymos;

import java.util.List;
import java.util.Objects;

/**
 * One blockchain rail of a payment channel.
 *
 * <p>{@code network} is a rail code such as {@code TRC20}, not a chain nickname. {@code address} is
 * absent until that rail finishes provisioning, and never changes once it is present — a payer may
 * keep using it forever.
 */
public record PaymentChannelNetwork(
    String network, String status, String address, List<PaymentChannelToken> tokens) {
  public PaymentChannelNetwork {
    Objects.requireNonNull(network, "network is required");
    Objects.requireNonNull(status, "status is required");
    tokens = tokens == null ? List.of() : List.copyOf(tokens);
  }
}
