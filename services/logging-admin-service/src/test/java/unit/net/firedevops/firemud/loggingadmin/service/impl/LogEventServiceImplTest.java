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
    assertEquals(91L, result.logEventId());
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

  private static AccountAuditReceipt receipt(
      CreateLogEventRequest request, byte[] payload, String status, String outcome) {
    return new AccountAuditReceipt(
        91L,
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
