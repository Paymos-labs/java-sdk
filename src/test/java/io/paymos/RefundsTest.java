package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

final class RefundsTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String INVOICE_ID = "inv_74BPZFhr9qy9Uz2fbRkdJX";

  private static JsonNode vectors() throws Exception {
    return JSON.readTree(Files.readString(Path.of("conformance/contract.json")))
        .path("vectors")
        .path("refunds");
  }

  @Test
  void simulateTerminalStatusesPreservesSignedRequestAndCurrentRefundFields() throws Exception {
    String address = "UQDKbjIcfM6ezt8KjKJJLshZJJSqX7XOA4ff-W72r5gqPuwA";
    for (String status : List.of("completed", "failed", "cancelled")) {
      com.fasterxml.jackson.databind.node.ObjectNode wire =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              JSON.readTree(refund("wdr_1", "full", "20"));
      wire.put("status", status);
      wire.put("is_final", true);
      wire.put("network", "TON");
      wire.put("destination_address", address);
      wire.put("first_sender", address);
      wire.put("type", "refund");
      wire.put("project_id", "prj_1");
      Recorder recorder = new Recorder(response(200, wire.toString()));
      Refund result = client(recorder).refunds.simulate("wdr/1 ?", status);
      assertEquals(status, result.status().value());
      assertEquals(address, result.destinationAddress());
      assertEquals(address, result.firstSender());
      assertEquals("refund", result.type());
      assertEquals("prj_1", result.projectId());
      assertEquals(List.of("POST /v1/sandbox/refunds/wdr%2F1%20%3F/simulate"), recorder.targets);
      assertEquals(
          JSON.readTree("{\"status\":\"" + status + "\"}"), JSON.readTree(recorder.bodies.get(0)));
    }
    Refund legacy =
        new Refund(
            "wdr_1",
            INVOICE_ID,
            RefundMode.FULL,
            "key",
            WithdrawalStatus.CREATED,
            false,
            true,
            "20",
            null,
            "USDT",
            "TON",
            address,
            address,
            null,
            null,
            1700000000L,
            null,
            null,
            null);
    assertNull(legacy.type());
    assertNull(legacy.projectId());
  }

  @Test
  void fullAndSameKeyReplayUseOnlyTheSignedContractFields() throws Exception {
    JsonNode contract = vectors().path("create");
    Recorder recorder =
        new Recorder(
            response(contract.path("new_status").asInt(), refund("wdr_full", "full", "120")),
            response(contract.path("replay_status").asInt(), refund("wdr_full", "full", "120")));
    PaymosClient client = client(recorder);

    Refund created = client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");
    Refund replay = client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");

    assertEquals(created, replay);
    assertEquals("wdr_full", created.withdrawalId());
    assertNull(created.type());
    assertNull(created.projectId());
    assertEquals("120", created.amount());
    assertEquals(WithdrawalStatus.CREATED, created.status());
    assertFalse(created.isFinal());
    assertEquals(List.of("POST /v1/refunds", "POST /v1/refunds"), recorder.targets);
    for (String body : recorder.bodies) {
      JsonNode payload = JSON.readTree(body);
      assertEquals(contract.path("body"), payload);
      for (JsonNode field : contract.path("rejected_fields"))
        assertFalse(payload.has(field.asText()));
    }
    assertEquals(recorder.bodies.get(0), recorder.bodies.get(1));
  }

  @Test
  void sandboxOverpaymentTwentyThenFullOneHundredUsesServerResponses() throws Exception {
    Recorder recorder =
        new Recorder(
            response(200, sandboxInvoice()),
            response(201, refund("wdr_over", "overpayment", "20")),
            response(200, completed(refund("wdr_over", "overpayment", "20"))),
            response(201, refund("wdr_remaining", "full", "100")),
            response(200, completed(refund("wdr_remaining", "full", "100"))));
    PaymosClient client = client(recorder);

    Invoice paid = client.invoices.simulatePayment(INVOICE_ID, "overpaid");
    Refund over = client.refunds.create(INVOICE_ID, RefundMode.OVERPAYMENT, "refund-order-2");
    Refund overCompleted = client.refunds.get(over.withdrawalId());
    Refund full = client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");
    Refund fullCompleted = client.refunds.get(full.withdrawalId());

    assertEquals("120", paid.payment().paid());
    assertEquals("100", paid.payment().expected());
    assertEquals(InvoiceStatus.PAID_OVER, paid.status());
    assertEquals("{\"stage\":\"overpaid\"}", recorder.bodies.get(0));
    assertEquals(vectors().path("overpayment").path("body"), JSON.readTree(recorder.bodies.get(1)));
    assertEquals(vectors().path("create").path("body"), JSON.readTree(recorder.bodies.get(3)));
    assertEquals("20", over.amount());
    assertEquals(RefundMode.OVERPAYMENT, over.mode());
    assertEquals("100", full.amount());
    assertEquals(RefundMode.FULL, full.mode());
    assertEquals(over.withdrawalId(), overCompleted.withdrawalId());
    assertEquals(full.withdrawalId(), fullCompleted.withdrawalId());
    for (Refund terminal : List.of(overCompleted, fullCompleted)) {
      assertTrue(terminal.isTest());
      assertTrue(terminal.isFinal());
      assertEquals(WithdrawalStatus.COMPLETED, terminal.status());
      assertEquals(1700000100L, terminal.completedAt());
      assertEquals("TFirstSender", terminal.firstSender());
    }
    assertEquals(
        List.of(
            "POST /v1/sandbox/invoices/" + INVOICE_ID + "/simulate-payment",
            "POST /v1/refunds",
            "GET /v1/refunds/wdr_over",
            "POST /v1/refunds",
            "GET /v1/refunds/wdr_remaining"),
        recorder.targets);
  }

  @Test
  void changingTheModeForTheSameKeyPreservesTheConflictProblem() throws Exception {
    String problem = problem(409, "idempotency_conflict", "external_order_id");
    Recorder recorder =
        new Recorder(
            response(201, refund("wdr_full", "full", "120")),
            response(vectors().path("create").path("changed_mode_status").asInt(), problem));
    PaymosClient client = client(recorder);
    client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");

    PaymosApiException error =
        assertThrows(
            PaymosApiException.class,
            () -> client.refunds.create(INVOICE_ID, RefundMode.OVERPAYMENT, "refund-order-1"));

    assertEquals(409, error.statusCode());
    assertEquals("conflict", error.kind());
    assertEquals("idempotency_conflict", error.code());
    assertEquals("external_order_id", error.field());
    assertEquals("Refund request rejected.", error.detail());
    assertEquals("Refund error", error.title());
    assertEquals("about:blank", error.type());
    assertEquals(409, error.problemStatus());
    assertEquals(problem, error.responseBody());
    assertEquals(
        JSON.readTree(recorder.bodies.get(0)).path("external_order_id"),
        JSON.readTree(recorder.bodies.get(1)).path("external_order_id"));
    assertEquals("overpayment", JSON.readTree(recorder.bodies.get(1)).path("mode").asText());
    assertEquals(2, recorder.targets.size());
  }

  @Test
  void anUnknownPostOutcomeIsReplayedExplicitlyWithTheSameKey() {
    Recorder recorder =
        new Recorder(
            new IOException("Response lost"), response(200, refund("wdr_saved", "full", "120")));
    PaymosClient client = client(recorder);

    assertThrows(
        PaymosException.class,
        () -> client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1"));
    assertEquals(1, recorder.targets.size(), "An uncertain POST is not automatically retried");
    Refund replay = client.refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");

    assertEquals("wdr_saved", replay.withdrawalId());
    assertEquals(2, recorder.targets.size());
    assertEquals(recorder.bodies.get(0), recorder.bodies.get(1));
  }

  @Test
  void throttlingRetriesTheSameModeAndKey() {
    Recorder recorder =
        new Recorder(
            new PaymosClient.Response(
                429, problem(429, "rate_limited", null), Map.of("Retry-After", List.of("0"))),
            response(200, refund("wdr_saved", "full", "120")));

    Refund result = client(recorder).refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1");

    assertEquals("wdr_saved", result.withdrawalId());
    assertEquals(2, recorder.targets.size());
    assertEquals(recorder.bodies.get(0), recorder.bodies.get(1));
  }

  @Test
  void anUnavailableCreateIsNotRetriedOrGivenANewKey() {
    Recorder recorder = new Recorder(response(503, problem(503, "unavailable", null)));

    PaymosApiException error =
        assertThrows(
            PaymosApiException.class,
            () -> client(recorder).refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1"));

    assertEquals(503, error.statusCode());
    assertEquals("unavailable", error.code());
    assertEquals(1, recorder.targets.size());
  }

  @Test
  void foreignAndMissingIdsReturnTheSameStructuredNotFound() throws Exception {
    String problem = problem(404, "not_found", null);
    Recorder recorder =
        new Recorder(
            response(vectors().path("get").path("foreign_or_missing_status").asInt(), problem),
            response(404, problem));
    PaymosClient client = client(recorder);

    for (String id : List.of("wdr_foreign", "wdr_missing")) {
      PaymosApiException error =
          assertThrows(PaymosApiException.class, () -> client.refunds.get(id));
      assertEquals(404, error.statusCode());
      assertEquals("not_found", error.code());
      assertEquals("not_found", error.kind());
      assertEquals(problem, error.responseBody());
      assertNull(error.field());
    }
    assertEquals(
        List.of("GET /v1/refunds/wdr_foreign", "GET /v1/refunds/wdr_missing"), recorder.targets);
  }

  @Test
  void getEncodesTheCurrentWithdrawalIdAndPreservesDecimalStrings() {
    Recorder recorder =
        new Recorder(response(200, refund("wdr_current", "full", "9007199254740993.00000001")));

    Refund result = client(recorder).refunds.get("wdr_current/a b");

    assertEquals(List.of("GET /v1/refunds/wdr_current%2Fa%20b"), recorder.targets);
    assertEquals("wdr_current", result.withdrawalId());
    assertEquals(INVOICE_ID, result.invoiceId());
    assertEquals("9007199254740993.00000001", result.amount());
    assertEquals("0.10000001", result.fee());
    assertEquals("USDT", result.currency());
    assertEquals("TRC20", result.network());
    assertEquals(result.firstSender(), result.destinationAddress());
    assertEquals(1700000000L, result.createdAt());
    assertNull(result.txHash());
    assertNull(result.explorerUrl());
    assertNull(result.completedAt());
    assertNull(result.failedAt());
    assertNull(result.cancelledAt());
  }

  @Test
  void listSignsInvoiceStatusesPeriodAndOpaqueCursor() {
    Recorder recorder =
        new Recorder(response(200, page(List.of(refund("wdr_1", "full", "120")), "next+/=")));
    List<WithdrawalStatus> statuses =
        new ArrayList<>(List.of(WithdrawalStatus.COMPLETED, WithdrawalStatus.CREATED));
    RefundListOptions options =
        RefundListOptions.builder()
            .invoiceId(INVOICE_ID)
            .status(statuses)
            .createdFrom("2026-10-01T00:00:00+03:00")
            .createdTo("2026-11-01T00:00:00Z")
            .cursor("prev+/=")
            .limit(100)
            .build();
    statuses.clear();

    Page<Refund> result = client(recorder).refunds.list(options);

    assertEquals(
        List.of(
            "GET /v1/refunds?created_from=2026-10-01T00%3A00%3A00%2B03%3A00&created_to=2026-11-01T00%3A00%3A00Z&cursor=prev%2B%2F%3D&invoice_id="
                + INVOICE_ID
                + "&limit=100&status=completed&status=created"),
        recorder.targets);
    assertEquals("next+/=", result.nextCursor());
    assertEquals("wdr_1", result.items().get(0).withdrawalId());
    assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
    assertThrows(UnsupportedOperationException.class, () -> options.status().clear());
  }

  @Test
  void listWithoutOptionsHasNoQueryOrBody() {
    Recorder recorder =
        new Recorder(response(200, page(List.of(), null)), response(200, page(List.of(), null)));
    PaymosClient client = client(recorder);

    assertNull(client.refunds.list().nextCursor());
    assertTrue(client.refunds.list(null).items().isEmpty());

    assertEquals(List.of("GET /v1/refunds", "GET /v1/refunds"), recorder.targets);
    assertEquals(List.of("", ""), recorder.bodies);
  }

  @Test
  void iterationReadsAll205RefundsWithoutLosingFilters() throws Exception {
    List<Object> responses = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    int id = 0;
    for (JsonNode size : vectors().path("list").path("page_sizes")) {
      List<String> items = new ArrayList<>();
      for (int i = 0; i < size.asInt(); i++) {
        String withdrawalId = "wdr_" + id++;
        expected.add(withdrawalId);
        items.add(refund(withdrawalId, "full", "120"));
      }
      responses.add(response(200, page(items, id < 205 ? "cursor_" + id : null)));
    }
    Recorder recorder = new Recorder(responses.toArray());
    List<String> actual = new ArrayList<>();
    RefundListOptions options =
        RefundListOptions.builder()
            .invoiceId(INVOICE_ID)
            .status(List.of(WithdrawalStatus.CREATED))
            .createdFrom("2026-10-01T00:00:00Z")
            .createdTo("2026-11-01T00:00:00Z")
            .limit(100)
            .build();

    for (Refund item : client(recorder).refunds.iterate(options, 3))
      actual.add(item.withdrawalId());

    assertEquals(205, actual.size());
    assertEquals(expected, actual);
    assertEquals(3, recorder.targets.size());
    String filters = "created_from=2026-10-01T00%3A00%3A00Z&created_to=2026-11-01T00%3A00%3A00Z&";
    String suffix = "invoice_id=" + INVOICE_ID + "&limit=100&status=created";
    assertEquals("GET /v1/refunds?" + filters + suffix, recorder.targets.get(0));
    assertEquals(
        "GET /v1/refunds?" + filters + "cursor=cursor_100&" + suffix, recorder.targets.get(1));
    assertEquals(
        "GET /v1/refunds?" + filters + "cursor=cursor_200&" + suffix, recorder.targets.get(2));
  }

  @Test
  void invalidCursorRetainsTheServerFieldAndCode() throws Exception {
    Recorder recorder =
        new Recorder(
            response(
                vectors().path("list").path("invalid_cursor_status").asInt(),
                problem(400, "pagination_cursor_invalid", "cursor")));

    PaymosApiException error =
        assertThrows(
            PaymosApiException.class,
            () ->
                client(recorder)
                    .refunds
                    .list(RefundListOptions.builder().cursor("invalid").build()));

    assertEquals(400, error.statusCode());
    assertEquals("validation", error.kind());
    assertEquals("pagination_cursor_invalid", error.code());
    assertEquals("cursor", error.field());
  }

  @Test
  void validationErrorsDoNotReplaceTheTopLevelProblemCode() {
    String problem = problem(400, "validation_failed", null);
    problem =
        problem.substring(0, problem.length() - 1)
            + ",\"errors\":[{\"code\":\"field_required\",\"field\":\"mode\",\"message\":\"Required.\"},"
            + "{\"code\":\"field_required\",\"field\":\"invoice_id\",\"message\":\"Required.\"}]}";
    Recorder recorder = new Recorder(response(400, problem));

    PaymosApiException error =
        assertThrows(
            PaymosApiException.class,
            () -> client(recorder).refunds.create(INVOICE_ID, RefundMode.FULL, "refund-order-1"));

    assertEquals("validation_failed", error.code());
    assertNull(error.field());
    assertEquals(2, error.errors().size());
    assertEquals(
        new PaymosApiException.ProblemError("field_required", "mode", "Required."),
        error.errors().get(0));
  }

  @Test
  void invalidCreateArgumentsNeverReachTheTransport() {
    Recorder recorder = new Recorder();
    PaymosClient client = client(recorder);

    assertThrows(
        IllegalArgumentException.class, () -> client.refunds.create(" ", RefundMode.FULL, "key"));
    assertThrows(
        IllegalArgumentException.class, () -> client.refunds.create(null, RefundMode.FULL, "key"));
    assertThrows(NullPointerException.class, () -> client.refunds.create(INVOICE_ID, null, "key"));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.refunds.create(INVOICE_ID, RefundMode.FULL, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.refunds.create(INVOICE_ID, RefundMode.FULL, null));
    assertThrows(IllegalArgumentException.class, () -> client.refunds.get(" "));
    assertTrue(recorder.targets.isEmpty());
  }

  @Test
  void iterationRejectsRepeatedCursorsAndRespectsThePageBound() {
    Recorder repeated = new Recorder(response(200, page(List.of(), "same")));
    Iterable<Refund> items =
        client(repeated).refunds.iterate(RefundListOptions.builder().cursor("same").build(), 3);
    assertThrows(PaymosException.class, () -> items.iterator().hasNext());
    assertEquals(1, repeated.targets.size());

    Recorder bounded =
        new Recorder(response(200, page(List.of(refund("wdr_1", "full", "120")), "more")));
    List<String> ids = new ArrayList<>();
    for (Refund item : client(bounded).refunds.iterate(null, 1)) ids.add(item.withdrawalId());
    assertEquals(List.of("wdr_1"), ids);
    assertEquals(1, bounded.targets.size());
  }

  private static PaymosClient client(PaymosClient.Transport transport) {
    return new PaymosClient(
        "pk_test_key",
        "sk_test_secret",
        "https://api.paymos.io",
        transport,
        Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC),
        2,
        Duration.ZERO,
        Duration.ofSeconds(30));
  }

  private static PaymosClient.Response response(int status, String body) {
    return new PaymosClient.Response(
        status, body, Map.of("Cache-Control", List.of("private, no-store")));
  }

  private static String refund(String id, String mode, String amount) {
    return "{\"withdrawal_id\":\""
        + id
        + "\",\"invoice_id\":\""
        + INVOICE_ID
        + "\",\"mode\":\""
        + mode
        + "\",\"external_order_id\":\"refund-order-"
        + (mode.equals("overpayment") ? "2" : "1")
        + "\",\"status\":\"created\",\"is_final\":false,\"is_test\":true,\"amount\":\""
        + amount
        + "\",\"fee\":\"0.10000001\",\"currency\":\"USDT\",\"network\":\"TRC20\","
        + "\"destination_address\":\"TFirstSender\",\"first_sender\":\"TFirstSender\",\"created_at\":1700000000}";
  }

  private static String sandboxInvoice() {
    return "{\"invoice_id\":\""
        + INVOICE_ID
        + "\",\"project_id\":\"prj_test\",\"status\":\"paid_over\","
        + "\"is_final\":true,\"is_test\":true,\"payment_url\":\"https://pay.test/invoice\","
        + "\"order\":{\"external_id\":\"order\",\"amount\":\"100\",\"currency\":\"USD\"},"
        + "\"payment\":{\"currency\":\"USDT\",\"network\":\"TRC20\",\"chain_id\":728126428,\"expected\":\"100\",\"paid\":\"120\"},"
        + "\"created_at\":1700000000,\"updated_at\":1700000100}";
  }

  private static String completed(String body) {
    return body.replace("\"status\":\"created\"", "\"status\":\"completed\"")
        .replace("\"is_final\":false", "\"is_final\":true")
        .replace(
            "\"created_at\":1700000000", "\"created_at\":1700000000,\"completed_at\":1700000100");
  }

  private static String page(List<String> items, String cursor) {
    return "{\"items\":["
        + String.join(",", items)
        + "],\"next_cursor\":"
        + (cursor == null ? "null" : "\"" + cursor + "\"")
        + "}";
  }

  private static String problem(int status, String code, String field) {
    return "{\"type\":\"about:blank\",\"title\":\"Refund error\",\"status\":"
        + status
        + ",\"detail\":\"Refund request rejected.\",\"code\":\""
        + code
        + "\""
        + (field == null ? "" : ",\"field\":\"" + field + "\"")
        + "}";
  }

  private static final class Recorder implements PaymosClient.Transport {
    private final Deque<Object> responses;
    final List<String> targets = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();

    Recorder(Object... responses) {
      this.responses = new ArrayDeque<>(List.of(responses));
    }

    @Override
    public PaymosClient.Response send(
        String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout)
        throws Exception {
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      targets.add(method + " " + uri.getRawPath() + query);
      bodies.add(new String(body, StandardCharsets.UTF_8));
      assertEquals("1700000000", headers.get("X-Request-Timestamp"));
      String bodyHash =
          body.length == 0
              ? ""
              : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
      String canonical =
          "1700000000\n" + method + "\n" + uri.getRawPath() + "\n" + query + "\n" + bodyHash;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec("sk_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      assertEquals(
          "HMAC-SHA256 pk_test_key:"
              + Base64.getEncoder()
                  .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))),
          headers.get("Authorization"));
      assertEquals("application/json", headers.get("Content-Type"));
      if (method.equals("GET")) assertEquals(0, body.length);
      Object next = responses.removeFirst();
      if (next instanceof Exception error) throw error;
      return (PaymosClient.Response) next;
    }
  }
}
