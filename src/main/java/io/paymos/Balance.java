package io.paymos;

import java.util.Objects;

/** Available merchant balance for one currency. */
public record Balance(String currency, String available) {
  public Balance {
    Objects.requireNonNull(currency, "currency is required");
    Objects.requireNonNull(available, "available is required");
  }
}
