package net.firedevops.firemud.gamesession.binding;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Exact versioned Redis set member for one tenant-qualified canonical gameplay binding. */
public record CanonicalGameplayAccountIndexMember(
    CanonicalGameplayBindingRef bindingRef, BigInteger bindingGeneration, UUID accountIndexFence) {
  private static final String PREFIX = "accountIndexMember/v1:";

  public CanonicalGameplayAccountIndexMember {
    Objects.requireNonNull(bindingRef, "bindingRef");
    Objects.requireNonNull(bindingGeneration, "bindingGeneration");
    Objects.requireNonNull(accountIndexFence, "accountIndexFence");
    if (bindingGeneration.signum() <= 0) {
      throw new IllegalArgumentException("bindingGeneration must be positive");
    }
    if (accountIndexFence.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("accountIndexFence must not be nil");
    }
  }

  public static CanonicalGameplayAccountIndexMember of(
      CanonicalGameplayBindingInventoryEntry binding) {
    Objects.requireNonNull(binding, "binding");
    return new CanonicalGameplayAccountIndexMember(
        binding.bindingRef(), binding.bindingGeneration(), binding.accountIndexFence());
  }

  /**
   * Encodes the specified member tuple without normalizing its canonical bindingRef bytes.
   * Base64url makes the member unambiguous while retaining a delimiter-safe generation/fence suffix
   * for the single-key Lua stale-generation guard.
   */
  public String value() {
    String encodedRef = Base64.getUrlEncoder().withoutPadding().encodeToString(bindingRef.bytes());
    return PREFIX + encodedRef + ":" + bindingGeneration + ":" + accountIndexFence;
  }
}
