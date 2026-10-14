package io.paymos;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

/**
 * invoice-late-payment wire contract (late-26): the Invoice record decodes paid_late_at (Unix
 * seconds | null | absent legacy) beside an unchanged terminal status and payment.paid, and the
 * simulate stage round-trips verbatim.
 */
public final class PaidLateTest {
  private static final ObjectMapper JSON =
      new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  private static final String EXPIRED =
      "{\"invoice_id\":\"inv_1\",\"project_id\":\"prj_1\",\"status\":\"expired\","
          + "\"is_final\":true,\"is_test\":false,\"payment_url\":\"https://pay.test/inv_1\","
          + "\"order\":{\"external_id\":\"order_1\",\"amount\":\"100.00\",\"currency\":\"USD\"},"
          + "\"payment\":{\"currency\":\"USDT\",\"network\":\"tron\",\"chain_id\":728126428,"
          + "\"expected\":\"100.00\",\"paid\":\"60.00\"},"
          + "\"created_at\":1700000000,\"updated_at\":1700003600,\"expires_at\":1700003600,"
          + "\"completed_at\":1700003600,\"paid_late_at\":1700007200}";

  private Invoice decode(String json) throws Exception {
    return JSON.readValue(json, Invoice.class);
  }

  @Test
  public void decodesUnixMarkBesideTerminalStatus() throws Exception {
    Invoice invoice = decode(EXPIRED);
    assertEquals(InvoiceStatus.EXPIRED, invoice.status());
    assertTrue(invoice.isFinal());
    assertEquals(Long.valueOf(1_700_007_200L), invoice.paidLateAt());
    assertEquals("60.00", invoice.payment().paid());
  }

  @Test
  public void decodesNullMark() throws Exception {
    Invoice invoice =
        decode(EXPIRED.replace("\"paid_late_at\":1700007200", "\"paid_late_at\":null"));
    assertNull(invoice.paidLateAt());
  }

  @Test
  public void keepsLegacyPayloadWithoutTheField() throws Exception {
    Invoice invoice = decode(EXPIRED.replace(",\"paid_late_at\":1700007200", ""));
    assertNull(invoice.paidLateAt());
    assertEquals(InvoiceStatus.EXPIRED, invoice.status());
  }

  @Test
  public void acceptsThePaidLateSimulateStage() throws Exception {
    PaymosClient client = new PaymosClient("pk", "sk", "https://api.paymos.test");
    assertThrows(
        IllegalArgumentException.class, () -> client.invoices.simulatePayment("inv_1", "explode"));
    // paid_late passes the local guard and reaches the transport: with a broken endpoint it must
    // fail with a transport error, NOT the stage guard.
    RuntimeException failure =
        assertThrows(
            RuntimeException.class, () -> client.invoices.simulatePayment("inv_1", "paid_late"));
    assertNotEquals("Invalid simulation stage.", failure.getMessage());
  }
}
