package net.firedevops.firemud.accountservice.charactercreate;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;

/** Account's original immutable player creation request and separately bound issued scope. */
public record AccountCharacterCreateIntent(
    UUID operationId,
    UUID createCharacterRequestId,
    String connectScopeId,
    UUID accountId,
    byte[] actionBytes,
    String mutationDigest,
    byte[] originalSourceReadback) {
  public static final String PURPOSE = "CHARACTER_CREATE";
  public static final int MAX_SOURCE_READBACK_BYTES = 32 * 1024 * 1024;

  /** Matches the existing Account bootstrap JOIN and connect-token request bound. */
  public static final int MAX_CONNECT_SCOPE_LENGTH = 2048;

  public AccountCharacterCreateIntent {
    requireId(operationId);
    requireId(createCharacterRequestId);
    requireConnectScope(connectScopeId);
    requireId(accountId);
    Objects.requireNonNull(actionBytes);
    if (actionBytes.length == 0
        || actionBytes.length > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES) {
      throw new IllegalArgumentException("Bounded original CHARACTER_CREATE action required");
    }
    actionBytes = actionBytes.clone();
    CharacterCreateOperatorAction action = CharacterCreateOperatorActionCodec.decode(actionBytes);
    if (!Arrays.equals(actionBytes, CharacterCreateOperatorActionCodec.encode(action))
        || !accountId.toString().equals(action.target().accountId())
        || !GrpcPeerIdentity.isValidNamespace(action.scope().targetNamespace())
        || !action.mutation().catalog().visible()
        || !action.mutation().catalog().publicProduction()
        || !CharacterCreateOperatorActionCodec.mutationDigest(action).equals(mutationDigest)) {
      throw new IllegalArgumentException("Exact authenticated public-production creation required");
    }
    Objects.requireNonNull(originalSourceReadback);
    if (originalSourceReadback.length == 0
        || originalSourceReadback.length > MAX_SOURCE_READBACK_BYTES) {
      throw new IllegalArgumentException("Bounded exact original owner readback required");
    }
    originalSourceReadback = originalSourceReadback.clone();
  }

  public CharacterCreateOperatorAction action() {
    return CharacterCreateOperatorActionCodec.decode(actionBytes);
  }

  public String targetNamespace() {
    return action().scope().targetNamespace();
  }

  public UUID tenantId() {
    return UUID.fromString(action().scope().tenantId());
  }

  @Override
  public byte[] actionBytes() {
    return actionBytes.clone();
  }

  @Override
  public byte[] originalSourceReadback() {
    return originalSourceReadback.clone();
  }

  /** Includes the separately bound operation/source identities, not only the mutation digest. */
  public boolean sameBinding(AccountCharacterCreateIntent other) {
    return other != null
        && operationId.equals(other.operationId)
        && createCharacterRequestId.equals(other.createCharacterRequestId)
        && connectScopeId.equals(other.connectScopeId)
        && accountId.equals(other.accountId)
        && mutationDigest.equals(other.mutationDigest)
        && Arrays.equals(actionBytes, other.actionBytes)
        && Arrays.equals(originalSourceReadback, other.originalSourceReadback);
  }

  static void requireConnectScope(String scope) {
    if (scope == null || scope.isBlank() || scope.length() > MAX_CONNECT_SCOPE_LENGTH) {
      throw new IllegalArgumentException("Bounded nonblank issued connect scope required");
    }
    for (int index = 0; index < scope.length(); index++) {
      char value = scope.charAt(index);
      if (value == 0 || Character.isLowSurrogate(value)) {
        throw new IllegalArgumentException("Exact valid UTF-8 connect scope required");
      }
      if (Character.isHighSurrogate(value)
          && (++index == scope.length() || !Character.isLowSurrogate(scope.charAt(index)))) {
        throw new IllegalArgumentException("Exact valid UTF-8 connect scope required");
      }
    }
  }

  static void requireId(UUID id) {
    if (id == null || id.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical nonnil UUID required");
    }
  }
}
