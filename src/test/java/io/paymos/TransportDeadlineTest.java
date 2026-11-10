package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class TransportDeadlineTest {
  @Test
  void partialBodyTimesOutAndCancelsOriginalHttpFuture() {
    StubClient client = new StubClient();
    assertThrows(
        HttpTimeoutException.class,
        () ->
            PaymosClient.transport(client)
                .send(
                    "GET",
                    URI.create("https://api.paymos.test/v1/time"),
                    Map.of(),
                    new byte[0],
                    Duration.ofMillis(20)));
    assertTrue(client.headersDelivered);
    assertTrue(client.pending.isCancelled(), "The underlying request must not keep running");
  }

  @Test
  void interruptionCancelsOriginalRequest() {
    StubClient client = new StubClient();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedException.class,
          () ->
              PaymosClient.transport(client)
                  .send(
                      "GET",
                      URI.create("https://api.paymos.test/v1/time"),
                      Map.of(),
                      new byte[0],
                      Duration.ofSeconds(1)));
      assertTrue(client.pending.isCancelled());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void transportFailureIsUnwrapped() {
    StubClient client = new StubClient();
    IOException failure = new IOException("connection lost");
    client.pending.completeExceptionally(failure);
    assertSame(
        failure,
        assertThrows(
            IOException.class,
            () ->
                PaymosClient.transport(client)
                    .send(
                        "GET",
                        URI.create("https://api.paymos.test/v1/time"),
                        Map.of(),
                        new byte[0],
                        Duration.ofSeconds(1))));
  }

  private static final class StubClient extends HttpClient {
    final CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
    boolean headersDelivered;

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      HttpResponse.BodySubscriber<T> subscriber =
          handler.apply(
              new HttpResponse.ResponseInfo() {
                public int statusCode() {
                  return 200;
                }

                public HttpHeaders headers() {
                  return HttpHeaders.of(Map.of("Content-Length", List.of("50")), (a, b) -> true);
                }

                public Version version() {
                  return Version.HTTP_1_1;
                }
              });
      subscriber.onSubscribe(
          new Flow.Subscription() {
            public void request(long count) {}

            public void cancel() {}
          });
      subscriber.onNext(
          List.of(
              ByteBuffer.wrap(
                  "{\"server_time\":".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      headersDelivered = true;
      // Headers and a partial body arrived, but the complete-response future stays pending.
      assertFalse(subscriber.getBody().toCompletableFuture().isDone());
      return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new AssertionError("Synchronous send bypasses the deadline");
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request,
        HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> push) {
      return sendAsync(request, handler);
    }
  }
}
