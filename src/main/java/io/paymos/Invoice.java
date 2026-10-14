package io.paymos;

import java.util.List;
import java.util.Objects;

/**
 * Full invoice returned by create, get, and mutation operations.
 *
 * <p>{@code paidLateAt} is the invoice-late-payment fact: money crossed the invoice's total after
 * it had already closed as expired/underpaid. Unix seconds; null while the invoice has no late mark
 * — the server omits the field then and Jackson leaves the null. {@code status} keeps its terminal
 * meaning; the marker travels beside it.
 */
public record Invoice(
    String invoiceId,
    String projectId,
    InvoiceStatus status,
    boolean isFinal,
    boolean isTest,
    String paymentUrl,
    Order order,
    Payment payment,
    long createdAt,
    long updatedAt,
    Long expiresAt,
    Long completedAt,
    Long paidLateAt) {

  public Invoice {
    Objects.requireNonNull(invoiceId, "invoiceId is required");
    Objects.requireNonNull(projectId, "projectId is required");
    Objects.requireNonNull(status, "status is required");
    Objects.requireNonNull(paymentUrl, "paymentUrl is required");
    Objects.requireNonNull(order, "order is required");
  }

  /** Merchant order represented by an invoice. */
  public record Order(
      String externalId, String clientId, String amount, String currency, String network) {
    public Order {
      Objects.requireNonNull(externalId, "externalId is required");
      Objects.requireNonNull(amount, "amount is required");
      Objects.requireNonNull(currency, "currency is required");
    }
  }

  /** Selected payment route and its on-chain transfers. */
  public record Payment(
      String currency,
      String network,
      long chainId,
      String contractAddress,
      String expected,
      String address,
      String exchangeRate,
      String paid,
      String remaining,
      String fee,
      String net,
      List<Transfer> transfers) {
    public Payment {
      Objects.requireNonNull(currency, "currency is required");
      Objects.requireNonNull(network, "network is required");
      Objects.requireNonNull(expected, "expected is required");
      transfers = transfers == null ? null : List.copyOf(transfers);
    }
  }

  /** One incoming on-chain transfer attached to the invoice. */
  public record Transfer(
      String txHash,
      String amount,
      String status,
      long createdAt,
      Long confirmedAt,
      Integer requiredConfirmations,
      Long estimatedConfirmationAt,
      String explorerUrl) {
    public Transfer {
      Objects.requireNonNull(txHash, "txHash is required");
      Objects.requireNonNull(amount, "amount is required");
      Objects.requireNonNull(status, "status is required");
    }
  }
}
