package net.firedevops.firemud.common.publication;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.HeldPublicationAuthorizationStatus;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldPublicationAuthorizationResponse;

/** Closed wire mapping and exact request-echo validation for the Account authorization read. */
public final class AccountPublicationAuthorizationReadGrpcCodec {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private AccountPublicationAuthorizationReadGrpcCodec() {}

  public static ReadHeldPublicationAuthorizationRequest toRequest(
      AccountPublicationAuthorizationReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadHeldPublicationAuthorizationRequest.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalPublicationAuthorizationBinding(
            ByteString.copyFrom(request.originalPublicationAuthorizationBinding()))
        .setPublicationAuthorizationDigest(request.publicationAuthorizationDigest())
        .build();
  }

  /** Decode only after Account authenticates its exact same-namespace Game Design peer. */
  public static AccountPublicationAuthorizationReadEvidence.Request fromRequest(
      ReadHeldPublicationAuthorizationRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadHeldPublicationAuthorizationRequest");
    try {
      return new AccountPublicationAuthorizationReadEvidence.Request(
          request.getSchemaVersion(),
          request.getTargetNamespace(),
          parseCanonicalUuid(request.getReadRequestId()),
          request.getOriginalPublicationAuthorizationBinding().toByteArray(),
          request.getPublicationAuthorizationDigest());
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "Account publication authorization read request is invalid", invalid);
    }
  }

  /** Account may emit HELD only after its owner read proves the exact immutable authorization. */
  public static ReadHeldPublicationAuthorizationResponse toHeldResponse(
      AccountPublicationAuthorizationReadEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadHeldPublicationAuthorizationResponse.newBuilder()
        .setSchemaVersion(request.schemaVersion())
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setOriginalPublicationAuthorizationBinding(
            ByteString.copyFrom(request.originalPublicationAuthorizationBinding()))
        .setPublicationAuthorizationDigest(request.publicationAuthorizationDigest())
        .setStatus(HeldPublicationAuthorizationStatus.HELD_PUBLICATION_AUTHORIZATION_STATUS_HELD)
        .build();
  }

  /** Validate a positive response; all other owner states are non-OK RPC failures. */
  static AccountPublicationAuthorizationReadEvidence fromResponse(
      AccountPublicationAuthorizationReadEvidence.Request request,
      ReadHeldPublicationAuthorizationResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadHeldPublicationAuthorizationResponse");
    if (response.getSchemaVersion() != request.schemaVersion()
        || !response.getTargetNamespace().equals(request.targetNamespace())
        || !response.getReadRequestId().equals(request.readRequestId().toString())
        || !Arrays.equals(
            response.getOriginalPublicationAuthorizationBinding().toByteArray(),
            request.originalPublicationAuthorizationBinding())
        || !response
            .getPublicationAuthorizationDigest()
            .equals(request.publicationAuthorizationDigest())) {
      throw new IllegalArgumentException(
          "Account response changed the exact publication authorization read");
    }
    if (response.getStatus()
        != HeldPublicationAuthorizationStatus.HELD_PUBLICATION_AUTHORIZATION_STATUS_HELD) {
      throw new IllegalArgumentException("Account did not confirm held publication authorization");
    }
    return new AccountPublicationAuthorizationReadEvidence(request);
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null) throw new IllegalArgumentException("Canonical read request UUID required");
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException("Canonical non-nil read request UUID required");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Canonical non-nil read request UUID required", invalid);
    }
  }
}
