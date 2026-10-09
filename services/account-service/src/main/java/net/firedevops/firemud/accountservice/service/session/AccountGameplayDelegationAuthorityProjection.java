package net.firedevops.firemud.accountservice.service.session;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;

/**
 * Converts exact owner-read issuer and Account source heads to their canonical projections.
 *
 * <p>The returned bytes are informational source projections. They do not authorize a token or
 * establish freshness, publication, or admission state.
 */
public final class AccountGameplayDelegationAuthorityProjection {
  public static final String ISSUER_KEY_PREFIX = "session:auth:generation:issuer:";
  public static final String ACCOUNT_KEY_PREFIX = "session:auth:generation:account:";
  public static final int MAX_PROJECTION_BYTES = 8 * 1024;

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  private AccountGameplayDelegationAuthorityProjection() {}

  /** Encodes exact source DTOs for the two projection keys; these bytes are not authority proof. */
  public static byte[][] canonicalProjectionPair(IssuerAccountSourceSnapshot source) {
    Objects.requireNonNull(source, "Account authority source snapshot is required");
    var canonicalIssuer = source.canonicalIssuerProjection();
    if (canonicalIssuer == null
        || !source.issuer().scope().issuerId().equals(canonicalIssuer.issuerId())
        || !Long.toString(source.issuer().generation())
            .equals(canonicalIssuer.issuerAuthGeneration())
        || !Long.toString(source.issuer().sourceVersion()).equals(canonicalIssuer.sourceVersion())
        || !source.issuer().checkpoint().outboxStreamKey().equals(canonicalIssuer.outboxStreamKey())
        || !Long.toString(source.issuer().checkpoint().sequence())
            .equals(canonicalIssuer.lastAppliedSourceOutboxSequence())
        || !source
            .issuer()
            .checkpoint()
            .sourceEventId()
            .equals(canonicalIssuer.lastAppliedSourceEventId())
        || !source
            .issuer()
            .checkpoint()
            .sourceEventDigest()
            .equals(canonicalIssuer.lastAppliedSourceEventDigest())) {
      throw new ProjectionUnavailableException();
    }
    ProjectionValue issuer =
        ProjectionValue.decode(
            canonicalIssuer.toJson().getBytes(StandardCharsets.UTF_8),
            "issuer",
            canonicalIssuer.issuerId());
    AccountGenerationProjection canonicalAccount = source.canonicalAccountProjection();
    if (canonicalAccount == null
        || !source.account().scope().accountId().toString().equals(canonicalAccount.accountId())
        || !Long.toString(source.account().generation())
            .equals(canonicalAccount.accountAuthorityGeneration())
        || !Long.toString(source.account().sourceVersion()).equals(canonicalAccount.sourceVersion())
        || !source
            .account()
            .checkpoint()
            .outboxStreamKey()
            .equals(canonicalAccount.outboxStreamKey())
        || !Long.toString(source.account().checkpoint().sequence())
            .equals(canonicalAccount.outboxSequence())
        || !source.issuanceFence().equals(source.account().issuanceFence())) {
      throw new ProjectionUnavailableException();
    }
    ProjectionValue account =
        ProjectionValue.decode(
            canonicalAccount.toJson().getBytes(StandardCharsets.UTF_8),
            "account",
            canonicalAccount.accountId());
    return new byte[][] {issuer.canonicalBytes(), account.canonicalBytes()};
  }

  /** Strict canonical non-authorizing value stored at one canonical authority key. */
  public static final class ProjectionValue {
    private static final String ISSUER = "issuer";
    private static final String ACCOUNT = "account";

    private final String scope;
    private final String scopeId;
    private final BigInteger generation;
    private final String digest;
    private final byte[] canonicalBytes;

    private ProjectionValue(
        String scope, String scopeId, BigInteger generation, String digest, byte[] canonicalBytes) {
      this.scope = scope;
      this.scopeId = scopeId;
      this.generation = generation;
      this.digest = digest;
      this.canonicalBytes = canonicalBytes.clone();
    }

    /** Strictly parses canonical owner projection bytes and verifies their preimage digest. */
    public static ProjectionValue decode(
        byte[] bytes, String expectedScope, String expectedScopeId) {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_PROJECTION_BYTES) {
        throw new ProjectionUnavailableException();
      }
      try {
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        if (ACCOUNT.equals(expectedScope)) {
          AccountGenerationProjection account = AccountGenerationProjection.parse(text);
          if (!expectedScopeId.equals(account.accountId())) {
            throw new ProjectionUnavailableException();
          }
          return new ProjectionValue(
              ACCOUNT, account.accountId(), account.generationValue(), sha256(bytes), bytes);
        }
        if (!ISSUER.equals(expectedScope)) throw new ProjectionUnavailableException();
        IssuerGenerationProjection issuer = IssuerGenerationProjection.parse(text);
        if (!expectedScopeId.equals(issuer.issuerId())) throw new ProjectionUnavailableException();
        return new ProjectionValue(
            ISSUER, issuer.issuerId(), issuer.generationValue(), sha256(bytes), bytes);
      } catch (ProjectionUnavailableException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new ProjectionUnavailableException();
      }
    }

    public String scope() {
      return scope;
    }

    public String scopeId() {
      return scopeId;
    }

    public long generation() {
      return generation.longValueExact();
    }

    public BigInteger generationValue() {
      return generation;
    }

    public String digest() {
      return digest;
    }

    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    @Override
    public String toString() {
      return "AccountAuthorityProjection[redacted]";
    }
  }

  public static final class ProjectionUnavailableException extends IllegalStateException {
    public ProjectionUnavailableException() {
      super("Account authority projection is unavailable or inconsistent");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new ProjectionUnavailableException();
    }
  }
}
