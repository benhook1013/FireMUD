package net.firedevops.firemud.entitymanagement.charactercreate;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse;
import net.firedevops.firemud.entitymanagement.v1.CharacterCreateProtectionReference;

/**
 * Distinct catalog/pointer/active-target creation protection. Roster-reference, StartSession and
 * initial-admission readers cannot implement this contract. No positive production adapter exists.
 */
@FunctionalInterface
public interface GameSessionCharacterCreateProtectionReader {
  Readback readOriginal(
      ReadOriginalCharacterCreateProtectionResponse accountProtection,
      CharacterCreateProtectionReference reference);

  record Readback(
      ReadOriginalCharacterCreateProtectionResponse accountProtection,
      CharacterCreateProtectionReference reference,
      String acquiredAt,
      ByteString originalOwnerReadback) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Protobuf messages and ByteString are immutable.")
    public Readback {
      EntityCharacterCreateBindings.exact(accountProtection);
      EntityCharacterCreateBindings.requireReference(reference);
      EntityCharacterCreateBindings.instant(acquiredAt);
      if (originalOwnerReadback == null
          || originalOwnerReadback.isEmpty()
          || originalOwnerReadback.size()
              > EntityCharacterCreateBindings.MAX_GAME_SESSION_RESPONSE_BYTES) {
        throw EntityCharacterCreateBindings.invalid();
      }
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public ReadOriginalCharacterCreateProtectionResponse accountProtection() {
      return accountProtection;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Protobuf messages are immutable.")
    public CharacterCreateProtectionReference reference() {
      return reference;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "ByteString is immutable.")
    public ByteString originalOwnerReadback() {
      return originalOwnerReadback;
    }
  }

  static GameSessionCharacterCreateProtectionReader unavailable() {
    return (account, reference) -> {
      throw Status.UNAVAILABLE
          .withDescription("Genuine Game Session CHARACTER_CREATE owner protection is unavailable")
          .asRuntimeException();
    };
  }
}
