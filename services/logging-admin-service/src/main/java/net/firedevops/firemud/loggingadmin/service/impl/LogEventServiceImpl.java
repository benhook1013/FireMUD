package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptDto;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptOutcome;
import net.firedevops.firemud.loggingadmin.dto.AccountAuditReceiptStatus;
import net.firedevops.firemud.loggingadmin.dto.CreateLogEventRequest;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceipt;
import net.firedevops.firemud.loggingadmin.entity.AccountAuditReceiptInsertResult;
import net.firedevops.firemud.loggingadmin.repository.AccountAuditReceiptRepository;
import net.firedevops.firemud.loggingadmin.service.AuditReceiptNotFoundException;
import net.firedevops.firemud.loggingadmin.service.AuditStorageUnavailableException;
import net.firedevops.firemud.loggingadmin.service.LogEventService;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class LogEventServiceImpl implements LogEventService {
  private static final Logger logger = LoggingUtil.getLogger(LogEventServiceImpl.class);
  private static final String ACCOUNT_SERVICE = "account-service";
  private static final String SHA_256_PREFIX = "sha256:";

  private final AccountAuditReceiptRepository repository;

  public LogEventServiceImpl(AccountAuditReceiptRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public AccountAuditReceiptDto createLogEvent(CreateLogEventRequest request) {
    validateEnvelope(request);
    try {
      AccountAuditReceiptInsertResult result =
          repository.insertIfAbsent(request, UUID.randomUUID());
      return receiptOutcome(result.receipt(), request, result.inserted());
    } catch (DataAccessException ex) {
      logger.warn("Account audit receipt write is unavailable: {}", ex.getClass().getSimpleName());
      throw new AuditStorageUnavailableException(ex);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public AccountAuditReceiptDto readLogEventReceipt(CreateLogEventRequest request) {
    validateEnvelope(request);
    try {
      long tenantKey = request.tenantId() == null ? 0L : request.tenantId();
      AccountAuditReceipt receipt =
          repository
              .findByIdentity(request, tenantKey)
              .orElseThrow(AuditReceiptNotFoundException::new);
      return receiptOutcome(receipt, request, false);
    } catch (AuditReceiptNotFoundException ex) {
      throw ex;
    } catch (DataAccessException ex) {
      logger.warn("Account audit receipt read is unavailable: {}", ex.getClass().getSimpleName());
      throw new AuditStorageUnavailableException(ex);
    }
  }

  private static AccountAuditReceiptDto receiptOutcome(
      AccountAuditReceipt receipt, CreateLogEventRequest request, boolean inserted) {
    if (inserted) {
      return toDto(
          receipt, AccountAuditReceiptStatus.COMMITTED, AccountAuditReceiptOutcome.ACCEPTED);
    }

    boolean metadataMatches = metadataMatches(receipt, request);
    if (!metadataMatches) {
      return toDto(
          receipt,
          AccountAuditReceiptStatus.CONFLICT,
          AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT);
    }

    byte[] retainedPayload = receipt.payload();
    if (retainedPayload == null) {
      return toDto(
          receipt, AccountAuditReceiptStatus.MINIMIZED, AccountAuditReceiptOutcome.NON_REPLAYABLE);
    }
    if (!Arrays.equals(retainedPayload, request.payload().toByteArray())) {
      return toDto(
          receipt,
          AccountAuditReceiptStatus.CONFLICT,
          AccountAuditReceiptOutcome.IDEMPOTENCY_CONFLICT);
    }

    if ("MINIMIZED".equals(receipt.status()) || "NON_REPLAYABLE".equals(receipt.outcome())) {
      return toDto(
          receipt, AccountAuditReceiptStatus.MINIMIZED, AccountAuditReceiptOutcome.NON_REPLAYABLE);
    }
    if (!"COMMITTED".equals(receipt.status())) {
      throw new IllegalStateException("Unknown persisted account audit receipt status");
    }
    return toDto(
        receipt, AccountAuditReceiptStatus.COMMITTED, AccountAuditReceiptOutcome.DUPLICATE);
  }

  private static boolean metadataMatches(
      AccountAuditReceipt receipt, CreateLogEventRequest request) {
    Instant occurredAt = request.occurredAt();
    return receipt.scope().equals(request.scope().databaseValue())
        && Objects.equals(receipt.tenantId(), request.tenantId())
        && receipt.auditEventId().equals(request.auditEventId())
        && receipt.producerService().equals(request.producerService())
        && receipt.eventType().equals(request.eventType())
        && receipt.occurredAtSeconds() == occurredAt.getEpochSecond()
        && receipt.occurredAtNanos() == occurredAt.getNano()
        && receipt.schemaVersion() == request.schemaVersion()
        && receipt.payloadDigestVersion() == request.payloadDigestVersion()
        && receipt.payloadDigest().equals(request.payloadDigest());
  }

  private static AccountAuditReceiptDto toDto(
      AccountAuditReceipt receipt,
      AccountAuditReceiptStatus status,
      AccountAuditReceiptOutcome outcome) {
    return new AccountAuditReceiptDto(
        "platform".equals(receipt.scope())
            ? net.firedevops.firemud.loggingadmin.dto.AccountAuditScope.PLATFORM
            : net.firedevops.firemud.loggingadmin.dto.AccountAuditScope.TENANT,
        receipt.tenantId(),
        receipt.auditEventId(),
        receipt.receiptId().toString(),
        receipt.id(),
        receipt.schemaVersion(),
        receipt.payloadDigestVersion(),
        receipt.payloadDigest(),
        status,
        outcome);
  }

  private static void validateEnvelope(CreateLogEventRequest request) {
    if (request == null
        || request.scope() == null
        || request.auditEventId() == null
        || request.auditEventId().isBlank()
        || request.producerService() == null
        || !ACCOUNT_SERVICE.equals(request.producerService())
        || request.eventType() == null
        || request.eventType().isBlank()
        || request.occurredAt() == null
        || request.schemaVersion() <= 0
        || request.payload() == null
        || request.payloadDigest() == null) {
      throw new IllegalArgumentException("Account audit envelope is incomplete");
    }
    if (!isCanonicalUuid(request.auditEventId())) {
      throw new IllegalArgumentException("auditEventId must be a canonical UUID");
    }
    if (request.scope() == net.firedevops.firemud.loggingadmin.dto.AccountAuditScope.PLATFORM) {
      if (request.tenantId() != null) {
        throw new IllegalArgumentException("tenantId must be absent for platform scope");
      }
    } else if (request.tenantId() == null || request.tenantId() <= 0) {
      throw new IllegalArgumentException("tenantId must be positive for tenant scope");
    }
    if (request.payloadDigestVersion() != 1) {
      throw new IllegalArgumentException("payloadDigestVersion must be 1");
    }
    if (!request.payloadDigest().matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadDigest must be lowercase sha256 hex");
    }
    byte[] payloadBytes = request.payload().toByteArray();
    validateUtf8(payloadBytes);
    String computedDigest = SHA_256_PREFIX + sha256Hex(payloadBytes);
    if (!computedDigest.equals(request.payloadDigest())) {
      throw new IllegalArgumentException("payloadDigest does not match the exact payload bytes");
    }
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return value.equals(UUID.fromString(value).toString());
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static void validateUtf8(byte[] payload) {
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(payload));
    } catch (CharacterCodingException ex) {
      throw new IllegalArgumentException("payload must contain exact UTF-8 bytes", ex);
    }
  }

  private static String sha256Hex(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
