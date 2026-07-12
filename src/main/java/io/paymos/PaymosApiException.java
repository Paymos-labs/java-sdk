package io.paymos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Optional;

/** A non-success HTTP response returned by the Paymos Merchant API. */
public final class PaymosApiException extends PaymosException {
  private static final long serialVersionUID = 1L;
  private static final ObjectMapper JSON = JsonSupport.MAPPER;
  private final int statusCode;
  private final String body;
  private final String retryAfterHeader;

  /** Creates an API exception while preserving the response for diagnostics. */
  public PaymosApiException(int statusCode, String body, HttpHeaders headers) {
    super(message(statusCode, body));
    this.statusCode = statusCode;
    this.body = body;
    this.retryAfterHeader = headers.firstValue("Retry-After").orElse(null);
  }

  /** Returns the HTTP status code. */
  public int statusCode() {
    return statusCode;
  }

  /** Returns the unmodified response body. */
  public String responseBody() {
    return body;
  }

  /** Returns parsed RFC 9457-style problem details, or an empty object. */
  public JsonNode problem() {
    return parse(body);
  }

  /** Returns the first stable Paymos error code when available. */
  public String code() {
    JsonNode problem = problem();
    JsonNode errors = problem.path("errors");
    return errors.isArray() && !errors.isEmpty()
        ? errors.get(0).path("code").asText("")
        : problem.path("code").asText("");
  }

  /** Returns the field associated with the first validation error. */
  public String field() {
    JsonNode problem = problem();
    JsonNode errors = problem.path("errors");
    JsonNode field =
        errors.isArray() && !errors.isEmpty() ? errors.get(0).path("field") : problem.path("field");
    return field.isTextual() ? field.asText() : null;
  }

  /** Classifies the status using the cross-language SDK error contract. */
  public String kind() {
    return switch (statusCode) {
      case 400 -> "validation";
      case 401, 403 -> "authentication";
      case 404 -> "not_found";
      case 409 -> "conflict";
      case 410 -> "gone";
      case 429 -> "rate_limit";
      case 503 -> "unavailable";
      default -> statusCode >= 500 ? "server" : "api";
    };
  }

  /** Returns a numeric Retry-After delay when the response supplied one. */
  public Optional<Duration> retryAfter() {
    if (retryAfterHeader == null) return Optional.empty();
    try {
      return Optional.of(Duration.ofSeconds(Long.parseLong(retryAfterHeader)));
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception ignored) {
      return JSON.createObjectNode();
    }
  }

  private static String message(int status, String body) {
    JsonNode value = parse(body);
    String detail = value.path("detail").asText("");
    if (detail.isEmpty()) detail = value.path("code").asText("");
    if (detail.isEmpty()) detail = body.isEmpty() ? "empty response" : body;
    return "Paymos API " + status + ": " + detail;
  }
}
