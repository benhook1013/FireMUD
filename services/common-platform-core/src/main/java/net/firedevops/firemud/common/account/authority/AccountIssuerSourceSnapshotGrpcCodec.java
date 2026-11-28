package net.firedevops.firemud.common.account.authority;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ExactIssuerAuthorityEventReadback;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.account.v1.ReadExactIssuerAuthorityEventRequest;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Strict closed-proto mapping for the Account issuer-source owner read. */
public final class AccountIssuerSourceSnapshotGrpcCodec {
  private static final Pattern POSITIVE_OWNER_SEQUENCE = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern EVENT_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  private AccountIssuerSourceSnapshotGrpcCodec() {}

  /** Immutable request value; unknown wire fields are rejected before one can be constructed. */
  public record ReadRequest(
      UUID reconciliationOperationId,
      String targetNamespace,
      String issuerId,
      Optional<AccountIssuerSourceSnapshotEvidence> expectedSource) {
    public ReadRequest {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
      }
      requireNonNil(reconciliationOperationId, "reconciliationOperationId");
      if (!AccountIssuerSourceSnapshotEvidence.ISSUER_ID.equals(issuerId)) {
        throw new IllegalArgumentException("issuerId must be the supported Account issuer");
      }
      expectedSource =
          Objects.requireNonNull(expectedSource, "expectedSource presence is required");
      expectedSource.ifPresent(
          source ->
              requireRequestBinding(source, targetNamespace, reconciliationOperationId, issuerId));
    }
  }

  /** Exact historical selector, scoped independently of any expected current snapshot. */
  public record ExactEventRequest(
      ReadRequest scope, long outboxSequence, String expectedEventId, String expectedEventDigest) {
    public ExactEventRequest {
      Objects.requireNonNull(scope, "scope is required");
      if (scope.expectedSource().isPresent()) {
        throw new IllegalArgumentException("exact event scope cannot carry an expected snapshot");
      }
      if (outboxSequence <= 0L || outboxSequence == Long.MAX_VALUE) {
        throw new IllegalArgumentException("outboxSequence must be in the positive owner range");
      }
      if (expectedEventId == null
          || expectedEventId.isBlank()
          || expectedEventId.length() > 512
          || !expectedEventId.equals(expectedEventId.strip())
          || !StandardCharsets.UTF_8.newEncoder().canEncode(expectedEventId)) {
        throw new IllegalArgumentException("expectedEventId must be bounded canonical text");
      }
      if (expectedEventDigest == null || !EVENT_DIGEST.matcher(expectedEventDigest).matches()) {
        throw new IllegalArgumentException(
            "expectedEventDigest must be a canonical SHA-256 digest");
      }
    }
  }

  /** Independently validated source evidence returned by the exact event read. */
  public record ExactEventReadback(
      AccountIssuerSourceSnapshotEvidence currentSnapshot, IssuerEvent selectedEvent) {}

  public static ReadExactIssuerAuthorityEventRequest toExactEventRequest(
      ExactEventRequest request, String configuredNamespace, String authenticatedCallerWorkload) {
    validateCallContext(configuredNamespace, authenticatedCallerWorkload);
    ReadRequest scope = request.scope();
    requireConfiguredNamespace(scope, configuredNamespace);
    return ReadExactIssuerAuthorityEventRequest.newBuilder()
        .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
        .setTargetNamespace(scope.targetNamespace())
        .setReconciliationOperationId(scope.reconciliationOperationId().toString())
        .setIssuerId(scope.issuerId())
        .setOutboxSequence(Long.toString(request.outboxSequence()))
        .setExpectedEventId(request.expectedEventId())
        .setExpectedEventDigest(request.expectedEventDigest())
        .build();
  }

  public static ExactEventRequest fromExactEventRequest(
      ReadExactIssuerAuthorityEventRequest wire,
      String configuredNamespace,
      String authenticatedCallerWorkload) {
    requireNoUnknownFields(wire, "exact issuer event request");
    validateCallContext(configuredNamespace, authenticatedCallerWorkload);
    requireSchemaVersion(wire.getSchemaVersion(), "exact issuer event request");
    ReadRequest scope =
        new ReadRequest(
            canonicalNonNilUuid(wire.getReconciliationOperationId(), "reconciliationOperationId"),
            wire.getTargetNamespace(),
            wire.getIssuerId(),
            Optional.empty());
    requireConfiguredNamespace(scope, configuredNamespace);
    return new ExactEventRequest(
        scope,
        parseOwnerSequence(wire.getOutboxSequence()),
        wire.getExpectedEventId(),
        wire.getExpectedEventDigest());
  }

  public static ExactIssuerAuthorityEventReadback toExactEventResponse(
      AccountIssuerSourceSnapshotEvidence current,
      IssuerEvent selected,
      ExactEventRequest request,
      String authenticatedCallerWorkload) {
    AccountIssuerAuthoritySourceSnapshot currentWire =
        toResponse(current, request.scope(), authenticatedCallerWorkload);
    ByteString selectedBytes = ByteString.copyFromUtf8(selected.canonicalJson());
    requireEventSize(selectedBytes, "selectedEvent");
    IssuerEvent verified = verifyExactEvent(current, selected.canonicalJson(), request);
    if (!verified.equals(selected)) {
      throw new IllegalArgumentException(
          "selected issuer event differs from its canonical payload");
    }
    return ExactIssuerAuthorityEventReadback.newBuilder()
        .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
        .setCurrentSnapshot(currentWire)
        .setSelectedEvent(selectedBytes)
        .build();
  }

  public static ExactEventReadback fromExactEventResponse(
      ExactIssuerAuthorityEventReadback wire,
      ExactEventRequest request,
      String authenticatedCallerWorkload) {
    requireNoUnknownFields(wire, "exact issuer event response");
    requireSchemaVersion(wire.getSchemaVersion(), "exact issuer event response");
    if (!wire.hasCurrentSnapshot()) {
      throw new IllegalArgumentException("currentSnapshot is required");
    }
    AccountIssuerSourceSnapshotEvidence current =
        fromResponse(wire.getCurrentSnapshot(), request.scope(), authenticatedCallerWorkload);
    return new ExactEventReadback(
        current,
        verifyExactEvent(current, decodeUtf8(wire.getSelectedEvent(), "selectedEvent"), request));
  }

  private static IssuerEvent verifyExactEvent(
      AccountIssuerSourceSnapshotEvidence current,
      String canonicalEvent,
      ExactEventRequest request) {
    long currentSequence = Long.parseLong(current.lastCommittedOutboxSequence());
    if (!(AccountAuthoritySourceEventV1Codec.verify(canonicalEvent) instanceof IssuerEvent event)
        || !canonicalEvent.equals(event.canonicalJson())
        || !event.eventId().equals(event.requestId())
        || !event.issuerId().equals(request.scope().issuerId())
        || !event.outboxStreamKey().equals(current.outboxStreamKey())
        || !event.outboxSequence().equals(Long.toString(request.outboxSequence()))
        || !event.eventId().equals(request.expectedEventId())
        || !event.eventDigest().equals(request.expectedEventDigest())
        || request.outboxSequence() > currentSequence
        || (request.outboxSequence() == currentSequence
            && !current.sourceEvent().orElseThrow().equals(canonicalEvent))) {
      throw new IllegalArgumentException("selected issuer event does not match the exact read");
    }
    return event;
  }

  private static long parseOwnerSequence(String value) {
    if (!POSITIVE_OWNER_SEQUENCE.matcher(value).matches()) {
      throw new IllegalArgumentException("outboxSequence must be a positive canonical decimal");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException outOfRange) {
      throw new IllegalArgumentException(
          "outboxSequence must be in the positive owner range", outOfRange);
    }
  }

  public static ReadCurrentIssuerAuthoritySourceRequest toRequest(
      ReadRequest request, String configuredNamespace, String authenticatedCallerWorkload) {
    validateCallContext(configuredNamespace, authenticatedCallerWorkload);
    requireConfiguredNamespace(request, configuredNamespace);
    request
        .expectedSource()
        .ifPresent(source -> requireCaller(source, authenticatedCallerWorkload));

    ReadCurrentIssuerAuthoritySourceRequest.Builder builder =
        ReadCurrentIssuerAuthoritySourceRequest.newBuilder()
            .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
            .setTargetNamespace(request.targetNamespace())
            .setReconciliationOperationId(request.reconciliationOperationId().toString())
            .setIssuerId(request.issuerId());
    request.expectedSource().ifPresent(source -> builder.setExpectedSource(toWire(source)));
    return builder.build();
  }

  public static ReadRequest fromRequest(
      ReadCurrentIssuerAuthoritySourceRequest wire,
      String configuredNamespace,
      String authenticatedCallerWorkload) {
    requireNoUnknownFields(wire, "issuer source request");
    validateCallContext(configuredNamespace, authenticatedCallerWorkload);
    requireSchemaVersion(wire.getSchemaVersion(), "issuer source request");
    Optional<AccountIssuerSourceSnapshotEvidence> expected =
        wire.hasExpectedSource()
            ? Optional.of(fromWire(wire.getExpectedSource()))
            : Optional.empty();
    ReadRequest request =
        new ReadRequest(
            canonicalNonNilUuid(wire.getReconciliationOperationId(), "reconciliationOperationId"),
            wire.getTargetNamespace(),
            wire.getIssuerId(),
            expected);
    requireConfiguredNamespace(request, configuredNamespace);
    request
        .expectedSource()
        .ifPresent(source -> requireCaller(source, authenticatedCallerWorkload));
    return request;
  }

  /** Maps the response only when it still matches the exact request and authenticated caller. */
  public static AccountIssuerAuthoritySourceSnapshot toResponse(
      AccountIssuerSourceSnapshotEvidence source,
      ReadRequest request,
      String authenticatedCallerWorkload) {
    validateCallContext(request.targetNamespace(), authenticatedCallerWorkload);
    requireResponseBinding(source, request, authenticatedCallerWorkload);
    return toWire(source);
  }

  /** Parses a response without permitting a changed source to replace an expected snapshot. */
  public static AccountIssuerSourceSnapshotEvidence fromResponse(
      AccountIssuerAuthoritySourceSnapshot wire,
      ReadRequest request,
      String authenticatedCallerWorkload) {
    AccountIssuerSourceSnapshotEvidence source = fromWire(wire);
    validateCallContext(request.targetNamespace(), authenticatedCallerWorkload);
    requireResponseBinding(source, request, authenticatedCallerWorkload);
    return source;
  }

  public static AccountIssuerAuthoritySourceSnapshot toWire(
      AccountIssuerSourceSnapshotEvidence source) {
    AccountIssuerAuthoritySourceSnapshot.Builder builder =
        AccountIssuerAuthoritySourceSnapshot.newBuilder()
            .setSchemaVersion(AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION)
            .setTargetNamespace(source.targetNamespace())
            .setCallerWorkload(source.callerWorkload())
            .setReconciliationOperationId(source.reconciliationOperationId().toString())
            .setIssuerId(source.issuerId())
            .setIssuerAuthGeneration(source.issuerAuthGeneration())
            .setSourceVersion(source.sourceVersion())
            .setOutboxStreamKey(source.outboxStreamKey())
            .setLastCommittedOutboxSequence(source.lastCommittedOutboxSequence());
    source
        .sourceEventBytes()
        .ifPresent(bytes -> builder.setSourceEvent(ByteString.copyFrom(bytes)));
    return builder.build();
  }

  public static AccountIssuerSourceSnapshotEvidence fromWire(
      AccountIssuerAuthoritySourceSnapshot wire) {
    requireNoUnknownFields(wire, "issuer source snapshot");
    requireSchemaVersion(wire.getSchemaVersion(), "issuer source snapshot");
    Optional<String> sourceEvent =
        wire.hasSourceEvent()
            ? Optional.of(decodeUtf8(wire.getSourceEvent(), "sourceEvent"))
            : Optional.empty();
    return new AccountIssuerSourceSnapshotEvidence(
        canonicalNonNilUuid(wire.getReconciliationOperationId(), "reconciliationOperationId"),
        wire.getTargetNamespace(),
        wire.getCallerWorkload(),
        wire.getIssuerId(),
        wire.getIssuerAuthGeneration(),
        wire.getSourceVersion(),
        wire.getOutboxStreamKey(),
        wire.getLastCommittedOutboxSequence(),
        sourceEvent);
  }

  private static void requireResponseBinding(
      AccountIssuerSourceSnapshotEvidence source,
      ReadRequest request,
      String authenticatedCallerWorkload) {
    requireRequestBinding(source, request);
    requireCaller(source, authenticatedCallerWorkload);
    request
        .expectedSource()
        .ifPresent(
            expected -> {
              if (!expected.equals(source)) {
                throw new IllegalArgumentException(
                    "current issuer source differs from the exact expected snapshot");
              }
            });
  }

  private static void requireRequestBinding(
      AccountIssuerSourceSnapshotEvidence source, ReadRequest request) {
    requireRequestBinding(
        source, request.targetNamespace(), request.reconciliationOperationId(), request.issuerId());
  }

  private static void requireRequestBinding(
      AccountIssuerSourceSnapshotEvidence source,
      String targetNamespace,
      UUID reconciliationOperationId,
      String issuerId) {
    if (!source.targetNamespace().equals(targetNamespace)
        || !source.reconciliationOperationId().equals(reconciliationOperationId)
        || !source.issuerId().equals(issuerId)) {
      throw new IllegalArgumentException("issuer source snapshot does not match the request scope");
    }
  }

  private static void requireCaller(
      AccountIssuerSourceSnapshotEvidence source, String authenticatedCallerWorkload) {
    if (!source.callerWorkload().equals(authenticatedCallerWorkload)) {
      throw new IllegalArgumentException("issuer source snapshot caller workload changed");
    }
  }

  private static void requireConfiguredNamespace(ReadRequest request, String configuredNamespace) {
    if (!request.targetNamespace().equals(configuredNamespace)) {
      throw new IllegalArgumentException(
          "issuer source request namespace is not configured namespace");
    }
  }

  private static void validateCallContext(String namespace, String callerWorkload) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("configured namespace must be one canonical DNS label");
    }
    AccountIssuerSourceSnapshotEvidence.validateCallerWorkload(namespace, callerWorkload);
  }

  private static void requireNoUnknownFields(Message message, String description) {
    Objects.requireNonNull(message, description + " is required");
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(description + " contains unknown protobuf fields");
    }
  }

  private static void requireSchemaVersion(int schemaVersion, String description) {
    if (schemaVersion != AccountIssuerSourceSnapshotEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException(description + " has an unsupported schema version");
    }
  }

  private static UUID canonicalNonNilUuid(String value, String field) {
    Objects.requireNonNull(value, field + " is required");
    final UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase UUID", malformed);
    }
    if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
      throw new IllegalArgumentException(field + " must be a canonical lowercase non-nil UUID");
    }
    return parsed;
  }

  private static void requireNonNil(UUID value, String field) {
    Objects.requireNonNull(value, field + " is required");
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a non-nil UUID");
    }
  }

  private static void requireEventSize(ByteString bytes, String field) {
    if (bytes.isEmpty()
        || bytes.size() > AccountIssuerSourceSnapshotEvidence.MAX_SOURCE_EVENT_BYTES) {
      throw new IllegalArgumentException(field + " must be nonempty and at most 64 KiB");
    }
  }

  private static String decodeUtf8(ByteString bytes, String field) {
    requireEventSize(bytes, field);
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(bytes.asReadOnlyByteBuffer())
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(field + " contains malformed UTF-8", malformed);
    }
  }
}
