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
  void changedEventTypeWithinIdentityReturnsIdempotencyConflict() {
    CreateLogEventRequest original = request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{}");
    CreateLogEventRequest changed =
        new CreateLogEventRequest(
            original.scope(),
            original.tenantId(),
            original.auditEventId(),
            original.producerService(),
            "ACCOUNT_RECOVERY",
            original.occurredAt(),
            original.schemaVersion(),
            original.payload(),
            original.payloadDigestVersion(),
            original.payloadDigest());
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
  void minimizedReadAcceptsOmittedPayloadWithMatchingIdentityMetadataAndDigest() {
    CreateLogEventRequest original =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{\"retained\":true}");
    CreateLogEventRequest digestOnly = withPayload(original, ByteString.EMPTY);
    when(repository.findByIdentity(eq(digestOnly), eq(0L)))
        .thenReturn(Optional.of(receipt(original, null, "MINIMIZED", "NON_REPLAYABLE")));

    var result = service.readLogEventReceipt(digestOnly);

    assertEquals(AccountAuditReceiptStatus.MINIMIZED, result.status());
    assertEquals(AccountAuditReceiptOutcome.NON_REPLAYABLE, result.outcome());
    assertEquals(RECEIPT_ID.toString(), result.receiptId());
    verify(repository).findByIdentity(eq(digestOnly), eq(0L));
  }

  @Test
  void minimizedReadReportsConflictForChangedDigestOrImmutableMetadata() {
    CreateLogEventRequest original =
        request(AccountAuditScope.TENANT, 42L, Instant.EPOCH, "{\"retained\":true}");
    CreateLogEventRequest changedDigest =
        withPayloadAndDigest(
            original,
            ByteString.EMPTY,
            digest("{\"changed\":true}".getBytes(StandardCharsets.UTF_8)));
    CreateLogEventRequest changedEventType =
        withEventType(withPayload(original, ByteString.EMPTY), "ACCOUNT_RECOVERY");
    AccountAuditReceipt minimized = receipt(original, null, "MINIMIZED", "NON_REPLAYABLE");
    when(repository.findByIdentity(eq(changedDigest), eq(42L))).thenReturn(Optional.of(minimized));
    when(repository.findByIdentity(eq(changedEventType), eq(42L)))
        .thenReturn(Optional.of(minimized));

    var digestResult = service.readLogEventReceipt(changedDigest);
    var metadataResult = service.readLogEventReceipt(changedEventType);

    assertEquals(AccountAuditReceiptStatus.CONFLICT, digestResult.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, digestResult.outcome());
    assertEquals(AccountAuditReceiptStatus.CONFLICT, metadataResult.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, metadataResult.outcome());
  }

  @Test
  void readRejectsMalformedDigestAndUnsupportedVersionBeforeRepositoryAccess() {
    CreateLogEventRequest valid =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{\"retained\":true}");
    CreateLogEventRequest malformedDigest =
        withPayloadAndDigest(valid, ByteString.EMPTY, "sha256:ABC");
    CreateLogEventRequest unsupportedVersion = withPayloadDigestVersion(valid, 2);

    assertThrows(
        IllegalArgumentException.class, () -> service.readLogEventReceipt(malformedDigest));
    assertThrows(
        IllegalArgumentException.class, () -> service.readLogEventReceipt(unsupportedVersion));

    verifyNoInteractions(repository);
  }

  @Test
  void readRejectsSuppliedPayloadWithMismatchedDigestBeforeRepositoryAccess() {
    CreateLogEventRequest valid =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{\"retained\":true}");
    ByteString differentPayload = ByteString.copyFromUtf8("{\"changed\":true}");
    CreateLogEventRequest mismatched =
        withPayloadAndDigest(valid, differentPayload, valid.payloadDigest());

    assertThrows(IllegalArgumentException.class, () -> service.readLogEventReceipt(mismatched));

    verifyNoInteractions(repository);
  }

  @Test
  void retainedReadRequiresFullPayloadAndReturnsConflictForOmittedOrChangedPayload() {
    CreateLogEventRequest original =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{\"retained\":true}");
    CreateLogEventRequest omitted = withPayload(original, ByteString.EMPTY);
    CreateLogEventRequest changed =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{\"changed\":true}");
    AccountAuditReceipt retained =
        receipt(original, original.payload().toByteArray(), "COMMITTED", "ACCEPTED");
    when(repository.findByIdentity(eq(omitted), eq(0L))).thenReturn(Optional.of(retained));
    when(repository.findByIdentity(eq(changed), eq(0L))).thenReturn(Optional.of(retained));

    var omittedResult = service.readLogEventReceipt(omitted);
    var changedResult = service.readLogEventReceipt(changed);

    assertEquals(AccountAuditReceiptStatus.CONFLICT, omittedResult.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, omittedResult.outcome());
    assertEquals(AccountAuditReceiptStatus.CONFLICT, changedResult.status());
    assertEquals(AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT, changedResult.outcome());
  }

  @Test
  void exactEmptyPayloadReadRemainsDistinctFromDigestOnlyMinimizedRead() {
    CreateLogEventRequest emptyPayload =
        request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "");
    AccountAuditReceipt retained = receipt(emptyPayload, new byte[0], "COMMITTED", "ACCEPTED");
    AccountAuditReceipt minimized = receipt(emptyPayload, null, "MINIMIZED", "NON_REPLAYABLE");
    when(repository.findByIdentity(eq(emptyPayload), eq(0L)))
        .thenReturn(Optional.of(retained), Optional.of(minimized));

    var retainedResult = service.readLogEventReceipt(emptyPayload);
    var minimizedResult = service.readLogEventReceipt(emptyPayload);

    assertEquals(AccountAuditReceiptStatus.COMMITTED, retainedResult.status());
    assertEquals(AccountAuditReceiptOutcome.DUPLICATE, retainedResult.outcome());
    assertEquals(AccountAuditReceiptStatus.MINIMIZED, minimizedResult.status());
    assertEquals(AccountAuditReceiptOutcome.NON_REPLAYABLE, minimizedResult.outcome());
  }

  @Test
  void missingReadbackThrowsForCanonicalNotFoundMapping() {
    CreateLogEventRequest request = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    when(repository.findByIdentity(request, 0L)).thenReturn(Optional.empty());

    assertThrows(AuditReceiptNotFoundException.class, () -> service.readLogEventReceipt(request));
  }

  @Test
  void unsupportedDigestVersionFailsBeforeRepositoryAccess() {
    CreateLogEventRequest valid = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest unsupported =
        new CreateLogEventRequest(
            valid.scope(),
            valid.tenantId(),
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
  void payloadDigestMismatchFailsBeforeRepositoryAccess() {
    CreateLogEventRequest valid = request(AccountAuditScope.PLATFORM, null, Instant.EPOCH, "{}");
    CreateLogEventRequest mismatched =
        new CreateLogEventRequest(
            valid.scope(),
            valid.tenantId(),
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
        tenantId,
        AUDIT_EVENT_ID,
        "account-service",
        "ACCOUNT_REGISTERED",
        occurredAt,
        1,
        payload,
        1,
        digest(payload.toByteArray()));
  }

  private static CreateLogEventRequest withPayload(
      CreateLogEventRequest request, ByteString payload) {
    return withPayloadAndDigest(request, payload, request.payloadDigest());
  }

  private static CreateLogEventRequest withPayloadAndDigest(
      CreateLogEventRequest request, ByteString payload, String payloadDigest) {
    return new CreateLogEventRequest(
        request.scope(),
        request.tenantId(),
        request.auditEventId(),
        request.producerService(),
        request.eventType(),
        request.occurredAt(),
        request.schemaVersion(),
        payload,
        request.payloadDigestVersion(),
        payloadDigest);
  }

  private static CreateLogEventRequest withEventType(
      CreateLogEventRequest request, String eventType) {
    return new CreateLogEventRequest(
        request.scope(),
        request.tenantId(),
        request.auditEventId(),
        request.producerService(),
        eventType,
        request.occurredAt(),
        request.schemaVersion(),
        request.payload(),
        request.payloadDigestVersion(),
        request.payloadDigest());
  }

  private static CreateLogEventRequest withPayloadDigestVersion(
      CreateLogEventRequest request, int payloadDigestVersion) {
    return new CreateLogEventRequest(
        request.scope(),
        request.tenantId(),
        request.auditEventId(),
        request.producerService(),
        request.eventType(),
        request.occurredAt(),
        request.schemaVersion(),
        request.payload(),
        payloadDigestVersion,
        request.payloadDigest());
  }

  private static AccountAuditReceipt receipt(
      CreateLogEventRequest request, byte[] payload, String status, String outcome) {
    return new AccountAuditReceipt(
        91L,
        LOG_EVENT_ID,
        RECEIPT_ID,
        request.scope().databaseValue(),
        request.tenantId(),
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
