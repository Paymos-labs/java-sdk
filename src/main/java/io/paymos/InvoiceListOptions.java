package io.paymos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional filters for listing invoices. */
public record InvoiceListOptions(
    Integer limit,
    String cursor,
    List<InvoiceStatus> status,
    String externalOrderId,
    String projectId,
    Long createdFrom,
    Long createdTo) {

  public InvoiceListOptions {
    status = status == null ? null : List.copyOf(status);
  }

  public static Builder builder() {
    return new Builder();
  }

  static InvoiceListOptions empty() {
    return builder().build();
  }

  InvoiceListOptions withCursor(String value) {
    return new InvoiceListOptions(
        limit, value, status, externalOrderId, projectId, createdFrom, createdTo);
  }

  Map<String, ?> query() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("limit", limit);
    values.put("cursor", cursor);
    values.put("status", status);
    values.put("external_order_id", externalOrderId);
    values.put("project_id", projectId);
    values.put("created_from", createdFrom);
    values.put("created_to", createdTo);
    return values;
  }

  public static final class Builder {
    private Integer limit;
    private String cursor;
    private List<InvoiceStatus> status;
    private String externalOrderId;
    private String projectId;
    private Long createdFrom;
    private Long createdTo;

    public Builder limit(int value) {
      limit = value;
      return this;
    }

    public Builder cursor(String value) {
      cursor = value;
      return this;
    }

    public Builder status(List<InvoiceStatus> value) {
      status = value;
      return this;
    }

    public Builder externalOrderId(String value) {
      externalOrderId = value;
      return this;
    }

    public Builder projectId(String value) {
      projectId = value;
      return this;
    }

    public Builder createdFrom(long value) {
      createdFrom = value;
      return this;
    }

    public Builder createdTo(long value) {
      createdTo = value;
      return this;
    }

    public InvoiceListOptions build() {
      if (limit != null && (limit < 1 || limit > 100))
        throw new IllegalArgumentException("limit must be between 1 and 100");
      if (cursor != null && cursor.isBlank())
        throw new IllegalArgumentException("cursor cannot be blank");
      if (status != null
          && (status.isEmpty() || status.stream().distinct().count() != status.size()))
        throw new IllegalArgumentException("status must be non-empty and contain no duplicates");
      if (createdFrom != null && createdFrom < 0 || createdTo != null && createdTo < 0)
        throw new IllegalArgumentException("timestamps cannot be negative");
      if (createdFrom != null && createdTo != null && createdFrom >= createdTo)
        throw new IllegalArgumentException("createdTo must be later than createdFrom");
      return new InvoiceListOptions(
          limit, cursor, status, externalOrderId, projectId, createdFrom, createdTo);
    }
  }
}
