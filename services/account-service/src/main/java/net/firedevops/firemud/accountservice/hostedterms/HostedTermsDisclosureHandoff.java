package net.firedevops.firemud.accountservice.hostedterms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;

/**
 * Exact, immutable Account evidence prepared before an elective terms deadline is disclosed. This
 * value is not an authorization decision: its opaque authority bytes must come from a future
 * trusted Account verifier, and this class does not authenticate them.
 */
public record HostedTermsDisclosureHandoff(
    UUID handoffId,
    UUID requestId,
    Kind kind,
    String sourceKey,
    String predecessorDigest,
    String candidateDigest,
    Instant effectiveAt,
    List<SourceEvidence> sources,
    byte[] authenticatedAuthorityEvidence) {
  public static final String SCHEMA = "account-hosted-terms-disclosure-handoff/v1";
  private static final int MAX_SOURCES = 128;
  private static final int MAX_EVIDENCE_BYTES = 1_048_576;

  public enum Kind {
    CATALOG,
    ENVIRONMENT_BINDING
  }

  public HostedTermsDisclosureHandoff {
    HostedTermsEncoding.requireUuid(handoffId, "disclosure handoff");
    HostedTermsEncoding.requireUuid(requestId, "disclosure request");
    Objects.requireNonNull(kind, "disclosure handoff kind");
    String exactSourceKey = HostedTermsEncoding.requireText(sourceKey, 512);
    sourceKey = exactSourceKey;
    if (exactSourceKey.getBytes(StandardCharsets.UTF_8).length > 512) {
      throw new IllegalArgumentException(
          "Disclosure source key exceeds the Account lock-key bound");
    }
    HostedTermsEncoding.requireDigest(predecessorDigest);
    HostedTermsEncoding.requireDigest(candidateDigest);
    Objects.requireNonNull(effectiveAt, "disclosed effective instant");
    if (effectiveAt.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Effective instant must preserve PostgreSQL microsecond precision");
    }
    List<SourceEvidence> exactSources =
        Objects.requireNonNull(sources, "complete exact source vector").stream()
            .map(Objects::requireNonNull)
            .sorted(Comparator.comparing(SourceEvidence::key))
            .toList();
    if (exactSources.isEmpty()
        || exactSources.size() > MAX_SOURCES
        || exactSources.stream().map(SourceEvidence::key).distinct().count() != exactSources.size()
        || exactSources.stream().noneMatch(source -> source.key().equals(exactSourceKey))) {
      throw new IllegalArgumentException(
          "Distinct complete sorted source evidence including the changed source is required");
    }
    SourceEvidence changedSource =
        exactSources.stream()
            .filter(source -> source.key().equals(exactSourceKey))
            .findFirst()
            .orElseThrow();
    if (changedSource.kind() != SourceKind.HOSTED_TERMS) {
      throw new IllegalArgumentException("Disclosure source must be Account hosted terms evidence");
    }
    if (kind == Kind.CATALOG) {
      DraftAuthorizationFenceBinding.canonicalUuid(changedSource.scopeId());
      if (!sourceKey.equals("HOSTED_TERMS:" + changedSource.scopeId())) {
        throw new IllegalArgumentException(
            "Catalog disclosure source key must bind its exact scope");
      }
    } else if (!changedSource.scopeId().startsWith("environment-boundary/")) {
      throw new IllegalArgumentException(
          "Environment-binding disclosure source must name its exact boundary");
    }
    sources = List.copyOf(exactSources);
    authenticatedAuthorityEvidence =
        requireEvidence(authenticatedAuthorityEvidence, "authenticated authority evidence");
  }

  @Override
  public List<SourceEvidence> sources() {
    return List.copyOf(sources);
  }

  @Override
  public byte[] authenticatedAuthorityEvidence() {
    return authenticatedAuthorityEvidence.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof HostedTermsDisclosureHandoff that
        && Arrays.equals(canonicalBytes(), that.canonicalBytes());
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(canonicalBytes());
  }

  /** Closed length-framed bytes; the caller-provided evidence is retained exactly, not trusted. */
  public byte[] canonicalBytes() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(output, SCHEMA);
    DraftAuthorizationFenceBinding.frame(output, handoffId.toString());
    DraftAuthorizationFenceBinding.frame(output, requestId.toString());
    DraftAuthorizationFenceBinding.frame(output, kind.name());
    DraftAuthorizationFenceBinding.frame(output, sourceKey);
    DraftAuthorizationFenceBinding.frame(output, predecessorDigest);
    DraftAuthorizationFenceBinding.frame(output, candidateDigest);
    DraftAuthorizationFenceBinding.frame(output, effectiveAt.toString());
    DraftAuthorizationFenceBinding.frame(output, Integer.toString(sources.size()));
    for (SourceEvidence source : sources) {
      DraftAuthorizationFenceBinding.frame(output, source.canonicalBytes());
    }
    DraftAuthorizationFenceBinding.frame(output, authenticatedAuthorityEvidence);
    return output.toByteArray();
  }

  /** Decodes only this exact immutable frame; it does not authenticate the authority bytes. */
  public static HostedTermsDisclosureHandoff fromStored(byte[] stored) {
    DraftAuthorizationFenceBinding.FrameReader reader =
        new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(SCHEMA);
    UUID handoffId = canonicalUuid(reader.text());
    UUID requestId = canonicalUuid(reader.text());
    Kind kind = Kind.valueOf(reader.text());
    String sourceKey = reader.text();
    String predecessorDigest = reader.text();
    String candidateDigest = reader.text();
    String effectiveAtText = reader.text();
    String countText = reader.text();
    DraftAuthorizationFenceBinding.decimal(countText, false);
    int count;
    try {
      count = Integer.parseInt(countText);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Stored source count is out of range", invalid);
    }
    if (count > MAX_SOURCES || count > reader.remaining() / Integer.BYTES) {
      throw new IllegalArgumentException("Stored source vector is incomplete or too large");
    }
    java.util.ArrayList<SourceEvidence> sources = new java.util.ArrayList<>();
    for (int index = 0; index < count; index++) {
      sources.add(SourceEvidence.fromStored(reader.bytes()));
    }
    byte[] authorityEvidence = reader.bytes();
    reader.requireEnd();
    final Instant effectiveAt;
    try {
      effectiveAt = Instant.parse(effectiveAtText);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Stored effective instant is invalid", invalid);
    }
    HostedTermsDisclosureHandoff handoff =
        new HostedTermsDisclosureHandoff(
            handoffId,
            requestId,
            kind,
            sourceKey,
            predecessorDigest,
            candidateDigest,
            effectiveAt,
            sources,
            authorityEvidence);
    if (!Arrays.equals(stored, handoff.canonicalBytes())) {
      throw new IllegalArgumentException("Noncanonical stored disclosure handoff");
    }
    return handoff;
  }

  /** Exact authenticated owner result. The opaque evidence must be verified before construction. */
  public record DisclosureResult(
      UUID handoffId,
      UUID requestId,
      String bindingDigest,
      Outcome outcome,
      byte[] authenticatedEvidence) {
    private static final String RESULT_SCHEMA = "account-hosted-terms-disclosure-result/v1";

    public DisclosureResult {
      HostedTermsEncoding.requireUuid(handoffId, "disclosure handoff");
      HostedTermsEncoding.requireUuid(requestId, "disclosure request");
      HostedTermsEncoding.requireDigest(bindingDigest);
      Objects.requireNonNull(outcome, "authenticated disclosure outcome");
      authenticatedEvidence =
          requireEvidence(authenticatedEvidence, "authenticated result evidence");
    }

    @Override
    public byte[] authenticatedEvidence() {
      return authenticatedEvidence.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof DisclosureResult that
          && Arrays.equals(canonicalBytes(), that.canonicalBytes());
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(canonicalBytes());
    }

    public byte[] canonicalBytes() {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(output, RESULT_SCHEMA);
      DraftAuthorizationFenceBinding.frame(output, handoffId.toString());
      DraftAuthorizationFenceBinding.frame(output, requestId.toString());
      DraftAuthorizationFenceBinding.frame(output, bindingDigest);
      DraftAuthorizationFenceBinding.frame(output, outcome.name());
      DraftAuthorizationFenceBinding.frame(
          output, HostedTermsEncoding.digest(authenticatedEvidence));
      DraftAuthorizationFenceBinding.frame(output, authenticatedEvidence);
      return output.toByteArray();
    }

    public static DisclosureResult fromStored(byte[] stored) {
      DraftAuthorizationFenceBinding.FrameReader reader =
          new DraftAuthorizationFenceBinding.FrameReader(stored);
      reader.expect(RESULT_SCHEMA);
      DisclosureResult result =
          new DisclosureResult(
              canonicalUuid(reader.text()),
              canonicalUuid(reader.text()),
              reader.text(),
              Outcome.valueOf(reader.text()),
              readEvidence(reader));
      reader.requireEnd();
      if (!Arrays.equals(stored, result.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical stored disclosure result");
      }
      return result;
    }

    private static byte[] readEvidence(DraftAuthorizationFenceBinding.FrameReader reader) {
      String digest = reader.text();
      byte[] evidence = reader.bytes();
      if (!HostedTermsEncoding.digest(evidence).equals(digest)) {
        throw new IllegalArgumentException("Stored disclosure result evidence digest conflicts");
      }
      return evidence;
    }
  }

  public enum Outcome {
    DISCLOSED,
    DEFINITIVELY_NOT_DISCLOSED
  }

  private static UUID canonicalUuid(String value) {
    DraftAuthorizationFenceBinding.canonicalUuid(value);
    return UUID.fromString(value);
  }

  private static byte[] requireEvidence(byte[] evidence, String description) {
    byte[] exact = HostedTermsEncoding.requireBytes(evidence);
    if (exact.length > MAX_EVIDENCE_BYTES) {
      throw new IllegalArgumentException(description + " exceeds the durable evidence bound");
    }
    return exact;
  }
}
