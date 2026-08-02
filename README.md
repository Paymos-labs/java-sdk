# Paymos Java SDK

Official Java 17+ client for the Paymos Merchant API. It provides the complete
resource surface, HMAC signing, bounded cursor iteration, structured errors,
safe retries, and raw-body webhook verification.

```xml
<dependency>
  <groupId>io.paymos</groupId>
  <artifactId>paymos-java</artifactId>
  <version>1.1.1</version>
</dependency>
```

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

```java
WebhookVerifier verifier = new WebhookVerifier(System.getenv("PAYMOS_WEBHOOK_SECRET"));
WebhookEvent<JsonNode> event =
    verifier.constructEvent(signatureHeader, rawRequestBody, Instant.now());
```

Verify the exact raw request bytes before parsing JSON. Never expose the API
secret to browser or mobile code. Full documentation:
https://paymos.io/docs/server-sdks
