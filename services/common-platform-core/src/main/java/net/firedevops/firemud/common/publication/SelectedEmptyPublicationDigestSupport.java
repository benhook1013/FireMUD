package net.firedevops.firemud.common.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Shared framing, identity parsing, and retained World-freeze checks for selected-empty digests.
 */
public final class SelectedEmptyPublicationDigestSupport {
  private SelectedEmptyPublicationDigestSupport() {}

  /** Hashes ordered UTF-8 segments using the publication digest's byte-length framing. */
  public static String framedSha256(String domain, int schemaVersion, String... segments) {
    ByteArrayOutputStream preimage = new ByteArrayOutputStream();
    appendSegment(preimage, domain);
    appendSegment(preimage, Integer.toString(schemaVersion));
    for (String segment : segments) {
      appendSegment(preimage, segment);
    }
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Parses a canonical, nonzero UUID while retaining the caller's field-specific error text. */
  public static UUID canonicalUuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(field + " must be a canonical nonzero UUID");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(field + " must be a canonical nonzero UUID", malformed);
    }
  }

  /** Parses a canonical positive decimal long while retaining the caller's field-specific text. */
  public static long canonicalPositiveLong(String value, String field) {
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical positive decimal");
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException(
          field + " must be a canonical positive decimal", malformed);
    }
  }

  /** Requires the original publication request identity retained in the selected World freeze. */
  public static void requireOriginalPublicationRequest(
      PublicationDigestRequestBinding requested,
      UUID canonicalTenantId,
      long gameDesignVersionRowId,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      WorldSelectedDraftPublicationFreezeEvidence retainedFreeze,
      String ownerLabel) {
    var freezeRequest = retainedFreeze.request();
    var retainedSelection = freezeRequest.accountBinding().input().selection();
    long retainedGameDesignVersionRowId =
        retainedSelection.selectedCommit().target().gameDesignVersionRowId();
    if (gameDesignVersionRowId != retainedGameDesignVersionRowId) {
      throw new IllegalStateException(
          ownerLabel + " publication version row differs from the original retained World freeze");
    }
    var retainedPublicationRequest =
        PublicationDigestRequestBinding.full(
            freezeRequest.canonicalTenantId().toString(),
            Long.toString(retainedGameDesignVersionRowId),
            freezeRequest.publicationRequestId());
    if (!canonicalTenantId.equals(freezeRequest.canonicalTenantId())
        || !canonicalVersionId.equals(freezeRequest.canonicalVersionId())
        || !selectedCommitId.equals(retainedSelection.selectedCommit().commitId())
        || !selectedCommitId.toString().equals(retainedFreeze.acknowledgement().appliedCommitId())
        || requested.scopeKind() != PublicationDigestRequestBinding.ScopeKind.FULL_VERSION
        || !requested.tenantId().equals(retainedPublicationRequest.tenantId())
        || !requested.versionId().equals(retainedPublicationRequest.versionId())
        || !requested.publishRequestId().equals(retainedPublicationRequest.publishRequestId())
        || !requested
            .derivedWorkflowIdentity()
            .equals(retainedPublicationRequest.derivedWorkflowIdentity())
        || !requested.requestDigest().equals(retainedPublicationRequest.requestDigest())) {
      throw new IllegalStateException(
          ownerLabel + " publication request differs from the original retained World freeze");
    }
  }

  private static void appendSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }
}
