package io.paymos;

import java.util.Objects;

/** Parameters for creating an invoice. */
public record CreateInvoiceRequest(
    String projectId,
    String amount,
    String currency,
    String externalOrderId,
    String network,
    Boolean allowMultiplePayments,
    Integer customerFeePercent,
    String clientId) {

  /** Starts a fluent request builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent invoice request builder. */
  public static final class Builder {
    private String projectId;
    private String amount;
    private String currency;
    private String externalOrderId;
    private String network;
    private Boolean allowMultiplePayments;
    private Integer customerFeePercent;
    private String clientId;

    public Builder projectId(String value) {
      projectId = value;
      return this;
    }

    public Builder amount(String value) {
      amount = value;
      return this;
    }

    public Builder currency(String value) {
      currency = value;
      return this;
    }

    public Builder externalOrderId(String value) {
      externalOrderId = value;
      return this;
    }

    public Builder network(String value) {
      network = value;
      return this;
    }

    public Builder allowMultiplePayments(boolean value) {
      allowMultiplePayments = value;
      return this;
    }

    public Builder customerFeePercent(int value) {
      customerFeePercent = value;
      return this;
    }

    public Builder clientId(String value) {
      clientId = value;
      return this;
    }

    public CreateInvoiceRequest build() {
      require(projectId, "projectId");
      require(amount, "amount");
      require(currency, "currency");
      require(externalOrderId, "externalOrderId");
      if (customerFeePercent != null && (customerFeePercent < 0 || customerFeePercent > 100)) {
        throw new IllegalArgumentException("customerFeePercent must be between 0 and 100");
      }
      return new CreateInvoiceRequest(
          projectId,
          amount,
          currency,
          externalOrderId,
          network,
          allowMultiplePayments,
          customerFeePercent,
          clientId);
    }

    private static void require(String value, String name) {
      Objects.requireNonNull(value, name + " is required");
      if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
    }
  }
}
