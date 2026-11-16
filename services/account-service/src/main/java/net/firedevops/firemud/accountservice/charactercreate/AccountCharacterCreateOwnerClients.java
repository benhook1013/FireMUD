package net.firedevops.firemud.accountservice.charactercreate;

import io.grpc.Status;
import java.util.Objects;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorActionCodec;

/**
 * Exact-purpose dependencies for the unregistered Account producer. Implementations must perform
 * same-namespace authenticated owner reads and exact original-operation readback. Content digests
 * and caller-supplied evidence are never substitutes for those owner calls.
 */
public interface AccountCharacterCreateOwnerClients {
  /** Resolves actual catalog/pointer, authored public source and World lifecycle; never private. */
  ResolvedSource resolvePublicProduction(
      String namespace, AccountCharacterCreatePrincipal principal, String connectScopeId);

  /**
   * Re-reads the retained source at its original owner identities, without refreshing the target.
   */
  void requireExactOriginalSource(AccountCharacterCreateIntent intent);

  /**
   * Acquires the distinct Game Session catalog/pointer and World lifecycle protections and
   * delegates to Entity under the same immutable intent. Every partial acquisition must be durably
   * recorded by its genuine owner before the next step; this call supplies no terminal or release
   * authority.
   */
  void delegateProtected(
      AccountCharacterCreateIntent intent, AccountCharacterCreateRepository.Protection protection);

  record ResolvedSource(byte[] actionBytes, byte[] originalSourceReadback) {
    public ResolvedSource {
      Objects.requireNonNull(actionBytes);
      Objects.requireNonNull(originalSourceReadback);
      if (actionBytes.length == 0
          || actionBytes.length > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES
          || originalSourceReadback.length == 0
          || originalSourceReadback.length
              > AccountCharacterCreateIntent.MAX_SOURCE_READBACK_BYTES) {
        throw new IllegalArgumentException("Bounded exact creation owner source required");
      }
      actionBytes = actionBytes.clone();
      originalSourceReadback = originalSourceReadback.clone();
    }

    @Override
    public byte[] actionBytes() {
      return actionBytes.clone();
    }

    @Override
    public byte[] originalSourceReadback() {
      return originalSourceReadback.clone();
    }
  }

  /** No genuine cross-owner creation contracts/clients are registered by this foundation. */
  static AccountCharacterCreateOwnerClients unavailable() {
    return new AccountCharacterCreateOwnerClients() {
      @Override
      public ResolvedSource resolvePublicProduction(
          String namespace, AccountCharacterCreatePrincipal principal, String scope) {
        throw unavailableDependency();
      }

      @Override
      public void requireExactOriginalSource(AccountCharacterCreateIntent intent) {
        throw unavailableDependency();
      }

      @Override
      public void delegateProtected(
          AccountCharacterCreateIntent intent,
          AccountCharacterCreateRepository.Protection protection) {
        throw unavailableDependency();
      }
    };
  }

  private static RuntimeException unavailableDependency() {
    return Status.UNAVAILABLE
        .withDescription("Genuine CHARACTER_CREATE owner composition is unavailable")
        .asRuntimeException();
  }
}
