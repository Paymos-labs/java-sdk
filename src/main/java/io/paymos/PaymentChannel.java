package io.paymos;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A payer's own set of deposit addresses, returned by create, get, list, block and unblock.
 *
 * <p>The identifier is prefixed {@code pc_}. Creating the same {@code projectId} plus {@code
 * externalId} again returns this same channel, so a checkout can call create on every visit.
 */
public record PaymentChannel(
    String id,
    String projectId,
    String externalId,
    String status,
    boolean isAcceptingPayments,
    boolean isFullyProvisioned,
    boolean isTest,
    BigDecimal appliedFeePercent,
    int customerFeePercent,
    List<PaymentChannelNetwork> networks,
    long createdAt,
    long updatedAt) {
  public PaymentChannel {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(externalId, "externalId is required");
    Objects.requireNonNull(status, "status is required");
    networks = networks == null ? List.of() : List.copyOf(networks);
  }
}
