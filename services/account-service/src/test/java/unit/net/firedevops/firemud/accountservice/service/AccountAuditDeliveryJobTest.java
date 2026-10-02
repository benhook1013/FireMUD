package net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Scheduled;

class AccountAuditDeliveryJobTest {
  private final AccountAuditOutboxRepository outbox =
      Mockito.mock(AccountAuditOutboxRepository.class);
  private final LoggingAdminClient client = Mockito.mock(LoggingAdminClient.class);
  private final AccountAuditDeliveryJob job = new AccountAuditDeliveryJob(outbox, client);

  @Test
  void deliveryRemainsDirectlyCallableButIsNotAutomaticallyScheduled()
      throws NoSuchMethodException {
    assertNull(
        AccountAuditDeliveryJob.class.getMethod("deliverPending").getAnnotation(Scheduled.class));
  }

  @Test
  void marksOnlyAnExactReceiverReceiptDelivered() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(eq(50), Mockito.any(Instant.class))).thenReturn(List.of(envelope));
    when(client.deliver(envelope))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-1", "log-1", false));

    job.deliverPending();

    verify(outbox).markDelivered(envelope.auditEventId(), "receipt-1", "log-1", false);
    verify(outbox, never()).recordAttempt(envelope.auditEventId());
  }

  @Test
  void minimizedReceiverReceiptMarksSameIdentityTerminal() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(eq(50), Mockito.any(Instant.class))).thenReturn(List.of(envelope));
    when(client.deliver(envelope))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-2", true));

    job.deliverPending();

    verify(outbox).markDelivered(envelope.auditEventId(), "receipt-2", "log-2", true);
    verify(outbox, never()).recordAttempt(envelope.auditEventId());
  }

  @Test
  void unavailableReceiverKeepsSameEnvelopePending() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(eq(50), Mockito.any(Instant.class))).thenReturn(List.of(envelope));
    when(client.deliver(envelope)).thenThrow(new IllegalStateException("unavailable"));

    job.deliverPending();

    verify(outbox).recordAttempt(envelope.auditEventId());
    verify(outbox, never()).markDelivered(envelope.auditEventId(), "receipt-1", "log-1", false);
  }

  @Test
  void recordAttemptFailureDoesNotPreventLaterEnvelopesFromDelivery() {
    AccountAuditEnvelope failed = platformRegistration();
    AccountAuditEnvelope later =
        platformRegistration(UUID.fromString("4cb5f750-6d75-4eaa-b0b4-2a9a285e2c32"));
    when(outbox.pending(eq(50), Mockito.any(Instant.class))).thenReturn(List.of(failed, later));
    when(client.deliver(failed)).thenThrow(new IllegalStateException("receiver unavailable"));
    when(client.deliver(later))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-2", false));
    Mockito.doThrow(new IllegalStateException("outbox unavailable"))
        .when(outbox)
        .recordAttempt(failed.auditEventId());

    job.deliverPending();

    verify(outbox, never())
        .markDelivered(eq(failed.auditEventId()), anyString(), anyString(), anyBoolean());
    verify(client).deliver(later);
    verify(outbox).markDelivered(later.auditEventId(), "receipt-2", "log-2", false);
  }

  @Test
  void digestBindsExactUtf8PayloadBytes() {
    assertEquals(
        "sha256:eee0f882a1fae6d1435a03d9e9508a3972f0c1f10495b4e3d5e388cebced63ef",
        AccountAuditDigest.ofPayload("{\"accountId\":1}"));
    assertNotEquals(
        AccountAuditDigest.ofPayload("{\"accountId\":1}"),
        AccountAuditDigest.ofPayload("{\"accountId\": 1}"));
  }

  private static AccountAuditEnvelope platformRegistration() {
    return platformRegistration(UUID.fromString("e9659715-e257-4f88-847e-700653e101d1"));
  }

  private static AccountAuditEnvelope platformRegistration(UUID auditEventId) {
    String payload = "{\"accountId\":1}";
    return new AccountAuditEnvelope(
        auditEventId,
        "platform",
        null,
        "account-service",
        "ACCOUNT_REGISTERED",
        Instant.parse("2026-09-24T00:00:00Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }
}
