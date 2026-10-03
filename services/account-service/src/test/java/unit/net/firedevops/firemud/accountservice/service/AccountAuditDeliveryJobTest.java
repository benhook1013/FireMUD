package net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.LoggingAdminClient;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountAuditDeliveryJobTest {
  private final AccountAuditOutboxRepository outbox =
      Mockito.mock(AccountAuditOutboxRepository.class);
  private final LoggingAdminClient client = Mockito.mock(LoggingAdminClient.class);
  private final AccountAuditDeliveryJob job = new AccountAuditDeliveryJob(outbox, client);

  @Test
  void marksOnlyAnExactReceiverReceiptDelivered() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(50)).thenReturn(List.of(envelope));
    when(client.deliver(envelope))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-1", "log-1", false));

    job.deliverPending();

    verify(outbox).markDelivered(envelope.auditEventId(), "receipt-1", "log-1", false);
    verify(outbox, never()).recordAttempt(envelope.auditEventId());
  }

  @Test
  void minimizedReceiverReceiptMarksSameIdentityTerminal() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(50)).thenReturn(List.of(envelope));
    when(client.deliver(envelope))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-2", "log-2", true));

    job.deliverPending();

    verify(outbox).markDelivered(envelope.auditEventId(), "receipt-2", "log-2", true);
    verify(outbox, never()).recordAttempt(envelope.auditEventId());
  }

  @Test
  void unavailableReceiverKeepsSameEnvelopePending() {
    AccountAuditEnvelope envelope = platformRegistration();
    when(outbox.pending(50)).thenReturn(List.of(envelope));
    when(client.deliver(envelope)).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

    job.deliverPending();

    verify(outbox).recordAttempt(envelope.auditEventId());
    verify(outbox, never()).markDelivered(envelope.auditEventId(), "receipt-1", "log-1", false);
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

  @Test
  void canonicalUuidEnvelopeCanReachTerminalDeliveryWithoutNumericAlias() {
    String payload = "{\"tenant\":\"canonical\"}";
    AccountAuditEnvelope envelope =
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.canonicalTenantV2("33333333-3333-4333-8333-333333333333"),
            "account-service",
            "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
            Instant.parse("2026-10-01T00:00:00Z"),
            1,
            1,
            AccountAuditDigest.ofPayload(payload),
            payload);
    when(outbox.pending(50)).thenReturn(List.of(envelope));
    when(client.deliver(envelope))
        .thenReturn(new LoggingAdminClient.AuditDeliveryResult("receipt-v2", "log-v2", false));

    job.deliverPending();

    verify(outbox).markDelivered(envelope.auditEventId(), "receipt-v2", "log-v2", false);
    assertEquals(2, envelope.tenantIdentityVersion());
    assertNull(envelope.tenantId());
    assertEquals("33333333-3333-4333-8333-333333333333", envelope.tenantUuid().toString());
  }

  private static AccountAuditEnvelope platformRegistration() {
    String payload = "{\"accountId\":1}";
    return new AccountAuditEnvelope(
        UUID.fromString("e9659715-e257-4f88-847e-700653e101d1"),
        "platform",
        AccountAuditTenantIdentity.platformV1(),
        "account-service",
        "ACCOUNT_REGISTERED",
        Instant.parse("2026-09-24T00:00:00Z"),
        1,
        1,
        AccountAuditDigest.ofPayload(payload),
        payload);
  }
}
