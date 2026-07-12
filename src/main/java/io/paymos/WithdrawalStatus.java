package io.paymos;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/** Forward-compatible withdrawal status value. */
public record WithdrawalStatus(@JsonValue String value) {
  public static final WithdrawalStatus CREATED = new WithdrawalStatus("created");
  public static final WithdrawalStatus PENDING_REVIEW = new WithdrawalStatus("pending_review");
  public static final WithdrawalStatus SIGNED = new WithdrawalStatus("signed");
  public static final WithdrawalStatus CANCELLING = new WithdrawalStatus("cancelling");
  public static final WithdrawalStatus COMPLETED = new WithdrawalStatus("completed");
  public static final WithdrawalStatus FAILED = new WithdrawalStatus("failed");
  public static final WithdrawalStatus CANCELLED = new WithdrawalStatus("cancelled");

  /** Creates a known or future status from its wire value. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public WithdrawalStatus {
    Objects.requireNonNull(value, "Withdrawal status is required");
    if (value.isBlank()) throw new IllegalArgumentException("Withdrawal status cannot be blank");
  }

  @Override
  public String toString() {
    return value;
  }
}
