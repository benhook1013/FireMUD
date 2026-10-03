package net.firedevops.firemud.loggingadmin.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditScope;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceipt;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceiptInsertResult;
import net.firedevops.firemud.loggingadmin.repository.AccountAuditReceiptRepository;
import net.firedevops.firemud.loggingadmin.service.AuditReceiptNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LogEventServiceImplTest {
  private static final String AUDIT_EVENT_ID = "d2719d4f-3b2a-4f64-a994-0f9ccdfdd2b3";
  private static final UUID RECEIPT_ID = UUID.fromString("c0c1f03b-31b7-4281-aaf8-2d66f29e8770");
  private static final long LOG_EVENT_ID = 192L;

  private final AccountAuditReceiptRepository repository =
      Mockito.mock(AccountAuditReceiptRepository.class);
  private final LogEventServiceImpl service = new LogEventServiceImpl(repository);

  @Test
  void firstWriteReturnsCommittedAcceptedReceipt() {
    CreateLogEventRequest request = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    AccountAuditReceipt receipt =
        receipt(request, request.payload().toByteArray(), "COMMITTED", "ACCEPTED");
    when(repository.insertIfAbsent(eq(request), any(UUID.class)))
        .thenReturn(new AccountAuditReceiptInsertResult(receipt, true));

    var result = service.createLogEvent(request);

    assertEquals(AccountAuditReceiptStatus.COMMITTED, result.status());
    assertEquals(AccountAuditReceiptOutcome.ACCEPTED, result.outcome());
    assertEquals(LOG_EVENT_ID, result.logEventId());
    verify(repository).insertIfAbsent(eq(request), any(UUID.class));
  }

  @Test
  void exactRetryReturnsOriginalReceiptAsDuplicate() {
    CreateLogEventRequest request = request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}");
    AccountAuditReceipt receipt =
        receipt(request, request.payload().toByteArray(), "COMMITTED", "ACCEPTED");
    when(repository.insertIfAbsent(eq(request), any(UUID.class)))
        .thenReturn(new AccountAuditReceiptInsertResult(receipt, false));

    var result = service.createLogEvent(request);

    assertEquals(AccountAuditReceiptStatus.COMMITTED, result.status());
    assertEquals(AccountAuditReceiptOutcome.DUPLICATE, result.outcome());
    assertEquals(42L, result.tenantId());
    assertEquals(LOG_EVENT_ID, result.logEventId());
    assertEquals(RECEIPT_ID.toString(), result.receiptId());
  }

  @Test
  void changedPayloadReturnsIdempotencyConflict() {
    CreateLogEventRequest original = request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}");
    CreateLogEventRequest changed =
        request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{\"changed\":true}");
    when(repository.insertIfAbsent(eq(changed), any(UUID.class)))
        .thenReturn(
            new AccountAuditReceiptInsertResult(
                receipt(original, original.payload().toByteArray(), "COMMITTED", "ACCEPTED"),
                false));

    var result = service.createLogEvent(changed);

    assertEquals(AccountAuditReceiptStatus.CONFLICT, result.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, result.outcome());
    assertEquals(LOG_EVENT_ID, result.logEventId());
  }

  @Test
  void changedImmutableMetadataReturnsIdempotencyConflict() {
    CreateLogEventRequest original = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest changed =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH.plusSeconds(1), "{}");
    when(repository.insertIfAbsent(eq(changed), any(UUID.class)))
        .thenReturn(
            new AccountAuditReceiptInsertResult(
                receipt(original, original.payload().toByteArray(), "COMMITTED", "ACCEPTED"),
                false));

    var result = service.createLogEvent(changed);

    assertEquals(AccountAuditReceiptStatus.CONFLICT, result.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, result.outcome());
  }

  @Test
  void minimizedRetryIsSuccessfulButNonReplayable() {
    CreateLogEventRequest request = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    when(repository.insertIfAbsent(eq(request), any(UUID.class)))
        .thenReturn(
            new AccountAuditReceiptInsertResult(
                receipt(request, null, "MINIMIZED", "NON_REPLAYABLE"), false));

    var result = service.createLogEvent(request);

    assertEquals(AccountAuditReceiptStatus.MINIMIZED, result.status());
    assertEquals(AccountAuditReceiptOutcome.NON_REPLAYABLE, result.outcome());
  }

  @Test
  void missingReadbackThrowsForCanonicalNotFoundMapping() {
    CreateLogEventRequest request = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    when(repository.findByIdentity(request)).thenReturn(Optional.empty());

    assertThrows(AuditReceiptNotFoundException.class, () -> service.readLogEventReceipt(request));
  }

  @Test
  void unsupportedDigestVersionFailsBeforeRepositoryAccess() {
    CreateLogEventRequest valid = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest unsupported =
        new CreateLogEventRequest(
            valid.scope(),
            valid.tenantIdentityVersion(),
            valid.tenantId(),
            valid.tenantUuid(),
            valid.auditEventId(),
            valid.producerService(),
            valid.eventType(),
            valid.occurredAt(),
            valid.schemaVersion(),
            valid.payload(),
            2,
            valid.payloadDigest());

    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(unsupported));
    verifyNoInteractions(repository);
  }

  @Test
  void canonicalUuidIdentityIsReturnedAndComparedOnRetryAndReadback() {
    UUID tenantUuid = UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21");
    CreateLogEventRequest request =
        uuidRequest(AccountAuditScope.TENANT, tenantUuid, Instant.EPOCH, "{}");
    AccountAuditReceipt receipt =
        receipt(request, request.payload().toByteArray(), "COMMITTED", "ACCEPTED");
    when(repository.insertIfAbsent(eq(request), any(UUID.class)))
        .thenReturn(new AccountAuditReceiptInsertResult(receipt, false));
    when(repository.findByIdentity(request)).thenReturn(Optional.of(receipt));

    var duplicate = service.createLogEvent(request);
    var readback = service.readLogEventReceipt(request);

    assertEquals(2, duplicate.tenantIdentityVersion());
    assertEquals(tenantUuid, duplicate.tenantUuid());
    assertEquals(AccountAuditReceiptOutcome.DUPLICATE, duplicate.outcome());
    assertEquals(2, readback.tenantIdentityVersion());
    assertEquals(tenantUuid, readback.tenantUuid());
    assertEquals(LOG_EVENT_ID, readback.logEventId());
  }

  @Test
  void changedTenantIdentityVersionConflictsWithRetainedReceiptMetadata() {
    UUID tenantUuid = UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21");
    CreateLogEventRequest original =
        uuidRequest(AccountAuditScope.TENANT, tenantUuid, Instant.EPOCH, "{}");
    CreateLogEventRequest changed = request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}");
    when(repository.insertIfAbsent(eq(changed), any(UUID.class)))
        .thenReturn(
            new AccountAuditReceiptInsertResult(
                receipt(original, original.payload().toByteArray(), "COMMITTED", "ACCEPTED"),
                false));

    var result = service.createLogEvent(changed);

    assertEquals(AccountAuditReceiptStatus.CONFLICT, result.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, result.outcome());
  }

  @Test
  void missingOrContradictoryTenantIdentityFailsBeforeRepositoryAccess() {
    CreateLogEventRequest validPlatform =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest missingVersion = withIdentity(validPlatform, 0, null, null);
    CreateLogEventRequest mixedV1 =
        withIdentity(
            request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}"),
            1,
            42L,
            UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21"));
    CreateLogEventRequest platformV2 =
        withIdentity(
            validPlatform, 2, null, UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21"));
    CreateLogEventRequest numericV2 =
        withIdentity(
            request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}"),
            2,
            42L,
            UUID.fromString("c7a1b80e-a5fa-4fc9-9fc4-cab3cbe44b21"));
    CreateLogEventRequest nilV2 =
        withIdentity(
            request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}"), 2, null, new UUID(0L, 0L));

    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(missingVersion));
    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(mixedV1));
    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(platformV2));
    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(numericV2));
    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(nilV2));
    verifyNoInteractions(repository);
  }

  @Test
  void payloadDigestMismatchFailsBeforeRepositoryAccess() {
    CreateLogEventRequest valid = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest mismatched =
        new CreateLogEventRequest(
            valid.scope(),
            valid.tenantIdentityVersion(),
            valid.tenantId(),
            valid.tenantUuid(),
            valid.auditEventId(),
            valid.producerService(),
            valid.eventType(),
            valid.occurredAt(),
            valid.schemaVersion(),
            valid.payload(),
            valid.payloadDigestVersion(),
            "sha256:" + "0".repeat(64));

    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(mismatched));
    verifyNoInteractions(repository);
  }

  @Test
  void invalidUtf8PayloadFailsBeforeRepositoryAccess() {
    ByteString payload = ByteString.copyFrom(new byte[] {(byte) 0xff});
    CreateLogEventRequest request =
        new CreateLogEventRequest(
            AccountAuditScope.PLATFORM,
            1,
            null,
            null,
            AUDIT_EVENT_ID,
            "account-service",
            "ACCOUNT_REGISTERED",
            Instant.EPOCH,
            1,
            payload,
            1,
            digest(payload.toByteArray()));

    assertThrows(IllegalArgumentException.class, () -> service.createLogEvent(request));
    verifyNoInteractions(repository);
  }

  private static CreateLogEventRequest request(
      AccountAuditScope scope, Long tenantId, Instant occurredAt, String payloadText) {
    ByteString payload = ByteString.copyFrom(payloadText, StandardCharsets.UTF_8);
    return new CreateLogEventRequest(
        scope,
        1,
        tenantId,
        null,
        AUDIT_EVENT_ID,
        "account-service",
        "ACCOUNT_REGISTERED",
        occurredAt,
        1,
        payload,
        1,
        digest(payload.toByteArray()));
  }

  private static CreateLogEventRequest uuidRequest(
      AccountAuditScope scope, UUID tenantUuid, Instant occurredAt, String payloadText) {
    ByteString payload = ByteString.copyFrom(payloadText, StandardCharsets.UTF_8);
    return new CreateLogEventRequest(
        scope,
        2,
        null,
        tenantUuid,
        AUDIT_EVENT_ID,
        "account-service",
        "ACCOUNT_REGISTERED",
        occurredAt,
        1,
        payload,
        1,
        digest(payload.toByteArray()));
  }

  private static CreateLogEventRequest withIdentity(
      CreateLogEventRequest request, int identityVersion, Long tenantId, UUID tenantUuid) {
    return new CreateLogEventRequest(
        request.scope(),
        identityVersion,
        tenantId,
        tenantUuid,
        request.auditEventId(),
        request.producerService(),
        request.eventType(),
        request.occurredAt(),
        request.schemaVersion(),
        request.payload(),
        request.payloadDigestVersion(),
        request.payloadDigest());
  }

  private static AccountAuditReceipt receipt(
      CreateLogEventRequest request, byte[] payload, String status, String outcome) {
    return new AccountAuditReceipt(
        91L,
        LOG_EVENT_ID,
        RECEIPT_ID,
        request.scope().databaseValue(),
        request.tenantIdentityVersion(),
        request.tenantId(),
        request.tenantUuid(),
        request.auditEventId(),
        request.producerService(),
        request.eventType(),
        request.occurredAt().getEpochSecond(),
        request.occurredAt().getNano(),
        request.schemaVersion(),
        request.payloadDigestVersion(),
        request.payloadDigest(),
        payload,
        status,
        outcome);
  }

  private static String digest(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
