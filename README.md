# Paymos Java SDK

Official Java 17+ client for the Paymos Merchant API. It provides the complete
resource surface — invoices, withdrawals, balances and static per-customer wallets
with their confirmed-deposit feed — HMAC signing, bounded cursor iteration, structured errors,
safe retries, and raw-body webhook verification.

```xml
<dependency>
  <groupId>io.paymos</groupId>
  <artifactId>paymos-java</artifactId>
  <version>${paymos.version}</version>
</dependency>
```

Set `paymos.version` to the newest release on
[Maven Central](https://central.sonatype.com/artifact/io.paymos/paymos-java) — this
page does not pin one, because it would be a release behind the day after it shipped.

```java
PaymosClient paymos = new PaymosClient(
    System.getenv("PAYMOS_API_KEY"),
    System.getenv("PAYMOS_API_SECRET"));

Invoice invoice = paymos.invoices.create(
    CreateInvoiceRequest.builder()
        .projectId("prj_...")
        .amount("10.00")
        .currency("USD")
        .externalOrderId("order_123")
        .build());
```

`iterate` provides bounded lazy cursor traversal. API failures throw
`PaymosApiException`, retaining the response status, body, problem JSON, stable
error code, field, error kind, and numeric `Retry-After`.

A payment channel is one payer's reusable set of deposit addresses. Create it,
read its rails, then poll the confirmed-deposit feed and persist the cursor:

```java
PaymentChannel channel = paymos.paymentChannels.create(
    new CreatePaymentChannelRequest("prj_...", "customer_42"));

for (PaymentChannelNetwork rail : channel.networks()) {
  if (rail.address() == null) continue; // this rail has not provisioned yet
  System.out.println(rail.network() + " " + rail.status() + " " + rail.address());
}

PaymentChannelDepositFeedPage feed = paymos.paymentChannelDeposits.read(
    PaymentChannelDepositFeedOptions.builder().cursor(savedCursor).limit(100).build());
for (PaymentChannelDeposit deposit : feed.items()) credit(deposit);
saveCursor(feed.nextCursor());
```

The no-argument `read()` overload covers the very first poll, before any cursor
is stored. Four things that bite a caller who guesses. Repeating the same
`externalId` returns the **same** channel — `200` instead of `201`, and both
bodies are a channel, so calling this on every checkout is safe: a repeat is not
a duplicate and not an error. A rail's `address()` is null until that rail
finishes provisioning and never changes once set, so null means "not yet", not
"no address". A null `minimumDeposit()` means "we cannot quote a minimum right
now", never "there is no minimum" — reading it as zero is how a merchant accepts
a deposit that lands below the live minimum and is never credited. And
`nextCursor()` is never null and never empty, not even on a page with no items:
store it and resume from it, and never loop until it is null the way `iterate`
ends a list, because that loop either spins forever or stops on the first quiet
page and leaves reconciliation silently behind. `confirmedFrom` is the first
poll's lower bound only; afterwards the stored cursor is the resume mechanism.

```java
WebhookVerifier verifier = new WebhookVerifier(System.getenv("PAYMOS_WEBHOOK_SECRET"));
WebhookEvent<JsonNode> event =
    verifier.constructEvent(signatureHeader, rawRequestBody, Instant.now());
```

Verify the exact raw request bytes before parsing JSON. Never expose the API
secret to browser or mobile code.

The three `payment_channel.deposit.*` events each carry a full `PaymentChannelDeposit`
as their `data`, so pass it as the fourth argument instead of decoding a raw `JsonNode`:

```java
WebhookEvent<PaymentChannelDeposit> event =
    verifier.constructEvent(
        signatureHeader, rawRequestBody, Instant.now(), PaymentChannelDeposit.class);
PaymentChannelDeposit deposit = event.data();

if (event.eventType().equals("payment_channel.deposit.confirmed") && deposit.isFinal()) {
  credit(deposit.paymentChannelExternalId(), deposit.net()); // idempotent by event.eventId()
}
```

`confirming` and `reorged` are advisory and may arrive out of order: a `confirming`
can land after the `confirmed` for the same deposit, and a `reorged` can be
superseded by a later `confirmed`. Credit only on `payment_channel.deposit.confirmed`
with `isFinal()` true, and never let an advisory event regress a deposit already
known to be confirmed — crediting on `confirming` releases goods against money a
reorg can still take back.

Full documentation: https://paymos.io/docs/server-sdks
