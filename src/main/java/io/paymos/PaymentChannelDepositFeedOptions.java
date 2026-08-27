package io.paymos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional filters for reading the confirmed-deposit feed.
 *
 * <p>These five are the complete set the API accepts; it rejects any other query field outright
 * instead of ignoring it.
 *
 * <p>{@code confirmedFrom} is an initial-sync lower bound in Unix seconds, answering "start me from
 * this point in time" on the very first poll. It is NOT how a poller resumes: afterwards, carry the
 * cursor the feed handed back, through {@link #withCursor(String)}.
 */
public record PaymentChannelDepositFeedOptions(
    Integer limit, String cursor, String projectId, String paymentChannelId, Long confirmedFrom) {

  public static Builder builder() {
    return new Builder();
  }

  static PaymentChannelDepositFeedOptions empty() {
    return builder().build();
  }

  /** Returns the same filters positioned at the cursor the previous page handed back. */
  public PaymentChannelDepositFeedOptions withCursor(String value) {
    return new PaymentChannelDepositFeedOptions(
        limit, value, projectId, paymentChannelId, confirmedFrom);
  }

  Map<String, ?> query() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("limit", limit);
    values.put("cursor", cursor);
    values.put("project_id", projectId);
    values.put("payment_channel_id", paymentChannelId);
    values.put("confirmed_from", confirmedFrom);
    return values;
  }

  /** Builds validated payment-channel deposit feed filters. */
  public static final class Builder {
    private Integer limit;
    private String cursor;
    private String projectId;
    private String paymentChannelId;
    private Long confirmedFrom;

    public Builder limit(int value) {
      limit = value;
      return this;
    }

    public Builder cursor(String value) {
      cursor = value;
      return this;
    }

    public Builder projectId(String value) {
      projectId = value;
      return this;
    }

    public Builder paymentChannelId(String value) {
      paymentChannelId = value;
      return this;
    }

    public Builder confirmedFrom(long value) {
      confirmedFrom = value;
      return this;
    }

    public PaymentChannelDepositFeedOptions build() {
      if (limit != null && (limit < 1 || limit > 100))
        throw new IllegalArgumentException("limit must be between 1 and 100");
      if (cursor != null && cursor.isBlank())
        throw new IllegalArgumentException("cursor cannot be blank");
      if (projectId != null && projectId.isBlank())
        throw new IllegalArgumentException("projectId cannot be blank");
      if (paymentChannelId != null && paymentChannelId.isBlank())
        throw new IllegalArgumentException("paymentChannelId cannot be blank");
      if (confirmedFrom != null && confirmedFrom < 0)
        throw new IllegalArgumentException("timestamps cannot be negative");
      return new PaymentChannelDepositFeedOptions(
          limit, cursor, projectId, paymentChannelId, confirmedFrom);
    }
  }
}
