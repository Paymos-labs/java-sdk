package io.paymos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional filters for listing payment channels.
 *
 * <p>These five are the complete set the API accepts; it rejects any other query field outright
 * instead of ignoring it. {@code externalId} is how one payer's channel is found again.
 */
public record PaymentChannelListOptions(
    Integer limit, String cursor, List<String> status, String externalId, String projectId) {

  public PaymentChannelListOptions {
    status = status == null ? null : List.copyOf(status);
  }

  public static Builder builder() {
    return new Builder();
  }

  static PaymentChannelListOptions empty() {
    return builder().build();
  }

  PaymentChannelListOptions withCursor(String value) {
    return new PaymentChannelListOptions(limit, value, status, externalId, projectId);
  }

  Map<String, ?> query() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("limit", limit);
    values.put("cursor", cursor);
    values.put("status", status);
    values.put("external_id", externalId);
    values.put("project_id", projectId);
    return values;
  }

  /** Builds validated payment-channel list filters. */
  public static final class Builder {
    private Integer limit;
    private String cursor;
    private List<String> status;
    private String externalId;
    private String projectId;

    public Builder limit(int value) {
      limit = value;
      return this;
    }

    public Builder cursor(String value) {
      cursor = value;
      return this;
    }

    public Builder status(List<String> value) {
      status = value;
      return this;
    }

    public Builder externalId(String value) {
      externalId = value;
      return this;
    }

    public Builder projectId(String value) {
      projectId = value;
      return this;
    }

    public PaymentChannelListOptions build() {
      if (limit != null && (limit < 1 || limit > 100))
        throw new IllegalArgumentException("limit must be between 1 and 100");
      if (cursor != null && cursor.isBlank())
        throw new IllegalArgumentException("cursor cannot be blank");
      if (status != null
          && (status.isEmpty() || status.stream().distinct().count() != status.size()))
        throw new IllegalArgumentException("status must be non-empty and contain no duplicates");
      if (externalId != null && externalId.isBlank())
        throw new IllegalArgumentException("externalId cannot be blank");
      if (projectId != null && projectId.isBlank())
        throw new IllegalArgumentException("projectId cannot be blank");
      return new PaymentChannelListOptions(limit, cursor, status, externalId, projectId);
    }
  }
}
