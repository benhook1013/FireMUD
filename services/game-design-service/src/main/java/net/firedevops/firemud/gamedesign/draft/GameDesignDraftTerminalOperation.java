package net.firedevops.firemud.gamedesign.draft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/**
 * Exact Account operation material paired with the complete Game Design commit binding.
 *
 * <p>This value validates identity and canonical bytes only. It does not authenticate the Account
 * source evidence or authorize a write; that decision belongs to the protected Account producer.
 */
public final class GameDesignDraftTerminalOperation {
  private final DraftAuthorizationFenceBinding accountBinding;
  private final DraftCommitBinding gameDesignBinding;
  private final byte[] accountBindingBytes;
  private final String accountBindingDigest;

  public GameDesignDraftTerminalOperation(DraftAuthorizationFenceBinding accountBinding) {
    this(accountBinding, bindingFrom(accountBinding));
  }

  public GameDesignDraftTerminalOperation(
      DraftAuthorizationFenceBinding accountBinding, DraftCommitBinding gameDesignBinding) {
    this.accountBinding = Objects.requireNonNull(accountBinding, "accountBinding");
    this.gameDesignBinding = Objects.requireNonNull(gameDesignBinding, "gameDesignBinding");
    this.accountBindingBytes = accountBinding.canonicalBytes();
    this.accountBindingDigest = sha256(accountBindingBytes);
    if (!Arrays.equals(accountBinding.gameDesignBinding(), gameDesignBinding.canonicalBytes())
        || !accountBinding.inputDigest().equals(gameDesignBinding.digest())
        || !accountBinding.requestId().equals(gameDesignBinding.requestId())
        || !accountBinding.commitId().equals(gameDesignBinding.commitId())
        || !accountBinding.tenantId().equals(gameDesignBinding.target().canonicalTenantId())
        || !accountBinding.versionId().equals(gameDesignBinding.target().canonicalVersionId())
        || !accountBinding.baseCommitId().equals(gameDesignBinding.baseCommitId())) {
      throw new IllegalArgumentException(
          "Account terminal operation differs from the exact complete Game Design binding");
    }
  }

  public DraftAuthorizationFenceBinding accountBinding() {
    return accountBinding;
  }

  public DraftCommitBinding gameDesignBinding() {
    return gameDesignBinding;
  }

  public byte[] accountBindingBytes() {
    return accountBindingBytes.clone();
  }

  public String accountBindingDigest() {
    return accountBindingDigest;
  }

  public boolean exactlyMatches(GameDesignDraftTerminalOperation other) {
    return other != null
        && accountBindingDigest.equals(other.accountBindingDigest)
        && Arrays.equals(accountBindingBytes, other.accountBindingBytes)
        && gameDesignBinding.digest().equals(other.gameDesignBinding.digest())
        && Arrays.equals(
            gameDesignBinding.canonicalBytes(), other.gameDesignBinding.canonicalBytes());
  }

  private static DraftCommitBinding bindingFrom(DraftAuthorizationFenceBinding accountBinding) {
    Objects.requireNonNull(accountBinding, "accountBinding");
    return DraftCommitBinding.fromStored(
        new String(accountBinding.gameDesignBinding(), StandardCharsets.UTF_8),
        accountBinding.inputDigest());
  }

  static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
