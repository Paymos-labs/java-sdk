package io.paymos;

import java.util.List;
import java.util.Objects;

/** One forward-only cursor page. */
public record Page<T>(List<T> items, String nextCursor) {
  public Page {
    Objects.requireNonNull(items, "items is required");
    items = List.copyOf(items);
  }
}
