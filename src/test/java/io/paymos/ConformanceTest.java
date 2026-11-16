package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ConformanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode contract() throws Exception {
    return JSON.readTree(Files.readString(Path.of("conformance/contract.json")));
  }

  @Test
  void signingAndWebhookVectors() throws Exception {
    JsonNode vectors = contract().path("vectors"), post = vectors.path("post_signing");
    assertEquals(
        post.path("authorization").asText(),
        RequestSigner.authorizationHeader(
            text(post, "api_key"),
            text(post, "api_secret"),
            text(post, "timestamp"),
            text(post, "method"),
            text(post, "path"),
            text(post, "query"),
            text(post, "body")));
    assertEquals("a%20b%2F%2A~", RequestSigner.encodePathSegment("a b/*~"));
    // An absent body signs an EMPTY last segment, never sha256(""). This vector is a POST, so an
    // SDK that shortcuts on the method ("only GET is empty") fails here.
    JsonNode empty = vectors.path("post_empty_body_signing");
    assertEquals(
        empty.path("authorization").asText(),
        RequestSigner.authorizationHeader(
            text(empty, "api_key"),
            text(empty, "api_secret"),
            text(empty, "timestamp"),
            text(empty, "method"),
            text(empty, "path"),
            text(empty, "query"),
            text(empty, "body")));
    JsonNode get = vectors.path("get_query_signing");
    assertEquals(
        text(get, "query"),
        RequestSigner.buildQuery(
            Map.of("status", List.of("paid_over", "paid"), "project_id", "prj/a", "limit", 50)));
    assertEquals(
        text(get, "query"),
        RequestSigner.buildQuery(
            InvoiceListOptions.builder()
                .limit(50)
                .projectId("prj/a")
                .status(List.of(InvoiceStatus.PAID_OVER, InvoiceStatus.PAID))
                .build()));
    JsonNode webhook = vectors.path("webhook");
    WebhookVerifier verifier =
        new WebhookVerifier(
            text(webhook, "secret"),
            Duration.ofSeconds(webhook.path("tolerance_seconds").asLong()));
    byte[] body = text(webhook, "raw_body").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Instant now = Instant.ofEpochSecond(webhook.path("now").asLong());
    assertTrue(verifier.verify(text(webhook, "header"), body, now));
    WebhookEvent<JsonNode> event = verifier.constructEvent(text(webhook, "header"), body, now);
    assertEquals("evt_123", event.eventId());
    assertEquals("inv_123", event.data().path("invoice_id").asText());
    assertFalse(
        verifier.verify(
            text(webhook, "header") + ",t=" + webhook.path("timestamp").asLong(), body, now));
  }

  @Test
  void problemDetailsUsesTopLevelCode() throws Exception {
    JsonNode vector = contract().path("vectors").path("problem_details").path("multi");
    PaymosApiException error =
        new PaymosApiException(
            400, JSON.writeValueAsString(vector), HttpHeaders.of(Map.of(), (a, b) -> true));

    assertEquals("validation_failed", error.code());
    assertNull(error.field());
    assertEquals("field_required", error.errors().get(0).code());
  }

  @Test
  void apiExceptionCanBeSerialized() throws Exception {
    PaymosApiException error =
        new PaymosApiException(
            400,
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"Invalid.\",\"code\":\"validation_failed\"}",
            HttpHeaders.of(Map.of(), (a, b) -> true));

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(error);
    }

    PaymosApiException restored;
    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (PaymosApiException) input.readObject();
    }
    assertEquals(400, restored.statusCode());
    assertEquals("validation_failed", restored.code());
    assertTrue(restored.problem().isObject());
  }

  @Test
  void completeResourceRoutes() {
    List<String> calls = new ArrayList<>();
    PaymosClient.Transport transport =
        (method, uri, headers, body, timeout) -> {
          calls.add(
              method
                  + " "
                  + uri.getRawPath()
                  + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
          String path = uri.getPath();
          String response;
          if (path.equals("/v1/time")) response = "{\"server_time\":1700000000}";
          else if (path.equals("/v1/balances")) response = "[]";
          else if (method.equals("GET")
              && (path.equals("/v1/invoices")
                  || path.equals("/v1/withdrawals")
                  || path.equals("/v1/payment-channels")))
            response = "{\"items\":[],\"next_cursor\":null}";
          else if (method.equals("GET") && path.equals("/v1/payment-channel-deposits"))
            response = "{\"items\":[],\"next_cursor\":\"cur_1\"}";
          else if (path.startsWith("/v1/payment-channel-deposits/")
              || path.endsWith("/simulate-deposit")) response = PaymentChannelTest.depositJson();
          else if (path.contains("payment-channel")) response = PaymentChannelTest.channelJson();
          else if (path.contains("invoice")) response = invoiceJson();
          else response = withdrawalJson();
          return new PaymosClient.Response(200, response, Map.of());
        };
    PaymosClient client =
        new PaymosClient(
            "pk_test_key",
            "sk_test_secret",
            "https://api.paymos.io",
            transport,
            Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC),
            0,
            Duration.ZERO,
            Duration.ofSeconds(30));
    client.system.time();
    client.invoices.create(invoiceRequest());
    client.invoices.get("inv/1");
    client.invoices.list(InvoiceListOptions.builder().limit(1).build());
    client.invoices.cancel("inv_1", "reason");
    client.invoices.confirmPayment("inv_1", "USDT", "tron");
    client.invoices.simulatePayment("inv_1", "paid");
    client.withdrawals.create(withdrawalRequest());
    client.withdrawals.get("wdr_1");
    client.withdrawals.list(WithdrawalListOptions.builder().limit(1).build());
    client.withdrawals.cancel("wdr_1", "reason");
    client.withdrawals.simulateCompletion("wdr_1");
    client.paymentChannels.create(new CreatePaymentChannelRequest("prj_1", "customer-42"));
    client.paymentChannels.get("pc_1");
    client.paymentChannels.list(PaymentChannelListOptions.builder().limit(1).build());
    client.paymentChannels.block("pc_1");
    client.paymentChannels.unblock("pc_1");
    client.paymentChannels.simulateDeposit(
        "pc_1", new SimulateDepositRequest("100.00", "USDT", "TRC20"));
    client.paymentChannelDeposits.read(PaymentChannelDepositFeedOptions.builder().limit(1).build());
    client.paymentChannelDeposits.get("pcd_1");
    client.balances.get();
    List<String> expected =
        List.of(
            "GET /v1/time",
            "POST /v1/invoices",
            "GET /v1/invoices/inv%2F1",
            "GET /v1/invoices?limit=1",
            "POST /v1/invoices/inv_1/cancel",
            "POST /v1/invoices/inv_1/confirm-payment",
            "POST /v1/sandbox/invoices/inv_1/simulate-payment",
            "POST /v1/withdrawals",
            "GET /v1/withdrawals/wdr_1",
            "GET /v1/withdrawals?limit=1",
            "POST /v1/withdrawals/wdr_1/cancel",
            "POST /v1/sandbox/withdrawals/wdr_1/simulate-completion",
            "POST /v1/payment-channels",
            "GET /v1/payment-channels/pc_1",
            "GET /v1/payment-channels?limit=1",
            "POST /v1/payment-channels/pc_1/block",
            "POST /v1/payment-channels/pc_1/unblock",
            "POST /v1/sandbox/payment-channels/pc_1/simulate-deposit",
            "GET /v1/payment-channel-deposits?limit=1",
            "GET /v1/payment-channel-deposits/pcd_1",
            "GET /v1/balances");
    assertEquals(expected, calls);
  }

  @Test
  void retryAndErrorContract() {
    AtomicInteger attempts = new AtomicInteger();
    PaymosClient.Transport retrying =
        (method, uri, headers, body, timeout) -> {
          int attempt = attempts.incrementAndGet();
          return attempt == 1
              ? new PaymosClient.Response(
                  429, "{\"detail\":\"slow down\"}", Map.of("Retry-After", List.of("0")))
              : new PaymosClient.Response(200, invoiceJson(), Map.of());
        };
    PaymosClient client = configured(retrying, 2);
    assertDoesNotThrow(() -> client.invoices.create(invoiceRequest()));
    assertEquals(2, attempts.get());

    attempts.set(0);
    PaymosClient.Transport unavailable =
        (method, uri, headers, body, timeout) -> {
          attempts.incrementAndGet();
          return new PaymosClient.Response(503, "{\"detail\":\"retry later\"}", Map.of());
        };
    PaymosApiException error =
        assertThrows(
            PaymosApiException.class,
            () -> configured(unavailable, 2).invoices.create(invoiceRequest()));
    assertEquals("unavailable", error.kind());
    assertEquals(1, attempts.get(), "POST must not retry a generic 503");
  }

  @Test
  void repeatedInitialCursorIsRejected() {
    PaymosClient.Transport transport =
        (method, uri, headers, body, timeout) ->
            new PaymosClient.Response(200, "{\"items\":[],\"next_cursor\":\"same\"}", Map.of());
    Iterable<InvoiceListItem> items =
        configured(transport, 0)
            .invoices
            .iterate(InvoiceListOptions.builder().cursor("same").build(), 3);
    assertThrows(PaymosException.class, () -> items.iterator().hasNext());
  }

  @Test
  void invoiceProductsWireAndLegacyConstructorCompatibility() throws Exception {
    // Legacy 7-arg CreateInvoiceRequest and 5-arg Invoice.Order constructors stay compatible.
    CreateInvoiceRequest legacyCtor =
        new CreateInvoiceRequest("prj_1", "30.00", "USD", "order-legacy-1", null, null, null);
    assertNull(legacyCtor.products());
    Invoice.Order legacyOrder = new Invoice.Order("order_1", null, "30.00", "USD", null);
    assertNull(legacyOrder.products());

    // @JsonInclude(NON_NULL) omits null optional fields even on a plain snake-case ObjectMapper
    // without a global NON_NULL default inclusion policy.
    ObjectMapper snakeCaseOnly =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    Invoice.InvoiceProduct minimalItem = new Invoice.InvoiceProduct("Priority Support", 1, "12.00");
    JsonNode minimalNode = snakeCaseOnly.readTree(snakeCaseOnly.writeValueAsString(minimalItem));
    assertFalse(minimalNode.has("description"));
    assertFalse(minimalNode.has("image_url"));
    assertEquals("Priority Support", minimalNode.path("title").asText());
    assertEquals(1, minimalNode.path("quantity").asInt());
    assertEquals("12.00", minimalNode.path("unit_amount").asText());
    JsonNode legacyNode = snakeCaseOnly.readTree(snakeCaseOnly.writeValueAsString(legacyCtor));
    assertFalse(legacyNode.has("products"));

    List<Invoice.InvoiceProduct> mutableProducts = new ArrayList<>(List.of(minimalItem));
    CreateInvoiceRequest copiedRequest =
        CreateInvoiceRequest.builder()
            .projectId("prj_1")
            .amount("12.00")
            .currency("USD")
            .externalOrderId("order-copy")
            .products(mutableProducts)
            .build();
    mutableProducts.clear();
    assertEquals(1, copiedRequest.products().size());

    JsonNode invoices = contract().path("vectors").path("invoices");
    List<String> capturedTargets = new ArrayList<>();
    List<String> capturedBodies = new ArrayList<>();
    Deque<String> responses =
        new ArrayDeque<>(
            List.of(
                invoices.path("create_single_product").path("response").toString(),
                invoices.path("create_multi_product").path("response").toString(),
                invoices.path("legacy_without_products").path("response").toString(),
                invoices.path("null_products").path("response").toString(),
                invoices
                    .path("null_products")
                    .path("response")
                    .toString()
                    .replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"products\":null")));
    PaymosClient client =
        configured(
            (method, uri, headers, body, timeout) -> {
              capturedTargets.add(method + " " + uri.getRawPath());
              capturedBodies.add(new String(body, StandardCharsets.UTF_8));
              return new PaymosClient.Response(200, responses.removeFirst(), Map.of());
            },
            0);

    JsonNode single = invoices.path("create_single_product");
    Invoice singleInvoice =
        client.invoices.create(
            CreateInvoiceRequest.builder()
                .projectId("prj_1")
                .amount("47.00")
                .currency("USD")
                .externalOrderId("order-product-single")
                .products(
                    List.of(
                        new Invoice.InvoiceProduct(
                            "Studio Pro",
                            "One-month access",
                            "https://shop.example/images/studio-pro.webp",
                            2,
                            "24.50")))
                .build());
    assertEquals("POST /v1/invoices", capturedTargets.get(0));
    assertEquals(single.path("body"), JSON.readTree(capturedBodies.get(0)));
    assertEquals("inv_prod_1", singleInvoice.invoiceId());
    assertEquals("47.00", singleInvoice.order().amount());
    assertEquals(1, singleInvoice.order().products().size());
    Invoice.InvoiceProduct firstDecoded = singleInvoice.order().products().get(0);
    assertEquals("Studio Pro", firstDecoded.title());
    assertEquals("One-month access", firstDecoded.description());
    assertEquals("https://shop.example/images/studio-pro.webp", firstDecoded.imageUrl());
    assertEquals(2, firstDecoded.quantity());
    assertEquals("24.50", firstDecoded.unitAmount());

    JsonNode multi = invoices.path("create_multi_product");
    Invoice multiInvoice =
        client.invoices.create(
            CreateInvoiceRequest.builder()
                .projectId("prj_1")
                .amount("59.00")
                .currency("USD")
                .externalOrderId("order-product-multi")
                .products(
                    List.of(
                        new Invoice.InvoiceProduct(
                            "Studio Pro",
                            "One-month access",
                            "https://shop.example/images/studio-pro.webp",
                            2,
                            "24.50"),
                        new Invoice.InvoiceProduct("Priority Support", 1, "12.00")))
                .build());
    JsonNode multiBody = JSON.readTree(capturedBodies.get(1));
    assertEquals(multi.path("body"), multiBody);
    assertFalse(multiBody.path("products").get(1).has("description"));
    assertFalse(multiBody.path("products").get(1).has("image_url"));
    assertEquals("inv_prod_2", multiInvoice.invoiceId());
    assertEquals("59.00", multiInvoice.order().amount());
    assertEquals(2, multiInvoice.order().products().size());
    Invoice.InvoiceProduct secondDecoded = multiInvoice.order().products().get(1);
    assertEquals("Priority Support", secondDecoded.title());
    assertNull(secondDecoded.description());
    assertNull(secondDecoded.imageUrl());
    assertEquals(1, secondDecoded.quantity());
    assertEquals("12.00", secondDecoded.unitAmount());

    JsonNode legacy = invoices.path("legacy_without_products");
    Invoice legacyInvoice =
        client.invoices.create(
            CreateInvoiceRequest.builder()
                .projectId("prj_1")
                .amount("30.00")
                .currency("USD")
                .externalOrderId("order-legacy-1")
                .build());
    JsonNode legacyBody = JSON.readTree(capturedBodies.get(2));
    assertEquals(legacy.path("body"), legacyBody);
    assertFalse(legacyBody.has("products"));
    assertEquals("30.00", legacyInvoice.order().amount());
    assertNull(legacyInvoice.order().products());

    Invoice nullInvoice = client.invoices.get("inv_null_1");
    assertNull(nullInvoice.order().products());

    Invoice explicitNullInvoice = client.invoices.get("inv_null_1");
    assertNull(explicitNullInvoice.order().products());
  }

  private static PaymosClient configured(PaymosClient.Transport transport, int maxRetries) {
    return new PaymosClient(
        "pk",
        "sk",
        "https://api.paymos.io",
        transport,
        Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC),
        maxRetries,
        Duration.ZERO,
        Duration.ofSeconds(30));
  }

  private static String text(JsonNode node, String name) {
    return node.path(name).asText();
  }

  private static CreateInvoiceRequest invoiceRequest() {
    return CreateInvoiceRequest.builder()
        .projectId("prj_1")
        .amount("10.00")
        .currency("USD")
        .externalOrderId("order_1")
        .build();
  }

  private static CreateWithdrawalRequest withdrawalRequest() {
    return new CreateWithdrawalRequest("address", "tron", "USDT", "5.00", "payout_1");
  }

  private static String invoiceJson() {
    return "{\"invoice_id\":\"inv_1\",\"project_id\":\"prj_1\",\"status\":\"awaiting_payment\",\"is_final\":false,\"is_test\":true,\"payment_url\":\"https://pay.paymos.io/i/inv_1\",\"order\":{\"external_id\":\"order_1\",\"amount\":\"10.00\",\"currency\":\"USD\"},\"created_at\":1700000000,\"updated_at\":1700000000}";
  }

  private static String withdrawalJson() {
    return "{\"withdrawal_id\":\"wdr_1\",\"external_order_id\":\"payout_1\",\"status\":\"created\",\"is_final\":false,\"is_test\":true,\"amount\":\"5.00\",\"currency\":\"USDT\",\"network\":\"tron\",\"destination_address\":\"address\",\"created_at\":1700000000}";
  }
}
