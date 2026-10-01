package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.jooq.tables.records.AccountAuditOutboxRecord;
import org.jooq.DSLContext;
import org.jooq.exception.NoDataFoundException;
import org.junit.jupiter.api.Test;

class AccountAuditOutboxRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountAuditOutboxRepository repository = new AccountAuditOutboxRepository(dsl);

  @Test
  void appendReturnsRefreshedPersistedEnvelope() {
    UUID auditEventId = UUID.randomUUID();
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    String persistedPayload = "{\"persisted\":\"exact material\"}";
    String persistedDigest = AccountAuditDigest.ofPayload(persistedPayload);
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    doAnswer(
            invocation -> {
              when(row.getAuditEventId()).thenReturn(auditEventId);
              when(row.getScope()).thenReturn("tenant");
              when(row.getTenantId()).thenReturn(73L);
              when(row.getProducerService()).thenReturn("account-service");
              when(row.getEventType()).thenReturn("ACCOUNT_MEMBERSHIP_LEFT");
              when(row.getOccurredAt()).thenReturn(persistedOccurredAt);
              when(row.getSchemaVersion()).thenReturn(1);
              when(row.getPayloadDigestVersion()).thenReturn(1);
              when(row.getPayloadDigest()).thenReturn(persistedDigest);
              when(row.getPayload()).thenReturn(persistedPayload);
              return null;
            })
        .when(row)
        .refresh();

    AccountAuditEnvelope envelope =
        repository.append(
            auditEventId,
            "tenant",
            73L,
            "ACCOUNT_MEMBERSHIP_LEFT",
            "{\"requested\":\"in-memory material\"}");

    assertThat(envelope)
        .isEqualTo(
            new AccountAuditEnvelope(
                auditEventId,
                "tenant",
                73L,
                "account-service",
                "ACCOUNT_MEMBERSHIP_LEFT",
                persistedOccurredAt.toInstant(ZoneOffset.UTC),
                1,
                1,
                persistedDigest,
                persistedPayload));
    assertThat(envelope.payloadDigest())
        .isEqualTo(AccountAuditDigest.ofPayload(envelope.payload()));
  }

  @Test
  void appendFailsClosedWhenStoredEnvelopeCannotBeReadBack() {
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    doThrow(new NoDataFoundException("Stored audit envelope is missing")).when(row).refresh();

    assertThatThrownBy(
            () ->
                repository.append(
                    UUID.randomUUID(), "tenant", 73L, "ACCOUNT_MEMBERSHIP_LEFT", "{}"))
        .isInstanceOf(NoDataFoundException.class)
        .hasMessageContaining("Stored audit envelope is missing");
  }
}
