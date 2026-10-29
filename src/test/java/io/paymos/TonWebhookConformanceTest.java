package io.paymos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class TonWebhookConformanceTest {
  @Test
  void signedTonWebhookRawAndFriendlyV1PreserveFields() throws Exception {
    JsonNode contract =
        new ObjectMapper().readTree(Files.readString(Path.of("conformance/contract.json")));
    JsonNode fixtures = contract.path("vectors").path("ton_webhooks");
    JsonNode vectors = fixtures.path("vectors");
    assertEquals(8, vectors.size());
    WebhookVerifier verifier = new WebhookVerifier(fixtures.path("secret").asText());
    Instant now = Instant.ofEpochSecond(fixtures.path("now").asLong());
    for (JsonNode vector : vectors) {
      assertEquals(1, vector.path("version").asInt());
      String body = vector.path("raw_body").asText();
      String header = vector.path("header").asText();
      WebhookEvent<JsonNode> event =
          verifier.constructEvent(header, body.getBytes(StandardCharsets.UTF_8), now);
      assertEquals(vector.path("event_id").asText(), event.eventId());
      assertEquals(vector.path("event_type").asText(), event.eventType());
      assertEquals(vector.path("version").asInt(), event.version());
      assertEquals(fixtures.path("now").asLong(), event.occurredAt());
      for (JsonNode expected : vector.path("expect")) {
        JsonNode value = event.data();
        for (JsonNode part : expected.path("path")) {
          value = value.path(part.asText());
        }
        assertEquals(expected.path("value"), value, vector.path("name").asText());
      }
      assertFalse(verifier.verify(header, (body + " ").getBytes(StandardCharsets.UTF_8), now));
    }
  }
}
