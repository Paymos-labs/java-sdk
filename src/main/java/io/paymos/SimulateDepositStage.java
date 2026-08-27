package io.paymos;

import com.fasterxml.jackson.annotation.JsonValue;

/** The three lifecycle stages a sandbox deposit simulation can be driven to. */
public enum SimulateDepositStage {
  /** The deposit is seen on chain but not yet settled. */
  CONFIRMING("confirming"),

  /** The deposit was dropped by a chain reorganisation. */
  REORGED("reorged"),

  /** The deposit is settled. This is the default when no stage is sent. */
  CONFIRMED("confirmed");

  private final String value;

  SimulateDepositStage(String value) {
    this.value = value;
  }

  /** The wire value sent to the API. */
  @JsonValue
  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }
}
