package net.firedevops.firemud.accountservice.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;

/** Exact owner-local fresh-birth evidence; this value grants no admission or activation. */
public record AccountPlatformRestrictionBirthSource(
    UUID accountId,
    long accountSourceNumericId,
    long accountInsertTransactionId,
    ScopeState birthAuthority,
    ScopeState currentAuthority,
    CategorySource accountSecurityLock,
    CategorySource platformAccessBan) {
  public AccountPlatformRestrictionBirthSource {
    Objects.requireNonNull(accountId, "Account UUID is required");
    if (accountId.version() != 4
        || accountId.variant() != 2
        || accountSourceNumericId <= 0
        || accountInsertTransactionId <= 0) {
      throw new IllegalArgumentException("Exact fresh Account identity is required");
    }
    requireAccountAuthority(accountId, birthAuthority);
    requireAccountAuthority(accountId, currentAuthority);
    if (birthAuthority.generation() != 1
        || birthAuthority.sourceVersion() != 1
        || birthAuthority.issuanceFence().value() != 1
        || birthAuthority.issuanceFence().sourceVersion() != 1) {
      throw new IllegalArgumentException("Fresh birth requires the initial Account authority");
    }
    requireCategory(accountId, accountSecurityLock, Category.ACCOUNT_SECURITY_LOCK);
    requireCategory(accountId, platformAccessBan, Category.PLATFORM_ACCESS_BAN);
    for (CategorySource source : new CategorySource[] {accountSecurityLock, platformAccessBan}) {
      if (!source
          .payloadDigest()
          .equals(
              birthDigest(
                  accountId,
                  accountSourceNumericId,
                  accountInsertTransactionId,
                  birthAuthority,
                  source.category(),
                  source.operationId(),
                  source.resultId(),
                  source.eventId()))) {
        throw new IllegalArgumentException("Restriction birth payload digest differs");
      }
    }
    if (accountSecurityLock.operationId().equals(platformAccessBan.operationId())
        || accountSecurityLock.resultId().equals(platformAccessBan.resultId())
        || accountSecurityLock.eventId().equals(platformAccessBan.eventId())) {
      throw new IllegalArgumentException("Category birth identities must be independent");
    }
  }

  private static void requireAccountAuthority(UUID accountId, ScopeState authority) {
    Objects.requireNonNull(authority, "Account authority is required");
    if (!AuthorityScope.account(accountId).equals(authority.scope())
        || authority.issuanceFence() == null
        || !accountId.equals(authority.issuanceFence().accountId())) {
      throw new IllegalArgumentException("Account authority identity differs");
    }
  }

  private static void requireCategory(UUID accountId, CategorySource source, Category expected) {
    Objects.requireNonNull(source, "Explicit category source is required");
    if (source.category() != expected
        || !source.outboxStreamKey().equals(streamKey(accountId, expected))) {
      throw new IllegalArgumentException("Exact category scope is required");
    }
  }

  public static String streamKey(UUID accountId, Category category) {
    return "account:restriction-birth:v1:" + accountId + "/" + category.storageValue();
  }

  /**
   * Closed UTF-8 newline-separated birth preimage, with one final newline. Exact order: schema,
   * account UUID, private numeric source ID, provenance, insert txid, category, source kind,
   * restriction state, revision, enforcement epoch, birth generation, generation source version,
   * fence, fence source version, operation ID, result ID, event ID, stream key, outbox sequence.
   * Every variable field is a canonical UUID, positive decimal, or fixed closed vocabulary.
   */
  public static String birthDigest(
      UUID accountId,
      long numericId,
      long transactionId,
      ScopeState birth,
      Category category,
      UUID operationId,
      UUID resultId,
      UUID eventId) {
    String preimage =
        String.join(
                "\n",
                "account-restriction-birth/v1",
                accountId.toString(),
                Long.toString(numericId),
                "ACCOUNT_REPOSITORY_INSERT",
                Long.toString(transactionId),
                category.storageValue(),
                "CATEGORY",
                "NONRESTRICTED",
                "1",
                "1",
                Long.toString(birth.generation()),
                Long.toString(birth.sourceVersion()),
                Long.toString(birth.issuanceFence().value()),
                Long.toString(birth.issuanceFence().sourceVersion()),
                operationId.toString(),
                resultId.toString(),
                eventId.toString(),
                streamKey(accountId, category),
                "1")
            + "\n";
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(preimage.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("Required SHA-256 digest is unavailable", unavailable);
    }
  }

  public enum Category {
    ACCOUNT_SECURITY_LOCK("account_security_lock"),
    PLATFORM_ACCESS_BAN("platform_access_ban");

    private final String storageValue;

    Category(String storageValue) {
      this.storageValue = storageValue;
    }

    public String storageValue() {
      return storageValue;
    }
  }

  public enum SourceKind {
    CATEGORY
  }

  public enum RestrictionState {
    NONRESTRICTED
  }

  /** A positive, explicit category revision/result, never inferred from absent rows. */
  public record CategorySource(
      Category category,
      SourceKind sourceKind,
      RestrictionState restrictionState,
      long revision,
      long enforcementEpoch,
      UUID operationId,
      UUID resultId,
      UUID eventId,
      String payloadDigest,
      String outboxStreamKey,
      long outboxSequence) {
    public CategorySource {
      Objects.requireNonNull(category, "Category is required");
      Objects.requireNonNull(sourceKind, "Explicit source kind is required");
      Objects.requireNonNull(restrictionState, "Explicit restriction state is required");
      requireUuid(operationId);
      requireUuid(resultId);
      requireUuid(eventId);
      if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Canonical restriction birth digest is required");
      }
      Objects.requireNonNull(outboxStreamKey, "Restriction outbox stream is required");
      if (revision != 1
          || enforcementEpoch != 1
          || outboxSequence != 1
          || operationId.equals(resultId)
          || operationId.equals(eventId)
          || resultId.equals(eventId)) {
        throw new IllegalArgumentException(
            "Exact independent birth result and outbox are required");
      }
    }

    private static void requireUuid(UUID value) {
      Objects.requireNonNull(value, "Server-generated birth identity is required");
      if (value.version() != 4 || value.variant() != 2) {
        throw new IllegalArgumentException("Canonical version-four birth identity is required");
      }
    }
  }
}
