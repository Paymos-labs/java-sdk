package io.paymos;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional refund filters. Period bounds are RFC 3339 strings with a timezone. */
public record RefundListOptions(
    Integer limit,
    String cursor,
    String invoiceId,
    List<WithdrawalStatus> status,
    String createdFrom,
    String createdTo) {
  public RefundListOptions {
    status = status == null ? null : List.copyOf(status);
  }

  public static Builder builder() {
    return new Builder();
  }

  static RefundListOptions empty() {
    return builder().build();
  }

  RefundListOptions withCursor(String value) {
    return new RefundListOptions(limit, value, invoiceId, status, createdFrom, createdTo);
  }

  Map<String, ?> query() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("limit", limit);
    values.put("cursor", cursor);
    values.put("invoice_id", invoiceId);
    values.put("status", status);
    values.put("created_from", createdFrom);
    values.put("created_to", createdTo);
    return values;
  }

  public static final class Builder {
    private Integer limit;
    private String cursor;
    private String invoiceId;
    private List<WithdrawalStatus> status;
    private String createdFrom;
    private String createdTo;

    public Builder limit(int value) {
      limit = value;
      return this;
    }

    public Builder cursor(String value) {
      cursor = value;
      return this;
    }

    public Builder invoiceId(String value) {
      invoiceId = value;
      return this;
    }

    public Builder status(List<WithdrawalStatus> value) {
      status = value;
      return this;
    }

    public Builder createdFrom(String value) {
      createdFrom = value;
      return this;
    }

    public Builder createdTo(String value) {
      createdTo = value;
      return this;
    }

    public RefundListOptions build() {
      if (limit != null && (limit < 1 || limit > 100))
        throw new IllegalArgumentException("limit must be between 1 and 100");
      if (cursor != null && cursor.isBlank())
        throw new IllegalArgumentException("cursor cannot be blank");
      if (invoiceId != null && invoiceId.isBlank())
        throw new IllegalArgumentException("invoiceId cannot be blank");
      if (status != null
          && (status.isEmpty() || status.stream().distinct().count() != status.size()))
        throw new IllegalArgumentException("status must be non-empty and contain no duplicates");
      OffsetDateTime from = createdFrom == null ? null : OffsetDateTime.parse(createdFrom);
      OffsetDateTime to = createdTo == null ? null : OffsetDateTime.parse(createdTo);
      if (from != null && to != null && !from.isBefore(to))
        throw new IllegalArgumentException("createdTo must be later than createdFrom");
      return new RefundListOptions(limit, cursor, invoiceId, status, createdFrom, createdTo);
    }
  }
}
