package net.firedevops.firemud.entitymanagement.charactercreate;

import com.google.protobuf.Message;
import io.grpc.Status;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;
import net.firedevops.firemud.entitymanagement.v1.CommitProtectedCharacterCreateRequest;

/** Closed receiving validation; no caller-supplied action or proof becomes owner authority. */
public final class EntityCharacterCreateBindings {
  public static final int MAX_REQUEST_BYTES = 16 * 1024;
  public static final int MAX_ACCOUNT_ORIGINAL_RESPONSE_BYTES = 36 * 1024 * 1024;
  public static final int MAX_SOURCE_READBACK_BYTES = 32 * 1024 * 1024;
  public static final int MAX_ACCOUNT_RESPONSE_BYTES = 40 * 1024 * 1024;
  public static final int MAX_GAME_SESSION_RESPONSE_BYTES = 48 * 1024 * 1024;
  public static final int MAX_WORLD_RESPONSE_BYTES = 48 * 1024 * 1024;
  public static final int MAX_TERMINAL_BYTES = 192 * 1024 * 1024;

  private EntityCharacterCreateBindings() {}

  public static void requireOriginalRequest(ReadOriginalCharacterCreateRequest request) {
    exact(request);
    if (!GrpcPeerIdentity.isValidNamespace(request.getTargetNamespace())
        || !request.getMutationDigest().matches("[0-9a-f]{64}")) throw invalid();
    id(request.getOperationId());
  }

  public static void requireRequest(CommitProtectedCharacterCreateRequest request) {
    exact(request);
    if (!request.hasOriginal()
        || !request.hasAccountProtection()
        || !request.hasGameSessionProtection()
        || !request.hasWorldProtection()
        || request.getSerializedSize() > MAX_REQUEST_BYTES) throw invalid();
    requireOriginalRequest(request.getOriginal());
    requireReference(request.getAccountProtection());
    requireReference(request.getGameSessionProtection());
    requireReference(request.getWorldProtection());
  }

  public static void requireReference(CharacterCreateProtectionReference reference) {
    exact(reference);
    id(reference.getProtectionId());
    if (reference.getProtectionFence() <= 0L) throw invalid();
  }

  public static CharacterCreateOperatorAction requireOriginal(
      ReadOriginalCharacterCreateRequest request, ReadOriginalCharacterCreateResponse original) {
    requireOriginalRequest(request);
    requireBound(original, MAX_ACCOUNT_ORIGINAL_RESPONSE_BYTES);
    id(original.getCreateCharacterRequestId());
    requireConnectScope(original.getConnectScopeId());
    id(original.getAuthenticatedAccountId());
    if (!request.getTargetNamespace().equals(original.getTargetNamespace())
        || !request.getOperationId().equals(original.getOperationId())
        || !request.getMutationDigest().equals(original.getMutationDigest())
        || !"CHARACTER_CREATE".equals(original.getPurpose())
        || original.getOriginalAction().isEmpty()
        || original.getOriginalAction().size() > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES
        || original.getOriginalSourceReadback().isEmpty()
        || original.getOriginalSourceReadback().size() > MAX_SOURCE_READBACK_BYTES) throw invalid();
    byte[] bytes = original.getOriginalAction().toByteArray();
    CharacterCreateOperatorAction action = CharacterCreateOperatorActionCodec.decode(bytes);
    if (!Arrays.equals(bytes, CharacterCreateOperatorActionCodec.encode(action))
        || !request
            .getMutationDigest()
            .equals(CharacterCreateOperatorActionCodec.mutationDigest(action))
        || !original.getAuthenticatedAccountId().equals(action.target().accountId())
        || !original.getTargetNamespace().equals(action.scope().targetNamespace())
        || !action.mutation().catalog().publicProduction()
        || !action.mutation().catalog().visible()) throw invalid();
    return action;
  }

  public static void requireAccountProtection(
      CommitProtectedCharacterCreateRequest request,
      ReadOriginalCharacterCreateResponse original,
      ReadOriginalCharacterCreateProtectionResponse protection) {
    requireBound(protection, MAX_ACCOUNT_RESPONSE_BYTES);
    if (!protection.hasOriginal()
        || !original.equals(protection.getOriginal())
        || !request
            .getAccountProtection()
            .getProtectionId()
            .equals(protection.getAccountProtectionId())
        || request.getAccountProtection().getProtectionFence()
            != protection.getAccountProtectionFence()
        || protection.getOriginalMembershipSnapshot().isEmpty()
        || protection.getOriginalEntitlementSnapshot().isEmpty()) throw invalid();
    instant(protection.getAcquiredAt());
  }

  private static void requireConnectScope(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 2048
        || value.indexOf(0) >= 0
        || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
  }

  public static UUID id(String value) {
    if (value == null) throw invalid();
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value) || id.equals(new UUID(0L, 0L))) throw invalid();
      return id;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  public static Instant instant(String value) {
    try {
      Instant instant = Instant.parse(value);
      if (!instant.toString().equals(value)) throw invalid();
      return instant;
    } catch (java.time.DateTimeException malformed) {
      throw invalid();
    }
  }

  public static void exact(Message message) {
    if (message == null
        || !message.getUnknownFields().asMap().isEmpty()
        || message.getSerializedSize() > MAX_TERMINAL_BYTES) throw invalid();
  }

  public static void requireBound(Message message, int maximumBytes) {
    exact(message);
    if (message.getSerializedSize() > maximumBytes) throw invalid();
  }

  public static RuntimeException invalid() {
    return Status.INVALID_ARGUMENT
        .withDescription("Exact CHARACTER_CREATE binding required")
        .asRuntimeException();
  }

  public static RuntimeException conflict() {
    return Status.ALREADY_EXISTS
        .withDescription("CHARACTER_CREATE immutable intent conflict")
        .asRuntimeException();
  }
}
