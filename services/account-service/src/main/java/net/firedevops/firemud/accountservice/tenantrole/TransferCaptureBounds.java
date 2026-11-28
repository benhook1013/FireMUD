package net.firedevops.firemud.accountservice.tenantrole;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Resource bounds and owner-lock ordering for an unregistered, non-authenticating transfer capture.
 * These checks do not establish membership, event provenance, actor permission or mutation
 * authority.
 */
public final class TransferCaptureBounds {
  public static final int MEMBER_COUNT = 2;
  public static final int MAX_ROLES_PER_MEMBER = 64;
  public static final int MAX_ROLE_UTF8_BYTES = 128;
  public static final int MAX_COMPONENT_BYTES = 16 * 1024;
  public static final int MAX_CAPTURE_BYTES = 256 * 1024;

  private TransferCaptureBounds() {}

  /** Same lexical UUID ordering used by Draft source-change Account owner locks. */
  public static List<UUID> orderedAccounts(UUID actor, UUID target) {
    requireAccount(actor);
    requireAccount(target);
    if (actor.equals(target)) {
      throw new IllegalArgumentException("Transfer capture requires two distinct Account owners");
    }
    return List.of(actor, target).stream().sorted(Comparator.comparing(UUID::toString)).toList();
  }

  /** Checks the envelope before a decoder can allocate component buffers. */
  public static byte[] copyEnvelope(byte[] encoded) {
    Objects.requireNonNull(encoded, "Capture bytes are required");
    if (encoded.length == 0 || encoded.length > MAX_CAPTURE_BYTES) {
      throw new IllegalArgumentException("Capture byte bound exceeded");
    }
    return Arrays.copyOf(encoded, encoded.length);
  }

  /** Check a wire length against both its component cap and actual remaining envelope. */
  public static int componentLength(int declaredLength, int remainingBytes) {
    if (declaredLength <= 0
        || declaredLength > MAX_COMPONENT_BYTES
        || remainingBytes < 0
        || declaredLength > remainingBytes) {
      throw new IllegalArgumentException("Capture component length is invalid");
    }
    return declaredLength;
  }

  private static void requireAccount(UUID account) {
    if (account == null || account.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("A canonical non-nil Account UUID is required");
    }
  }
}
