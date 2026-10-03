package net.firedevops.firemud.accountservice.repository;

import static net.firedevops.firemud.accountservice.jooq.tables.AccountAuditOutbox.ACCOUNT_AUDIT_OUTBOX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuditDigest;
import net.firedevops.firemud.accountservice.dto.AccountAuditEnvelope;
import net.firedevops.firemud.accountservice.dto.AccountAuditTenantIdentity;
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
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    String persistedDigest =
        arrangePersistedTenantRow(row, auditEventId, persistedOccurredAt, persistedPayload);

    AccountAuditEnvelope envelope =
        repository.append(auditEventId, "tenant", 73L, "ACCOUNT_MEMBERSHIP_LEFT", persistedPayload);

    assertThat(envelope)
        .isEqualTo(
            new AccountAuditEnvelope(
                auditEventId,
                "tenant",
                AccountAuditTenantIdentity.retainedTenantV1(73L),
                "account-service",
                "ACCOUNT_MEMBERSHIP_LEFT",
                persistedOccurredAt.toInstant(ZoneOffset.UTC),
                1,
                1,
                persistedDigest,
                persistedPayload));
    assertThat(envelope.payloadDigest())
        .isEqualTo(AccountAuditDigest.ofPayload(envelope.payload()));
    verify(row).setTenantIdentityVersion(1);
    verify(row).setTenantUuid(null);
  }

  @Test
  void appendRejectsChangedStoredPayloadAndDigest() {
    UUID auditEventId = UUID.randomUUID();
    AccountAuditOutboxRecord row = mock(AccountAuditOutboxRecord.class);
    when(dsl.newRecord(ACCOUNT_AUDIT_OUTBOX)).thenReturn(row);
    LocalDateTime persistedOccurredAt = LocalDateTime.parse("2026-10-01T12:34:56.123456");
    arrangePersistedTenantRow(
        row, auditEventId, persistedOccurredAt, "{\"stored\":\"different material\"}");

    assertThatThrownBy(
            () ->
                repository.append(
                    auditEventId,
                    "tenant",
                    73L,
                    "ACCOUNT_MEMBERSHIP_LEFT",
                    "{\"requested\":\"original material\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not preserve its exact envelope");
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

  @Test
  void canonicalAppendRequiresAnActiveReadWriteOwnerTransactionBeforeStorage() {
    assertThatThrownBy(
            () ->
                repository.appendCanonicalTenant(
                    UUID.randomUUID(),
                    "33333333-3333-4333-8333-333333333333",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active read-write owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void canonicalReadbackRequiresAnActiveReadWriteOwnerTransactionBeforeStorage() {
    String payload = "{}";
    AccountAuditEnvelope expected =
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.canonicalTenantV2("33333333-3333-4333-8333-333333333333"),
            "account-service",
            "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
            java.time.Instant.parse("2026-10-01T00:00:00Z"),
            1,
            1,
            AccountAuditDigest.ofPayload(payload),
            payload);

    assertThatThrownBy(() -> repository.findExactCanonicalTenantEnvelopeForUpdate(expected))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active read-write owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void canonicalIdentityRejectsMissingMixedUnsupportedNilAndNoncanonicalValues() {
    assertThatThrownBy(() -> AccountAuditTenantIdentity.canonicalTenantV2(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "tenant",
                    null,
                    "account-service",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    AccountAuditDigest.ofPayload("{}"),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountAuditTenantIdentity.canonicalTenantV2(
                    "00000000-0000-0000-0000-000000000000"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountAuditTenantIdentity.canonicalTenantV2(
                    "33333333-3333-4333-8333-33333333333A"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccountAuditTenantIdentity(3, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditTenantIdentity(
                    AccountAuditTenantIdentity.VERSION_2,
                    73L,
                    UUID.fromString("33333333-3333-4333-8333-333333333333")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "platform",
                    AccountAuditTenantIdentity.retainedTenantV1(73L),
                    "account-service",
                    "ACCOUNT_REGISTERED",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    AccountAuditDigest.ofPayload("{}"),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountAuditEnvelope(
                    UUID.randomUUID(),
                    "tenant",
                    AccountAuditTenantIdentity.canonicalTenantV2(
                        "33333333-3333-4333-8333-333333333333"),
                    "account-service",
                    "ACCOUNT_JOINED_PUBLIC_PRODUCTION",
                    java.time.Instant.parse("2026-10-01T00:00:00Z"),
                    1,
                    1,
                    "sha256:" + "0".repeat(64),
                    "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("digest");
  }

  @Test
  void retainedIdentityIsExplicitVersionOneAndDigestRejectsMalformedUnicode() {
    AccountAuditEnvelope retained =
        new AccountAuditEnvelope(
            UUID.randomUUID(),
            "tenant",
            AccountAuditTenantIdentity.retainedTenantV1(73L),
            "account-service",
            "ACCOUNT_MEMBERSHIP_LEFT",
            java.time.Instant.parse("2026-10-01T00:00:00Z"),
            1,
            1,
            AccountAuditDigest.ofPayload("{}"),
            "{}");

    assertThat(retained.tenantIdentityVersion()).isEqualTo(1);
    assertThat(retained.tenantId()).isEqualTo(73L);
    assertThat(retained.tenantUuid()).isNull();
    assertThatThrownBy(() -> AccountAuditDigest.ofPayload(String.valueOf((char) 0xD800)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");
  }

  private static String arrangePersistedTenantRow(
      AccountAuditOutboxRecord row, UUID auditEventId, LocalDateTime occurredAt, String payload) {
    String digest = AccountAuditDigest.ofPayload(payload);
    doAnswer(
            invocation -> {
              when(row.getAuditEventId()).thenReturn(auditEventId);
              when(row.getScope()).thenReturn("tenant");
              when(row.getTenantId()).thenReturn(73L);
              when(row.getTenantIdentityVersion()).thenReturn(1);
              when(row.getTenantUuid()).thenReturn(null);
              when(row.getProducerService()).thenReturn("account-service");
              when(row.getEventType()).thenReturn("ACCOUNT_MEMBERSHIP_LEFT");
              when(row.getOccurredAt()).thenReturn(occurredAt);
              when(row.getSchemaVersion()).thenReturn(1);
              when(row.getPayloadDigestVersion()).thenReturn(1);
              when(row.getPayloadDigest()).thenReturn(digest);
              when(row.getPayload()).thenReturn(payload);
              return null;
            })
        .when(row)
        .refresh();
    return digest;
  }
}
