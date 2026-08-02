package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.net.http.HttpHeaders;
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
              && (path.equals("/v1/invoices") || path.equals("/v1/withdrawals")))
            response = "{\"items\":[],\"next_cursor\":null}";
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
