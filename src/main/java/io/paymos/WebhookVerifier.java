package io.paymos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies timestamped Paymos webhooks against the exact raw request body. */
public final class WebhookVerifier {
  private static final ObjectMapper JSON = JsonSupport.MAPPER;
  private final byte[] secret;
  private final Duration tolerance;

  /** Creates a verifier with a five-minute replay tolerance. */
  public WebhookVerifier(String secret) {
    this(secret, Duration.ofMinutes(5));
  }

  /** Creates a verifier with an explicit replay tolerance. */
  public WebhookVerifier(String secret, Duration tolerance) {
    if (secret == null || secret.isBlank())
      throw new IllegalArgumentException("Webhook secret is required");
    if (tolerance == null || tolerance.isNegative())
      throw new IllegalArgumentException("Tolerance cannot be negative");
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.tolerance = tolerance;
  }

  /** Returns whether the signature and timestamp are valid. */
  public boolean verify(String header, byte[] body, Instant now) {
    try {
      assertValid(header, body, now);
      return true;
    } catch (PaymosException error) {
      return false;
    }
  }

  /** Validates the signature and throws when it is invalid or stale. */
  public void assertValid(String header, byte[] body, Instant now) {
    Objects.requireNonNull(body, "Raw webhook body is required");
    Objects.requireNonNull(now, "Current time is required");
    Parsed parsed = parse(header);
    if (Duration.between(Instant.ofEpochSecond(parsed.timestamp), now).abs().compareTo(tolerance)
        > 0) throw new WebhookTimestampException("Webhook timestamp is outside tolerance");

    byte[] expected = hmac((parsed.timestamp + ".").getBytes(StandardCharsets.US_ASCII), body);
    for (String value : parsed.signatures) {
      try {
        if (value.length() == 64 && MessageDigest.isEqual(expected, HexFormat.of().parseHex(value)))
          return;
      } catch (IllegalArgumentException ignored) {
        // Try every v1 signature to support secret rotation.
      }
    }
    throw new WebhookSignatureException("Webhook signature does not match payload");
  }

  /** Validates and parses the webhook envelope while leaving data as a JSON tree. */
  public WebhookEvent<JsonNode> constructEvent(String header, byte[] body, Instant now) {
    return constructEvent(header, body, now, JsonNode.class);
  }

  /** Validates and parses the webhook envelope into a caller-selected data type. */
  public <T> WebhookEvent<T> constructEvent(
      String header, byte[] body, Instant now, Class<T> dataType) {
    assertValid(header, body, now);
    Objects.requireNonNull(dataType, "Webhook data type is required");
    try {
      JsonNode root = JSON.readTree(body);
      if (root == null
          || !root.isObject()
          || !text(root, "event_id")
          || !text(root, "event_type")
          || !root.path("version").isIntegralNumber()
          || !root.path("version").canConvertToInt()
          || root.path("version").asInt() < 1
          || !root.path("occurred_at").isIntegralNumber()
          || !root.path("occurred_at").canConvertToLong()
          || root.path("occurred_at").asLong() < 0
          || !root.path("data").isObject())
        throw new PaymosException("Webhook event envelope is invalid");

      return new WebhookEvent<>(
          root.path("event_id").asText(),
          root.path("event_type").asText(),
          root.path("version").asInt(),
          root.path("occurred_at").asLong(),
          JSON.treeToValue(root.path("data"), dataType));
    } catch (PaymosException error) {
      throw error;
    } catch (Exception error) {
      throw new PaymosException("Webhook payload is invalid JSON", error);
    }
  }

  private static boolean text(JsonNode root, String name) {
    JsonNode value = root.path(name);
    return value.isTextual() && !value.asText().isBlank();
  }

  private byte[] hmac(byte[] prefix, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(prefix);
      return mac.doFinal(body);
    } catch (Exception error) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", error);
    }
  }

  private static Parsed parse(String header) {
    if (header == null || header.isBlank())
      throw new WebhookSignatureException("Webhook signature header is malformed");

    Long timestamp = null;
    List<String> signatures = new ArrayList<>();
    for (String part : header.split(",")) {
      String[] pair = part.trim().split("=", 2);
      if (pair.length != 2) continue;
      if (pair[0].equals("t")) {
        if (timestamp != null)
          throw new WebhookSignatureException("Webhook signature header is malformed");
        try {
          timestamp = Long.parseLong(pair[1]);
          if (timestamp < 0)
            throw new WebhookSignatureException("Webhook signature header is malformed");
        } catch (NumberFormatException error) {
          throw new WebhookSignatureException("Webhook signature header is malformed");
        }
      } else if (pair[0].equals("v1") && !pair[1].isEmpty()) {
        signatures.add(pair[1]);
      }
    }
    if (timestamp == null || signatures.isEmpty())
      throw new WebhookSignatureException("Webhook signature header is malformed");
    return new Parsed(timestamp, List.copyOf(signatures));
  }

  private record Parsed(long timestamp, List<String> signatures) {}
}
