package net.firedevops.firemud.gamesession.charactercreate;

import io.grpc.Status;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionRequest;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;

/**
 * Passive exact-purpose binding checks; only the separate authenticated owner client reads Account.
 */
public final class GameSessionCharacterCreateBinding {
  // These larger bounds are local to this closed creation family, not shared gRPC defaults.
  public static final int MAX_ACCOUNT_ORIGINAL_BYTES = 36 * 1024 * 1024;
  public static final int MAX_ACCOUNT_PROTECTION_BYTES = 40 * 1024 * 1024;
  public static final int MAX_SOURCE_READBACK_BYTES = 32 * 1024 * 1024;
  public static final int MAX_WIRE_BYTES = 48 * 1024 * 1024;

  private GameSessionCharacterCreateBinding() {}

  public static void requireSelector(
      ReadOriginalCharacterCreateProtectionRequest selector, String namespace) {
    if (selector == null
        || !selector.hasOriginal()
        || !selector.getUnknownFields().asMap().isEmpty()
        || !selector.getOriginal().getUnknownFields().asMap().isEmpty()
        || !GrpcPeerIdentity.isValidNamespace(namespace)
        || !namespace.equals(selector.getOriginal().getTargetNamespace())
        || !selector.getOriginal().getMutationDigest().matches("[0-9a-f]{64}")
        || selector.getAccountProtectionFence() <= 0) throw invalid();
    id(selector.getOriginal().getOperationId());
    id(selector.getAccountProtectionId());
  }

  public static CharacterCreateOperatorAction requireAccountProtection(
      ReadOriginalCharacterCreateProtectionRequest selector,
      ReadOriginalCharacterCreateResponse original,
      ReadOriginalCharacterCreateProtectionResponse protection,
      String namespace) {
    requireSelector(selector, namespace);
    if (original == null
        || protection == null
        || !protection.hasOriginal()
        || original.getSerializedSize() > MAX_ACCOUNT_ORIGINAL_BYTES
        || protection.getSerializedSize() > MAX_ACCOUNT_PROTECTION_BYTES
        || !original.getUnknownFields().asMap().isEmpty()
        || !protection.getUnknownFields().asMap().isEmpty()
        || !Arrays.equals(original.toByteArray(), protection.getOriginal().toByteArray())
        || !namespace.equals(original.getTargetNamespace())
        || !selector.getOriginal().getOperationId().equals(original.getOperationId())
        || !selector.getOriginal().getMutationDigest().equals(original.getMutationDigest())
        || !selector.getAccountProtectionId().equals(protection.getAccountProtectionId())
        || selector.getAccountProtectionFence() != protection.getAccountProtectionFence()
        || !"CHARACTER_CREATE".equals(original.getPurpose())
        || original.getOriginalAction().isEmpty()
        || original.getOriginalAction().size() > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES
        || original.getOriginalSourceReadback().isEmpty()
        || original.getOriginalSourceReadback().size() > MAX_SOURCE_READBACK_BYTES
        || protection.getOriginalMembershipSnapshot().size() > 1024 * 1024
        || protection.getOriginalEntitlementSnapshot().size() > 1024 * 1024
        || protection.getOriginalMembershipSnapshot().isEmpty()
        || protection.getOriginalEntitlementSnapshot().isEmpty()) throw invalid();
    id(original.getOperationId());
    id(original.getCreateCharacterRequestId());
    requireConnectScope(original.getConnectScopeId());
    id(original.getAuthenticatedAccountId());
    instant(protection.getAcquiredAt());
    var bytes = original.getOriginalAction().toByteArray();
    var action = CharacterCreateOperatorActionCodec.decode(bytes);
    if (!Arrays.equals(bytes, CharacterCreateOperatorActionCodec.encode(action))
        || !namespace.equals(action.scope().targetNamespace())
        || !original.getAuthenticatedAccountId().equals(action.target().accountId())
        || !original
            .getMutationDigest()
            .equals(CharacterCreateOperatorActionCodec.mutationDigest(action))
        || !action.mutation().catalog().visible()
        || !action.mutation().catalog().publicProduction()) throw invalid();
    return action;
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
      var id = UUID.fromString(value);
      if (id.equals(new UUID(0L, 0L)) || !id.toString().equals(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  public static Instant instant(String value) {
    try {
      var instant = Instant.parse(value);
      if (!instant.toString().equals(value)) throw invalid();
      return instant;
    } catch (IllegalArgumentException | java.time.format.DateTimeParseException malformed) {
      throw invalid();
    }
  }

  static RuntimeException invalid() {
    return Status.INVALID_ARGUMENT
        .withDescription("Exact original CHARACTER_CREATE binding required")
        .asRuntimeException();
  }
}
