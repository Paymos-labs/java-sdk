package io.paymos;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Canonical request encoding and HMAC-SHA256 signing helpers. */
public final class RequestSigner {
  private RequestSigner() {}

  /** Builds the exact canonical value signed by Merchant API requests. */
  public static String stringToSign(
      String timestamp, String method, String path, String query, String body) {
    String hash =
        body.isEmpty()
            ? ""
            : HexFormat.of().formatHex(sha256(body.getBytes(StandardCharsets.UTF_8)));
    return timestamp
        + "\n"
        + method.toUpperCase(Locale.ROOT)
        + "\n"
        + path
        + "\n"
        + query
        + "\n"
        + hash;
  }

  /** Computes a base64-encoded HMAC-SHA256 signature. */
  public static String sign(String secret, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", error);
    }
  }

  /** Builds the complete Merchant API Authorization header. */
  public static String authorizationHeader(
      String apiKey,
      String apiSecret,
      String timestamp,
      String method,
      String path,
      String query,
      String body) {
    return "HMAC-SHA256 "
        + apiKey
        + ":"
        + sign(apiSecret, stringToSign(timestamp, method, path, query, body));
  }

  /** RFC 3986-encodes one URL path segment. */
  public static String encodePathSegment(String value) {
    return rfc3986(value);
  }

  /** Builds a deterministic, sorted RFC 3986 query string. */
  public static String buildQuery(Map<String, ?> filters) {
    List<String> parts = new ArrayList<>();
    filters.keySet().stream()
        .sorted()
        .forEach(
            key -> {
              Object raw = filters.get(key);
              if (raw == null) return;
              List<String> values = new ArrayList<>();
              if (raw instanceof Collection<?> collection) {
                collection.forEach(value -> values.add(queryValue(key, value)));
              } else if (supportedQueryValue(raw)) {
                values.add(String.valueOf(raw));
              } else throw new IllegalArgumentException("Unsupported Paymos list filter: " + key);
              if (values.isEmpty())
                throw new IllegalArgumentException("Paymos list filter cannot be empty: " + key);
              Collections.sort(values);
              for (String value : values) {
                if (value.isEmpty())
                  throw new IllegalArgumentException("Paymos list filter cannot be empty: " + key);
                parts.add(rfc3986(key) + "=" + rfc3986(value));
              }
            });
    return parts.isEmpty() ? "" : "?" + String.join("&", parts);
  }

  static String buildQuery(InvoiceListOptions options) {
    return buildQuery(options == null ? Map.of() : options.query());
  }

  static String buildQuery(WithdrawalListOptions options) {
    return buildQuery(options == null ? Map.of() : options.query());
  }

  private static String queryValue(String key, Object value) {
    if (!supportedQueryValue(value))
      throw new IllegalArgumentException("Unsupported Paymos list filter: " + key);
    return String.valueOf(value);
  }

  private static boolean supportedQueryValue(Object value) {
    return value instanceof String
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigInteger
        || value instanceof InvoiceStatus
        || value instanceof WithdrawalStatus;
  }

  private static String rfc3986(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length);
    for (byte raw : bytes) {
      int unsigned = raw & 0xff;
      if ((unsigned >= 'a' && unsigned <= 'z')
          || (unsigned >= 'A' && unsigned <= 'Z')
          || (unsigned >= '0' && unsigned <= '9')
          || unsigned == '-'
          || unsigned == '.'
          || unsigned == '_'
          || unsigned == '~') {
        encoded.append((char) unsigned);
      } else {
        encoded.append('%');
        encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
        encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
      }
    }
    return encoded.toString();
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
