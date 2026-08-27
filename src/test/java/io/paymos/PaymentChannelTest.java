package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Payment channels, and the deposit feed that must never be mistaken for a list.
 *
 * <p>The channel list terminates on a null cursor and has an iterator; the feed never terminates
 * and deliberately has none. Both assertions live in this one file so the distinction stays visible
 * to the next reader.
 */
final class PaymentChannelTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void createTwiceReturnsTheChannelBothTimes() {
    Recorder recorder = new Recorder(response(201, channelJson()), response(200, channelJson()));
    PaymosClient client = client(recorder);
    CreatePaymentChannelRequest payload = new CreatePaymentChannelRequest("prj_1", "customer-42");

    PaymentChannel created = client.paymentChannels.create(payload);
    PaymentChannel existing = client.paymentChannels.create(payload);

    assertEquals("pc_1", created.id());
    assertEquals("pc_1", existing.id(), "The idempotent 200 is a channel, never an error");
    assertEquals(
        List.of("POST /v1/payment-channels", "POST /v1/payment-channels"), recorder.targets);
    assertEquals(
        "{\"project_id\":\"prj_1\",\"external_id\":\"customer-42\"}", recorder.bodies.get(0));
  }

  @Test
  void blockReturnsTheUpdatedChannel() {
    Recorder recorder = new Recorder(response(200, channelJson(false, "blocked")));

    PaymentChannel channel = client(recorder).paymentChannels.block("pc_1");

    assertFalse(channel.isAcceptingPayments(), "block is not a void call");
    assertEquals("blocked", channel.status());
    assertEquals(List.of("POST /v1/payment-channels/pc_1/block"), recorder.targets);
  }

  @Test
  void unblockReturnsTheUpdatedChannel() {
    Recorder recorder = new Recorder(response(200, channelJson(true, "active")));

    PaymentChannel channel = client(recorder).paymentChannels.unblock("pc_1");

    assertTrue(channel.isAcceptingPayments(), "unblock is not a void call either");
    assertEquals("active", channel.status());
    assertEquals(List.of("POST /v1/payment-channels/pc_1/unblock"), recorder.targets);
  }

  @Test
  void anEmptyFeedPageStillYieldsACursorToResumeFrom() {
    Recorder recorder = new Recorder(response(200, "{\"items\":[],\"next_cursor\":\"cur_2\"}"));

    PaymentChannelDepositFeedPage page = client(recorder).paymentChannelDeposits.read();

    assertTrue(page.items().isEmpty());
    assertEquals(
        "cur_2", page.nextCursor(), "An empty feed page still advances past what it skipped");
    assertNull(page.blocked());
    assertEquals(List.of("GET /v1/payment-channel-deposits"), recorder.targets);
  }

  @Test
  void aBlockedFeedPageSurfacesWhichDepositAndWhy() {
    Recorder recorder =
        new Recorder(
            response(
                200,
                "{\"items\":[],\"next_cursor\":\"cur_2\",\"blocked\":{\"deposit_id\":\"pcd_9\",\"reason\":\"not_confirmed\"}}"));

    PaymentChannelDepositFeedPage page = client(recorder).paymentChannelDeposits.read();

    assertNotNull(page.blocked(), "A stall must not look like a quiet day");
    assertEquals("pcd_9", page.blocked().depositId());
    assertEquals("not_confirmed", page.blocked().reason());
    assertEquals("cur_2", page.nextCursor());
  }

  @Test
  void everyFilterReachesTheWireInSnakeCase() {
    Recorder channels = new Recorder(response(200, "{\"items\":[],\"next_cursor\":null}"));
    client(channels)
        .paymentChannels
        .list(
            PaymentChannelListOptions.builder()
                .limit(25)
                .cursor("cur_1")
                .status(List.of("active", "blocked"))
                .externalId("customer-42")
                .projectId("prj_1")
                .build());

    Recorder feed = new Recorder(response(200, "{\"items\":[],\"next_cursor\":\"cur_2\"}"));
    client(feed)
        .paymentChannelDeposits
        .read(
            PaymentChannelDepositFeedOptions.builder()
                .limit(25)
                .cursor("cur_1")
                .projectId("prj_1")
                .paymentChannelId("pc_1")
                .confirmedFrom(1700000000L)
                .build());

    // The API rejects unknown query fields outright, so a camelCase slip is a 400 at a merchant.
    assertEquals(
        List.of(
            "GET /v1/payment-channels?cursor=cur_1&external_id=customer-42&limit=25&project_id=prj_1&status=active&status=blocked"),
        channels.targets);
    assertEquals(
        List.of(
            "GET /v1/payment-channel-deposits?confirmed_from=1700000000&cursor=cur_1&limit=25&payment_channel_id=pc_1&project_id=prj_1"),
        feed.targets);
  }

  @Test
  void idsArePathEncodedNotQueryEncoded() {
    Recorder recorder = new Recorder(response(200, channelJson()), response(200, depositJson()));
    PaymosClient client = client(recorder);

    client.paymentChannels.get("pc_1/a b");
    client.paymentChannelDeposits.get("pcd_1/a b");

    // RFC 3986 path encoding: a space is %20, never the query encoder's plus.
    assertEquals(
        List.of(
            "GET /v1/payment-channels/pc_1%2Fa%20b",
            "GET /v1/payment-channel-deposits/pcd_1%2Fa%20b"),
        recorder.targets);
  }

  @Test
  void simulateSendsNetworkAndOmitsStageWhenUnset() throws Exception {
    Recorder recorder = new Recorder(response(200, depositJson()));

    PaymentChannelDeposit deposit =
        client(recorder)
            .paymentChannels
            .simulateDeposit("pc_1", new SimulateDepositRequest("100.00", "USDT", "TRC20"));

    JsonNode body = JSON.readTree(recorder.bodies.get(0));
    assertEquals("TRC20", body.path("network").asText(), "network is required on simulate");
    assertFalse(body.has("stage"), "An unset stage is omitted entirely, not sent as null or empty");
    assertEquals("pcd_1", deposit.id());
    assertEquals(
        List.of("POST /v1/sandbox/payment-channels/pc_1/simulate-deposit"), recorder.targets);
  }

  @Test
  void simulateSendsTheStageWhenSet() throws Exception {
    Recorder recorder = new Recorder(response(200, depositJson()));

    client(recorder)
        .paymentChannels
        .simulateDeposit(
            "pc_1",
            new SimulateDepositRequest("100.00", "USDT", "TRC20", SimulateDepositStage.CONFIRMING));

    JsonNode body = JSON.readTree(recorder.bodies.get(0));
    assertEquals("confirming", body.path("stage").asText(), "The enum sends its wire spelling");
  }

  @Test
  void theChannelListIteratorTerminates() {
    Recorder recorder =
        new Recorder(
            response(200, "{\"items\":[" + channelJson() + "],\"next_cursor\":\"cur_2\"}"),
            response(
                200,
                "{\"items\":["
                    + channelJson().replace("pc_1", "pc_2")
                    + "],\"next_cursor\":null}"));

    List<String> ids = new ArrayList<>();
    // A null cursor ends an ordinary keyset list. The feed has no equivalent, on purpose.
    for (PaymentChannel channel : client(recorder).paymentChannels.iterate(null, 10))
      ids.add(channel.id());

    assertEquals(List.of("pc_1", "pc_2"), ids);
    assertEquals(2, recorder.targets.size(), "A third page was never requested");
  }

  @Test
  void theFeedPageCannotModelATerminatingCursor() {
    assertThrows(
        NullPointerException.class,
        () -> new PaymentChannelDepositFeedPage(List.of(), null, null),
        "A null cursor would invite a poller to break out of its loop");
  }

  @Test
  void absentWireMembersDeserializeAsNull() {
    Recorder recorder = new Recorder(response(200, minimalDepositJson()));

    PaymentChannelDeposit deposit = client(recorder).paymentChannelDeposits.get("pcd_2");

    assertNull(deposit.contractAddress());
    assertNull(deposit.sourceAddress());
    assertNull(deposit.firstIncludedBlockTimestamp());
    assertNull(deposit.explorerUrl());
    assertNull(deposit.confirmedAt());
    assertFalse(deposit.isFinal());
    assertEquals("100.00", deposit.gross(), "Money stays a decimal string");
  }

  @Test
  void aChannelCarriesItsRailsAndTokens() {
    Recorder recorder = new Recorder(response(200, channelJson()));

    PaymentChannel channel = client(recorder).paymentChannels.get("pc_1");

    PaymentChannelNetwork rail = channel.networks().get(0);
    assertEquals("TRC20", rail.network(), "A rail code, not a chain nickname");
    assertEquals("TXk1", rail.address());
    assertEquals("1.00", rail.tokens().get(0).minimumDeposit());
    assertNull(
        rail.tokens().get(1).minimumDeposit(),
        "Absent means cannot quote a minimum right now, never no minimum");
    PaymentChannelNetwork pending = channel.networks().get(1);
    assertNull(pending.address(), "No address until that rail provisions");
    assertEquals(0, new java.math.BigDecimal("1.5").compareTo(channel.appliedFeePercent()));
  }

  @Test
  void unfilteredReadsSendNoQueryString() {
    Recorder recorder =
        new Recorder(
            response(200, "{\"items\":[],\"next_cursor\":null}"),
            response(200, "{\"items\":[],\"next_cursor\":\"cur_1\"}"));
    PaymosClient client = client(recorder);

    client.paymentChannels.list();
    client.paymentChannelDeposits.read();

    assertEquals(
        List.of("GET /v1/payment-channels", "GET /v1/payment-channel-deposits"), recorder.targets);
  }

  @Test
  void listFiltersAreValidatedBeforeTheyReachTheWire() {
    assertThrows(
        IllegalArgumentException.class, () -> PaymentChannelListOptions.builder().limit(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> PaymentChannelListOptions.builder().status(List.of("active", "active")).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> PaymentChannelDepositFeedOptions.builder().confirmedFrom(-1).build());
    assertThrows(
        IllegalArgumentException.class, () -> new CreatePaymentChannelRequest("prj_1", " "));
    assertThrows(
        NullPointerException.class, () -> new SimulateDepositRequest("1.00", "USDT", null));
  }

  /**
   * The deposit wire shape doubles as the webhook payload, so {@link WebhookVerifier} must decode
   * one through its generic {@code constructEvent} with no extra code. This goes through the real
   * verifier — HMAC-SHA256 hex over {@code "{timestamp}.{body}"}, exactly as a real webhook sender
   * signs it — so the test proves the whole path, not just {@code objectMapper.readValue}.
   *
   * <p>This is a native-coin (TRX) deposit: it has no contract, the payer address was not
   * attributable, and no explorer link was minted — those keys are ABSENT from the JSON, not null,
   * and must stay absent after decoding.
   */
  @Test
  void aConfirmedDepositWebhookRoundTripsThroughTheVerifier() {
    String secret = "whsec_test";
    long timestamp = 1700000000L;
    String body = confirmedDepositWebhookJson();
    String header = "t=" + timestamp + ",v1=" + hmacSha256Hex(secret, timestamp + "." + body);

    WebhookEvent<PaymentChannelDeposit> event =
        new WebhookVerifier(secret)
            .constructEvent(
                header,
                body.getBytes(StandardCharsets.UTF_8),
                Instant.ofEpochSecond(timestamp),
                PaymentChannelDeposit.class);

    assertEquals("payment_channel.deposit.confirmed", event.eventType());
    PaymentChannelDeposit deposit = event.data();
    assertTrue(deposit.isFinal(), "confirmed with is_final true is the only signal to credit on");
    assertEquals("100.00", deposit.gross(), "Money stays a decimal string, never a float");
    assertEquals("1.00", deposit.fee());
    assertEquals("99.00", deposit.net());
    assertNull(deposit.contractAddress());
    assertNull(deposit.sourceAddress());
    assertNull(deposit.explorerUrl());
    assertNull(deposit.firstIncludedBlockTimestamp());
  }

  /** Replays queued responses in order and records exactly what reached the wire. */
  private static final class Recorder implements PaymosClient.Transport {
    private final Deque<PaymosClient.Response> responses = new ArrayDeque<>();
    private final List<String> targets = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    Recorder(PaymosClient.Response... queued) {
      responses.addAll(List.of(queued));
    }

    @Override
    public PaymosClient.Response send(
        String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout) {
      String target =
          method
              + " "
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      targets.add(target);
      bodies.add(new String(body, StandardCharsets.UTF_8));
      if (responses.isEmpty()) throw new AssertionError("Unexpected request: " + target);
      return responses.removeFirst();
    }
  }

  private static PaymosClient.Response response(int status, String body) {
    return new PaymosClient.Response(status, body, Map.of());
  }

  private static PaymosClient client(PaymosClient.Transport transport) {
    return new PaymosClient(
        "pk_test_key",
        "sk_test_secret",
        "https://api.paymos.io",
        transport,
        Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC),
        0,
        Duration.ZERO,
        Duration.ofSeconds(30));
  }

  static String channelJson() {
    return channelJson(true, "active");
  }

  private static String channelJson(boolean accepting, String status) {
    return "{\"id\":\"pc_1\",\"project_id\":\"prj_1\",\"external_id\":\"customer-42\",\"status\":\""
        + status
        + "\",\"is_accepting_payments\":"
        + accepting
        + ",\"is_fully_provisioned\":false,\"is_test\":true,\"applied_fee_percent\":1.5,\"customer_fee_percent\":0,\"networks\":[{\"network\":\"TRC20\",\"status\":\"active\",\"address\":\"TXk1\",\"tokens\":[{\"symbol\":\"USDT\",\"minimum_deposit\":\"1.00\"},{\"symbol\":\"USDC\"}]},{\"network\":\"ERC20\",\"status\":\"provisioning\",\"tokens\":[]}],\"created_at\":1700000000,\"updated_at\":1700000100}";
  }

  static String depositJson() {
    return "{\"id\":\"pcd_1\",\"payment_channel_id\":\"pc_1\",\"project_id\":\"prj_1\",\"payment_channel_external_id\":\"customer-42\",\"status\":\"confirmed\",\"is_final\":true,\"is_test\":true,\"currency\":\"USDT\",\"network\":\"TRC20\",\"chain_id\":728126428,\"contract_address\":\"TR7N\",\"gross\":\"100.00\",\"fee\":\"1.00\",\"net\":\"99.00\",\"applied_fee_percent\":1.0,\"customer_fee_percent\":0,\"tx_hash\":\"abc\",\"transfer_id\":\"trf_1\",\"source_address\":\"TFrom\",\"destination_address\":\"TTo\",\"block_height\":12345,\"first_included_block_timestamp\":1700000050,\"explorer_url\":\"https://tronscan.org/transaction/abc\",\"created_at\":1700000000,\"updated_at\":1700000100,\"confirmed_at\":1700000100}";
  }

  private static String minimalDepositJson() {
    return "{\"id\":\"pcd_2\",\"payment_channel_id\":\"pc_1\",\"project_id\":\"prj_1\",\"payment_channel_external_id\":\"customer-42\",\"status\":\"confirming\",\"is_final\":false,\"is_test\":true,\"currency\":\"USDT\",\"network\":\"TRC20\",\"chain_id\":728126428,\"gross\":\"100.00\",\"fee\":\"1.00\",\"net\":\"99.00\",\"applied_fee_percent\":1.0,\"customer_fee_percent\":0,\"tx_hash\":\"abc\",\"transfer_id\":\"trf_1\",\"destination_address\":\"TTo\",\"block_height\":12345,\"created_at\":1700000000,\"updated_at\":1700000100}";
  }

  // Same fixture values as the TypeScript SDK's webhook round-trip test, so all eight SDKs
  // exercise the same deposit.
  private static String confirmedDepositWebhookJson() {
    return "{\"event_id\":\"evt_2\",\"event_type\":\"payment_channel.deposit.confirmed\",\"version\":1,\"occurred_at\":1700000000,\"data\":{\"id\":\"pcd_9\",\"payment_channel_id\":\"pc_1\",\"project_id\":\"prj_1\",\"payment_channel_external_id\":\"customer-42\",\"status\":\"confirmed\",\"is_final\":true,\"is_test\":false,\"currency\":\"TRX\",\"network\":\"TRC20\",\"chain_id\":728126428,\"gross\":\"100.00\",\"fee\":\"1.00\",\"net\":\"99.00\",\"applied_fee_percent\":1,\"customer_fee_percent\":0,\"tx_hash\":\"9a2f1c\",\"transfer_id\":\"trf_1\",\"destination_address\":\"TChannelAddress\",\"block_height\":60000000,\"created_at\":1700000000,\"updated_at\":1700000100,\"confirmed_at\":1700000100}}";
  }

  /** Signs {@code message} the way a real webhook sender does: HMAC-SHA256, lowercase hex. */
  private static String hmacSha256Hex(String secret, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", error);
    }
  }
}
