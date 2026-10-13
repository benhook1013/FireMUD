package net.firedevops.firemud.common.account.authority;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Strict closed-proto mapping for the Account issuer-source owner read. */
public final class AccountIssuerSourceSnapshotGrpcCodec {
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
            ? Optional.of(decodeUtf8(wire.getSourceEvent().toByteArray(), "sourceEvent"))
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

  private static String decodeUtf8(byte[] bytes, String field) {
    if (bytes.length == 0
        || bytes.length > AccountIssuerSourceSnapshotEvidence.MAX_SOURCE_EVENT_BYTES) {
      throw new IllegalArgumentException(field + " must be nonempty and at most 64 KiB");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(field + " contains malformed UTF-8", malformed);
    }
  }
}
