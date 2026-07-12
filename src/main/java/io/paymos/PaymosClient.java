package io.paymos;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** HMAC-authenticated client for all Paymos Merchant API resources. */
public final class PaymosClient {
  /** Injectable HTTP boundary used for testing and custom transports. */
  public interface Transport {
    /** Sends one fully prepared HTTP request. */
    Response send(
        String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout)
        throws Exception;
  }

  /** Transport response preserving status, body, and multi-value headers. */
  public record Response(int status, String body, Map<String, List<String>> headers) {
    public Response {
      Objects.requireNonNull(body, "Response body is required");
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }

  private static final ObjectMapper JSON = JsonSupport.MAPPER;
  private final String apiKey;
  private final String apiSecret;
  private final String baseUrl;
  private final Transport transport;
  private final Clock clock;
  private final int maxRetries;
  private final Duration baseDelay;
  private final Duration timeout;

  /** Invoice operations. */
  public final Invoices invoices;

  /** Withdrawal operations. */
  public final Withdrawals withdrawals;

  /** Balance operations. */
  public final Balances balances;

  /** Merchant API system operations. */
  public final SystemResource system;

  /** Creates a client using the production base URL and safe retry defaults. */
  public PaymosClient(String apiKey, String apiSecret) {
    this(
        apiKey,
        apiSecret,
        "https://api.paymos.io",
        defaultTransport(),
        Clock.systemUTC(),
        2,
        Duration.ofMillis(150),
        Duration.ofSeconds(30));
  }

