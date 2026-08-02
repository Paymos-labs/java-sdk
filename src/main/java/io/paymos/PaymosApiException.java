package io.paymos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A non-success HTTP response returned by the Paymos Merchant API. */
public final class PaymosApiException extends PaymosException {
  /** One field-level entry from the optional Problem Details errors array. */
  public record ProblemError(String code, String field, String message) {}

  private static final long serialVersionUID = 1L;
  private static final ObjectMapper JSON = JsonSupport.MAPPER;
  private final int statusCode;
  private final String body;
  private final String retryAfterHeader;
  private final ObjectNode problem;

  /** Creates an API exception while preserving the response for diagnostics. */
  public PaymosApiException(int statusCode, String body, HttpHeaders headers) {
    this(statusCode, body, headers, parseProblem(statusCode, body));
  }

  private PaymosApiException(int statusCode, String body, HttpHeaders headers, ObjectNode problem) {
    super(message(statusCode, body, problem));
    this.statusCode = statusCode;
    this.body = body;
    this.retryAfterHeader = headers.firstValue("Retry-After").orElse(null);
    this.problem = problem;
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
    return problem;
  }

  /** Returns the authoritative top-level Paymos error code when available. */
  public String code() {
    return problem.path("code").asText("");
  }

  /** Returns the request-level top-level field, when supplied. */
  public String field() {
    JsonNode field = problem.path("field");
    return field.isTextual() ? field.asText() : null;
  }

  public String type() {
    return problem.path("type").asText("");
  }

  public String title() {
    return problem.path("title").asText("");
  }

  public Integer problemStatus() {
    return problem.path("status").isInt() ? problem.path("status").asInt() : null;
  }

  public String detail() {
    return problem.path("detail").asText("");
  }

  public List<ProblemError> errors() {
    JsonNode values = problem.path("errors");
    if (!values.isArray()) return List.of();
    List<ProblemError> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.path("code").isTextual() || !value.path("message").isTextual()) continue;
      JsonNode field = value.path("field");
      result.add(
          new ProblemError(
              value.path("code").asText(),
              field.isTextual() ? field.asText() : null,
              value.path("message").asText()));
    }
    return List.copyOf(result);
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

  private static ObjectNode parseProblem(int status, String body) {
    JsonNode value = parse(body);
    return value.isObject()
            && value.path("type").isTextual()
            && value.path("title").isTextual()
            && value.path("status").isInt()
            && value.path("status").asInt() == status
            && value.path("detail").isTextual()
            && value.path("code").isTextual()
        ? (ObjectNode) value
        : JSON.createObjectNode();
  }

  private static String message(int status, String body, JsonNode value) {
    String detail = value.path("detail").asText("");
    if (detail.isEmpty()) detail = value.path("code").asText("");
    if (detail.isEmpty()) detail = body.isEmpty() ? "empty response" : body;
    return "Paymos API " + status + ": " + detail;
  }
}
