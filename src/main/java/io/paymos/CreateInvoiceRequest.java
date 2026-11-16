package io.paymos;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** Parameters for creating an invoice. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateInvoiceRequest(
    String projectId,
    String amount,
    String currency,
    String externalOrderId,
    String network,
    Integer customerFeePercent,
    String clientId,
    List<Invoice.InvoiceProduct> products) {

  public CreateInvoiceRequest {
    products = products == null ? null : List.copyOf(products);
  }

  /** Legacy constructor without products for backwards compatibility. */
  public CreateInvoiceRequest(
      String projectId,
      String amount,
      String currency,
      String externalOrderId,
      String network,
      Integer customerFeePercent,
      String clientId) {
    this(projectId, amount, currency, externalOrderId, network, customerFeePercent, clientId, null);
  }

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
    private Integer customerFeePercent;
    private String clientId;
    private List<Invoice.InvoiceProduct> products;

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

    public Builder customerFeePercent(int value) {
      customerFeePercent = value;
      return this;
    }

    public Builder clientId(String value) {
      clientId = value;
      return this;
    }

    public Builder products(List<Invoice.InvoiceProduct> value) {
      products = value;
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
          customerFeePercent,
          clientId,
          products);
    }

    private static void require(String value, String name) {
      Objects.requireNonNull(value, name + " is required");
      if (value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
    }
  }
}
