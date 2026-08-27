package io.paymos;

import java.util.List;
import java.util.Objects;

/**
 * One page of the confirmed-deposit feed. Deliberately NOT a {@link Page}.
 *
 * <p>{@code nextCursor} is never null and never absent, including on an empty page: feed positions
 * are global across all merchants, so a page whose items your filter skipped still hands back a
 * cursor past what was skipped. There is no last page — money keeps arriving. Store the cursor and
 * resume from it; never stop polling because a page came back empty, and never write {@code if
 * (cursor == null) break} against this type, which is exactly what a nullable cursor would invite.
 *
 * <p>{@code blocked} is absent on every healthy page.
 */
public record PaymentChannelDepositFeedPage(
    List<PaymentChannelDeposit> items,
    String nextCursor,
    PaymentChannelDepositFeedBlockage blocked) {
  public PaymentChannelDepositFeedPage {
    Objects.requireNonNull(items, "items is required");
    items = List.copyOf(items);
    Objects.requireNonNull(nextCursor, "nextCursor is required");
  }
}