  /** Creates a client with explicit transport, clock, retry, and timeout options. */
  public PaymosClient(
      String apiKey,
      String apiSecret,
      String baseUrl,
      Transport transport,
      Clock clock,
      int maxRetries,
      Duration baseDelay,
      Duration timeout) {
    require(apiKey, "API key");
    require(apiSecret, "API secret");
    require(baseUrl, "Base URL");
    URI parsedBase = URI.create(baseUrl);
    if (!parsedBase.isAbsolute()
        || parsedBase.getHost() == null
        || parsedBase.getRawQuery() != null
        || parsedBase.getRawFragment() != null
        || !(parsedBase.getRawPath().isEmpty() || parsedBase.getRawPath().equals("/")))
      throw new IllegalArgumentException("Base URL must be an absolute origin without a path");
    if (maxRetries < 0 || maxRetries > 10)
      throw new IllegalArgumentException("maxRetries must be between 0 and 10");
    if (baseDelay == null || baseDelay.isNegative())
      throw new IllegalArgumentException("Base retry delay cannot be negative");
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Timeout must be positive");

    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.transport = Objects.requireNonNull(transport, "Transport is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    this.maxRetries = maxRetries;
    this.baseDelay = baseDelay;
    this.timeout = timeout;
    invoices = new Invoices();
    withdrawals = new Withdrawals();
    balances = new Balances();
    system = new SystemResource();
  }

  private <T> T request(
      String method, String path, String query, Object payload, JavaType responseType) {
    final String body;
    try {
      body = payload == null ? "" : JSON.writeValueAsString(payload);
    } catch (Exception error) {
      throw new PaymosException("Paymos request payload cannot be serialized", error);
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      String timestamp = Long.toString(clock.instant().getEpochSecond());
      Map<String, String> headers =
          Map.of(
              "Authorization",
              RequestSigner.authorizationHeader(
                  apiKey, apiSecret, timestamp, method, path, query, body),
              "X-Request-Timestamp",
              timestamp,
              "Content-Type",
              "application/json",
              "Accept",
              "application/json",
              "User-Agent",
              "paymos-java/" + SdkVersion.VALUE);

      Response response;
      try {
        response =
            transport.send(method, URI.create(baseUrl + path + query), headers, bytes, timeout);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new PaymosException("Paymos request interrupted", error);
      } catch (Exception error) {
        if (attempt < maxRetries && safe(method)) {
          sleep(backoff(attempt));
          continue;
        }
        throw new PaymosException("Paymos request failed", error);
      }

      if (attempt < maxRetries
          && (response.status == 429 || (response.status >= 500 && safe(method)))) {
        sleep(retryDelay(response.headers, attempt));
        continue;
      }
      if (response.status < 200 || response.status >= 300)
        throw new PaymosApiException(
            response.status, response.body, HttpHeaders.of(response.headers, (a, b) -> true));
      if (response.body.isEmpty())
        throw new PaymosException("Paymos API returned an empty response");

      try {
        return JSON.readValue(response.body, responseType);
      } catch (Exception error) {
        throw new PaymosException("Paymos API returned invalid JSON", error);
      }
    }
    throw new IllegalStateException("Unreachable retry state");
  }

  private Duration retryDelay(Map<String, List<String>> headers, int attempt) {
    String value =
        headers.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase("Retry-After"))
            .flatMap(entry -> entry.getValue().stream())
            .findFirst()
            .orElse("");
    try {
      long seconds = Long.parseLong(value);
      if (seconds >= 0) return Duration.ofSeconds(seconds);
    } catch (NumberFormatException | ArithmeticException ignored) {
      // Retry-After may instead be an RFC 7231 HTTP date.
    }
    try {
      Duration delay =
          Duration.between(
              clock.instant(),
              ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
      return delay.isNegative() ? Duration.ZERO : delay;
    } catch (DateTimeParseException ignored) {
      return backoff(attempt);
    }
  }

  private Duration backoff(int attempt) {
    return baseDelay.multipliedBy(1L << attempt);
  }

  private static boolean safe(String method) {
    return Set.of("GET", "HEAD", "OPTIONS").contains(method.toUpperCase(Locale.ROOT));
  }

  private static void sleep(Duration value) {
    try {
      Thread.sleep(value.toMillis());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new PaymosException("Paymos retry interrupted", error);
    }
  }

  private static Transport defaultTransport() {
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    return (method, uri, headers, body, timeout) -> {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
      headers.forEach(builder::header);
      builder.method(
          method,
          body.length == 0
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(), response.body(), response.headers().map());
    };
  }

  /** Invoice resource operations. */
  public final class Invoices {
    /** Creates an invoice. */
    public Invoice create(CreateInvoiceRequest payload) {
      return request(
          "POST", "/v1/invoices", "", Objects.requireNonNull(payload), type(Invoice.class));
    }

    /** Gets an invoice by ID. */
    public Invoice get(String id) {
      id(id);
      return request(
          "GET",
          "/v1/invoices/" + RequestSigner.encodePathSegment(id),
          "",
          null,
          type(Invoice.class));
    }

    /** Lists one cursor page of invoices without filters. */
    public Page<InvoiceListItem> list() {
      return list(InvoiceListOptions.empty());
    }

    /** Lists one cursor page of invoices. */
    public Page<InvoiceListItem> list(InvoiceListOptions options) {
      JavaType pageType =
          JSON.getTypeFactory().constructParametricType(Page.class, InvoiceListItem.class);
      return request("GET", "/v1/invoices", RequestSigner.buildQuery(options), null, pageType);
    }

    /** Iterates invoice items with a strict maximum-page bound. */
    public Iterable<InvoiceListItem> iterate(InvoiceListOptions options, int maxPages) {
      InvoiceListOptions initial = options == null ? InvoiceListOptions.empty() : options;
      return cursor(
          this::list,
          initial,
          InvoiceListOptions::cursor,
          InvoiceListOptions::withCursor,
          maxPages);
    }

    /** Cancels an invoice with an audit reason. */
    public Invoice cancel(String id, String reason) {
      id(id);
      reason(reason);
      return request(
          "POST",
          "/v1/invoices/" + RequestSigner.encodePathSegment(id) + "/cancel",
          "",
          Map.of("reason", reason),
          type(Invoice.class));
    }

    /** Confirms a currency and network for a white-label payment flow. */
    public Invoice confirmPayment(String id, String currency, String network) {
      id(id);
      require(currency, "Currency");
      require(network, "Network");
      return request(
          "POST",
          "/v1/invoices/" + RequestSigner.encodePathSegment(id) + "/confirm-payment",
          "",
          Map.of("currency", currency, "network", network),
          type(Invoice.class));
    }

    /** Simulates a sandbox invoice payment stage. */
    public Invoice simulatePayment(String id, String stage) {
      id(id);
      if (!Set.of("paid", "overpaid", "underpay", "cancel").contains(stage))
        throw new IllegalArgumentException("Invalid stage");
      return request(
          "POST",
          "/v1/sandbox/invoices/" + RequestSigner.encodePathSegment(id) + "/simulate-payment",
          "",
          Map.of("stage", stage),
          type(Invoice.class));
    }
  }

  /** Withdrawal resource operations. */
  public final class Withdrawals {
    /** Creates a withdrawal. */
    public Withdrawal create(CreateWithdrawalRequest payload) {
      return request(
          "POST", "/v1/withdrawals", "", Objects.requireNonNull(payload), type(Withdrawal.class));
    }

    /** Gets a withdrawal by ID. */
    public Withdrawal get(String id) {
      id(id);
      return request(
          "GET",
          "/v1/withdrawals/" + RequestSigner.encodePathSegment(id),
          "",
          null,
          type(Withdrawal.class));
    }

    /** Lists one cursor page of withdrawals without filters. */
    public Page<Withdrawal> list() {
      return list(WithdrawalListOptions.empty());
    }

    /** Lists one cursor page of withdrawals. */
    public Page<Withdrawal> list(WithdrawalListOptions options) {
      JavaType pageType =
          JSON.getTypeFactory().constructParametricType(Page.class, Withdrawal.class);
      return request("GET", "/v1/withdrawals", RequestSigner.buildQuery(options), null, pageType);
    }

    /** Iterates withdrawal items with a strict maximum-page bound. */
    public Iterable<Withdrawal> iterate(WithdrawalListOptions options, int maxPages) {
      WithdrawalListOptions initial = options == null ? WithdrawalListOptions.empty() : options;
      return cursor(
          this::list,
          initial,
          WithdrawalListOptions::cursor,
          WithdrawalListOptions::withCursor,
          maxPages);
    }

    /** Cancels a withdrawal with an audit reason. */
    public Withdrawal cancel(String id, String reason) {
      id(id);
      reason(reason);
      return request(
          "POST",
          "/v1/withdrawals/" + RequestSigner.encodePathSegment(id) + "/cancel",
          "",
          Map.of("reason", reason),
          type(Withdrawal.class));
    }

    /** Simulates completion of a sandbox withdrawal. */
    public Withdrawal simulateCompletion(String id) {
      id(id);
      return request(
          "POST",
          "/v1/sandbox/withdrawals/" + RequestSigner.encodePathSegment(id) + "/simulate-completion",
          "",
          null,
          type(Withdrawal.class));
    }
  }

  /** Balance resource operations. */
  public final class Balances {
    /** Gets available balances grouped by currency. Requires a Payout key. */
    public List<Balance> get() {
      JavaType listType = JSON.getTypeFactory().constructCollectionType(List.class, Balance.class);
      return request("GET", "/v1/balances", "", null, listType);
    }
  }

  /** System resource operations. */
  public final class SystemResource {
    /** Gets authenticated server time. */
    public ServerTime time() {
      return request("GET", "/v1/time", "", null, type(ServerTime.class));
    }
  }

  private static JavaType type(Class<?> value) {
    return JSON.getTypeFactory().constructType(value);
  }

  private static void id(String value) {
    require(value, "Resource ID");
  }

  private static void reason(String value) {
    if (value == null || value.isBlank() || value.length() > 500)
      throw new IllegalArgumentException("Cancellation reason must contain 1 to 500 characters");
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
  }

  private interface PageFetcher<T, O> {
    Page<T> fetch(O options);
  }

  private interface CursorReader<O> {
    String read(O options);
  }

  private interface CursorWriter<O> {
    O write(O options, String cursor);
  }

  private static <T, O> Iterable<T> cursor(
      PageFetcher<T, O> fetch,
      O initial,
      CursorReader<O> readCursor,
      CursorWriter<O> writeCursor,
      int maxPages) {
    if (maxPages < 1) throw new IllegalArgumentException("maxPages must be positive");
    return () ->
        new Iterator<>() {
          private O options = initial;
          private Iterator<T> items = Collections.emptyIterator();
          private String cursor = readCursor.read(initial);
          private int pages;
          private boolean done;

          @Override
          public boolean hasNext() {
            while (!items.hasNext() && !done && pages < maxPages) {
              Page<T> page = fetch.fetch(options);
              pages++;
              items = page.items().iterator();
              String next = page.nextCursor();
              if (next == null || next.isEmpty()) {
                done = true;
              } else {
                if (next.equals(cursor))
                  throw new PaymosException("Paymos API returned the same pagination cursor twice");
                cursor = next;
                options = writeCursor.write(options, cursor);
              }
            }
            return items.hasNext();
          }

          @Override
          public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            return items.next();
          }
        };
  }
}
