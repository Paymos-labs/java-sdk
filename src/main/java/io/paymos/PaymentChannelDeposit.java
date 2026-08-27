package io.paymos;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One deposit paid into a payment channel, identified by a {@code pcd_} prefix.
 *
 * <p>Amounts are decimal strings, timestamps are Unix seconds. This is also the exact payload of
 * every {@code payment_channel.deposit.*} webhook event — decode one with {@code
 * verifier.constructEvent(header, body, now, PaymentChannelDeposit.class)}.
 *
 * <p><b>Ordering.</b> {@code confirming} and {@code reorged} are ADVISORY and may arrive out of
 * order: a {@code confirming} can land after the {@code confirmed} for the same deposit, and a
 * {@code reorged} can be superseded by a later {@code confirmed}. Credit a merchant only on {@code
 * payment_channel.deposit.confirmed} with {@code isFinal} true, and never let an advisory event
 * regress a deposit already known to be confirmed — crediting on {@code confirming} releases goods
 * against money a reorg can still take back.
 */
public record PaymentChannelDeposit(
    String id,
    String paymentChannelId,
    String projectId,
    String paymentChannelExternalId,
    String status,
    boolean isFinal,
    boolean isTest,
    String currency,
    String network,
    long chainId,
    String contractAddress,
    String gross,
    String fee,
    String net,
    BigDecimal appliedFeePercent,
    int customerFeePercent,
    String txHash,
    String transferId,
    String sourceAddress,
    String destinationAddress,
    long blockHeight,
    Long firstIncludedBlockTimestamp,
    String explorerUrl,
    long createdAt,
    long updatedAt,
    Long confirmedAt) {
  public PaymentChannelDeposit {
    Objects.requireNonNull(id, "id is required");
    Objects.requireNonNull(paymentChannelId, "paymentChannelId is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(paymentChannelExternalId, "paymentChannelExternalId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(currency, "currency is required");
    Objects.requireNonNull(network, "network is required");
    Objects.requireNonNull(gross, "gross is required");
    Objects.requireNonNull(fee, "fee is required");
    Objects.requireNonNull(net, "net is required");
    Objects.requireNonNull(txHash, "txHash is required");
    Objects.requireNonNull(transferId, "transferId is required");
    Objects.requireNonNull(destinationAddress, "destinationAddress is required");
  }
}
