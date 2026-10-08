package net.firedevops.firemud.common.account;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Immutable, non-admitting Account evidence for public-production preseeded actor staging. */
public record AccountActorStagingEligibilityEvidence(
    int schemaVersion,
    String targetNamespace,
    UUID requestId,
    UUID canonicalAccountId,
    UUID canonicalTenantId,
    Purpose purpose,
    Currentness currentness,
    Instant observedAt,
    Decision decision,
    String accountUuidProvenance,
    String accountLifecycleState,
    String membershipLifecycleState,
    boolean gameplayAdmissionAllowed,
    String membershipAuthorityProvenance,
    long membershipVersion,
    long membershipAuthorityGeneration,
    String tenantProvenanceKind,
    UUID tenantSourceOperationId,
    String tenantProvenanceDigest,
    long membershipEventSequence,
    UUID membershipEventId,
    String membershipEventDigest,
    boolean lastTransitionInvalidated,
    String eligibilityDecisionDigest,
    String authoritySnapshotDigest) {
  public static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern BARE_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SOURCE_SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final List<String> ACCOUNT_PROVENANCES =
      List.of("ACCOUNT_V29_MIGRATION", "ACCOUNT_REPOSITORY_INSERT", "ACCOUNT_DATABASE_INSERT");
  private static final List<String> ACCOUNT_LIFECYCLE_STATES =
      List.of("ACTIVE", "SECURITY_LOCKED", "DEACTIVATED_PENDING_DELETE", "DELETED");

  public AccountActorStagingEligibilityEvidence {
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported actor-staging evidence schema version");
    }
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("Target namespace must be one canonical DNS label");
    }
    requireNonNil(requestId, "requestId");
    requireNonNil(canonicalAccountId, "canonicalAccountId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(tenantSourceOperationId, "tenantSourceOperationId");
    requireNonNil(membershipEventId, "membershipEventId");
    if (purpose != Purpose.PUBLIC_PRODUCTION_STAGING_ONLY) {
      throw new IllegalArgumentException("Only public-production staging purpose is supported");
    }
    if (currentness != Currentness.CURRENT_AT_REVALIDATION) {
      throw new IllegalArgumentException("Current Account snapshot evidence is required");
    }
    Objects.requireNonNull(observedAt, "observedAt is required");
    Objects.requireNonNull(decision, "decision is required");
    Objects.requireNonNull(accountUuidProvenance, "Account UUID provenance is required");
    Objects.requireNonNull(accountLifecycleState, "Account lifecycle state is required");
    Objects.requireNonNull(membershipLifecycleState, "membership lifecycle state is required");
    Objects.requireNonNull(
        membershipAuthorityProvenance, "membership authority provenance is required");
    Objects.requireNonNull(tenantProvenanceKind, "tenant provenance kind is required");
    Objects.requireNonNull(tenantProvenanceDigest, "tenant provenance digest is required");
    Objects.requireNonNull(membershipEventDigest, "membership event digest is required");
    Objects.requireNonNull(eligibilityDecisionDigest, "eligibility decision digest is required");
    Objects.requireNonNull(authoritySnapshotDigest, "authority snapshot digest is required");
    requireMember(accountUuidProvenance, ACCOUNT_PROVENANCES, "Account UUID provenance");
    requireMember(accountLifecycleState, ACCOUNT_LIFECYCLE_STATES, "Account lifecycle state");
    if (!"ACTIVE".equals(membershipLifecycleState)
        && !"INACTIVE".equals(membershipLifecycleState)) {
      throw new IllegalArgumentException("Membership lifecycle state is not recognized");
    }
    if (!"EXPLICIT_JOIN".equals(membershipAuthorityProvenance)) {
      throw new IllegalArgumentException(
          "Membership authority provenance is not current JOIN evidence");
    }
    if (membershipVersion <= 0L
        || membershipAuthorityGeneration <= 0L
        || membershipEventSequence <= 0L) {
      throw new IllegalArgumentException("Positive membership authority counters are required");
    }
    if (!"FRESH_GAME_DESIGN".equals(tenantProvenanceKind)) {
      throw new IllegalArgumentException("Fresh canonical tenant provenance is required");
    }
    requireDigest(tenantProvenanceDigest, SOURCE_SHA256, "tenant provenance digest");
    requireDigest(membershipEventDigest, SOURCE_SHA256, "membership event digest");
    requireDigest(eligibilityDecisionDigest, BARE_SHA256, "eligibility decision digest");
    requireDigest(authoritySnapshotDigest, BARE_SHA256, "authority snapshot digest");
    requireStrictUtf8(targetNamespace, "target namespace");
    requireStrictUtf8(accountUuidProvenance, "Account UUID provenance");
    requireStrictUtf8(accountLifecycleState, "Account lifecycle state");
    requireStrictUtf8(membershipLifecycleState, "membership lifecycle state");
    requireStrictUtf8(membershipAuthorityProvenance, "membership authority provenance");
    requireStrictUtf8(tenantProvenanceKind, "tenant provenance kind");
    requireStrictUtf8(tenantProvenanceDigest, "tenant provenance digest");
    requireStrictUtf8(membershipEventDigest, "membership event digest");

    Decision expectedDecision =
        "ACTIVE".equals(accountLifecycleState)
                && "ACTIVE".equals(membershipLifecycleState)
                && gameplayAdmissionAllowed
            ? Decision.STAGING_ELIGIBLE
            : Decision.STAGING_INELIGIBLE;
    if (decision != expectedDecision) {
      throw new IllegalArgumentException("Decision does not match the current Account snapshot");
    }
    String expectedAuthorityDigest =
        authoritySnapshotDigestFor(
            fields(
                schemaVersion,
                targetNamespace,
                requestId,
                canonicalAccountId,
                canonicalTenantId,
                purpose,
                currentness,
                observedAt,
                decision,
                accountUuidProvenance,
                accountLifecycleState,
                membershipLifecycleState,
                gameplayAdmissionAllowed,
                membershipAuthorityProvenance,
                membershipVersion,
                membershipAuthorityGeneration,
                tenantProvenanceKind,
                tenantSourceOperationId,
                tenantProvenanceDigest,
                membershipEventSequence,
                membershipEventId,
                membershipEventDigest,
                lastTransitionInvalidated));
    if (!expectedAuthorityDigest.equals(authoritySnapshotDigest)) {
      throw new IllegalArgumentException("Authority snapshot digest does not match its fields");
    }
    String expectedDecisionDigest =
        eligibilityDecisionDigestFor(
            targetNamespace,
            requestId,
            canonicalAccountId,
            canonicalTenantId,
            purpose,
            currentness,
            decision,
            expectedAuthorityDigest);
    if (!expectedDecisionDigest.equals(eligibilityDecisionDigest)) {
      throw new IllegalArgumentException("Eligibility decision digest does not match its fields");
    }
  }

  /** Creates the two independently domain-separated digests for a verified owner snapshot. */
  public static AccountActorStagingEligibilityEvidence seal(
      String targetNamespace,
      UUID requestId,
      UUID canonicalAccountId,
      UUID canonicalTenantId,
      Purpose purpose,
      Instant observedAt,
      String accountUuidProvenance,
      String accountLifecycleState,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      String membershipAuthorityProvenance,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest,
      long membershipEventSequence,
      UUID membershipEventId,
      String membershipEventDigest,
      boolean lastTransitionInvalidated) {
    Decision decision =
        "ACTIVE".equals(accountLifecycleState)
                && "ACTIVE".equals(membershipLifecycleState)
                && gameplayAdmissionAllowed
            ? Decision.STAGING_ELIGIBLE
            : Decision.STAGING_INELIGIBLE;
    String authorityDigest =
        authoritySnapshotDigestFor(
            fields(
                SCHEMA_VERSION,
                targetNamespace,
                requestId,
                canonicalAccountId,
                canonicalTenantId,
                purpose,
                Currentness.CURRENT_AT_REVALIDATION,
                observedAt,
                decision,
                accountUuidProvenance,
                accountLifecycleState,
                membershipLifecycleState,
                gameplayAdmissionAllowed,
                membershipAuthorityProvenance,
                membershipVersion,
                membershipAuthorityGeneration,
                tenantProvenanceKind,
                tenantSourceOperationId,
                tenantProvenanceDigest,
                membershipEventSequence,
                membershipEventId,
                membershipEventDigest,
                lastTransitionInvalidated));
    String decisionDigest =
        eligibilityDecisionDigestFor(
            targetNamespace,
            requestId,
            canonicalAccountId,
            canonicalTenantId,
            purpose,
            Currentness.CURRENT_AT_REVALIDATION,
            decision,
            authorityDigest);
    return new AccountActorStagingEligibilityEvidence(
        SCHEMA_VERSION,
        targetNamespace,
        requestId,
        canonicalAccountId,
        canonicalTenantId,
        purpose,
        Currentness.CURRENT_AT_REVALIDATION,
        observedAt,
        decision,
        accountUuidProvenance,
        accountLifecycleState,
        membershipLifecycleState,
        gameplayAdmissionAllowed,
        membershipAuthorityProvenance,
        membershipVersion,
        membershipAuthorityGeneration,
        tenantProvenanceKind,
        tenantSourceOperationId,
        tenantProvenanceDigest,
        membershipEventSequence,
        membershipEventId,
        membershipEventDigest,
        lastTransitionInvalidated,
        decisionDigest,
        authorityDigest);
  }

  public enum Purpose {
    PUBLIC_PRODUCTION_STAGING_ONLY
  }

  public enum Currentness {
    CURRENT_AT_REVALIDATION
  }

  public enum Decision {
    STAGING_ELIGIBLE,
    STAGING_INELIGIBLE
  }

  private static List<Field> fields(
      int schemaVersion,
      String targetNamespace,
      UUID requestId,
      UUID canonicalAccountId,
      UUID canonicalTenantId,
      Purpose purpose,
      Currentness currentness,
      Instant observedAt,
      Decision decision,
      String accountUuidProvenance,
      String accountLifecycleState,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      String membershipAuthorityProvenance,
      long membershipVersion,
      long membershipAuthorityGeneration,
      String tenantProvenanceKind,
      UUID tenantSourceOperationId,
      String tenantProvenanceDigest,
      long membershipEventSequence,
      UUID membershipEventId,
      String membershipEventDigest,
      boolean lastTransitionInvalidated) {
    return List.of(
        new Field("schemaVersion", Integer.toString(schemaVersion)),
        new Field("targetNamespace", targetNamespace),
        new Field("requestId", requestId.toString()),
        new Field("canonicalAccountId", canonicalAccountId.toString()),
        new Field("canonicalTenantId", canonicalTenantId.toString()),
        new Field("purpose", purpose.name()),
        new Field("currentness", currentness.name()),
        new Field("observedAtEpochSecond", Long.toString(observedAt.getEpochSecond())),
        new Field("observedAtNano", Integer.toString(observedAt.getNano())),
        new Field("decision", decision.name()),
        new Field("accountUuidProvenance", accountUuidProvenance),
        new Field("accountLifecycleState", accountLifecycleState),
        new Field("membershipLifecycleState", membershipLifecycleState),
        new Field("gameplayAdmissionAllowed", Boolean.toString(gameplayAdmissionAllowed)),
        new Field("membershipAuthorityProvenance", membershipAuthorityProvenance),
        new Field("membershipVersion", Long.toString(membershipVersion)),
        new Field("membershipAuthorityGeneration", Long.toString(membershipAuthorityGeneration)),
        new Field("tenantProvenanceKind", tenantProvenanceKind),
        new Field("tenantSourceOperationId", tenantSourceOperationId.toString()),
        new Field("tenantProvenanceDigest", tenantProvenanceDigest),
        new Field("membershipEventSequence", Long.toString(membershipEventSequence)),
        new Field("membershipEventId", membershipEventId.toString()),
        new Field("membershipEventDigest", membershipEventDigest),
        new Field("lastTransitionInvalidated", Boolean.toString(lastTransitionInvalidated)));
  }

  private static String authoritySnapshotDigestFor(List<Field> fields) {
    return digest("firemud.account.actor-staging.authority-snapshot/v1", fields);
  }

  private static String eligibilityDecisionDigestFor(
      String namespace,
      UUID requestId,
      UUID accountId,
      UUID tenantId,
      Purpose purpose,
      Currentness currentness,
      Decision decision,
      String authoritySnapshotDigest) {
    return digest(
        "firemud.account.actor-staging.eligibility-decision/v1",
        List.of(
            new Field("targetNamespace", namespace),
            new Field("requestId", requestId.toString()),
            new Field("canonicalAccountId", accountId.toString()),
            new Field("canonicalTenantId", tenantId.toString()),
            new Field("purpose", purpose.name()),
            new Field("currentness", currentness.name()),
            new Field("decision", decision.name()),
            new Field("authoritySnapshotDigest", authoritySnapshotDigest)));
  }

  private static String digest(String domain, List<Field> fields) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeUtf8(output, domain);
        output.writeInt(fields.size());
        for (Field field : fields) {
          writeUtf8(output, field.name());
          writeUtf8(output, field.value());
        }
      }
      return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode Account actor-staging evidence", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void writeUtf8(DataOutputStream output, String value) throws IOException {
    ByteBuffer encoded;
    try {
      encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Evidence contains malformed UTF-16", exception);
    }
    byte[] valueBytes = new byte[encoded.remaining()];
    encoded.get(valueBytes);
    output.writeInt(valueBytes.length);
    output.write(valueBytes);
  }

  private static void requireStrictUtf8(String value, String label) {
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(label + " is not valid UTF-8", exception);
    }
  }

  private static String hex(byte[] digest) {
    StringBuilder value = new StringBuilder(digest.length * 2);
    for (byte octet : digest) {
      value.append(Character.forDigit((octet >>> 4) & 0x0f, 16));
      value.append(Character.forDigit(octet & 0x0f, 16));
    }
    return value.toString();
  }

  private static void requireDigest(String value, Pattern pattern, String label) {
    if (value == null || !pattern.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be canonical lowercase SHA-256 hex");
    }
  }

  private static void requireMember(String value, List<String> allowed, String label) {
    if (value == null || !allowed.contains(value)) {
      throw new IllegalArgumentException(label + " is not recognized");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a nonnil UUID");
    }
  }

  private record Field(String name, String value) {
    private Field {
      Objects.requireNonNull(name);
      Objects.requireNonNull(value);
    }
  }
}
