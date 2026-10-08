package net.firedevops.firemud.common.publication;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact selected-publication freeze request and its committed World acknowledgement. */
public final class WorldSelectedDraftPublicationFreezeEvidence {
  private final Request request;
  private final Acknowledgement acknowledgement;

  WorldSelectedDraftPublicationFreezeEvidence(Request request, Acknowledgement acknowledgement) {
    this.request = Objects.requireNonNull(request, "request");
    this.acknowledgement = Objects.requireNonNull(acknowledgement, "acknowledgement");
    if (!request.equals(acknowledgement.request())) {
      throw new IllegalArgumentException("World freeze acknowledgement changed its exact request");
    }
  }

  public Request request() {
    return request;
  }

  public Acknowledgement acknowledgement() {
    return acknowledgement;
  }

  /** Complete exact request from Game Design; its Account bytes are correlation, not authority. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publicationRequestId,
      long expectedVersionStateEpoch,
      String requestDigest,
      byte[] accountPublicationAuthorizationBinding) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ACCOUNT_BINDING_BYTES = 1_048_576;
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public Request {
      if (schemaVersion != SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported World selected-freeze schema version");
      }
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical World workload namespace is required");
      }
      requireUuid(canonicalTenantId, "canonicalTenantId");
      requireUuid(canonicalVersionId, "canonicalVersionId");
      if (expectedVersionStateEpoch <= 0L) {
        throw new IllegalArgumentException("Expected Version state epoch must be positive");
      }
      if (requestDigest == null || !SHA256.matcher(requestDigest).matches()) {
        throw new IllegalArgumentException(
            "Request digest must be 64 lowercase hexadecimal digits");
      }
      if (accountPublicationAuthorizationBinding == null
          || accountPublicationAuthorizationBinding.length == 0
          || accountPublicationAuthorizationBinding.length > MAX_ACCOUNT_BINDING_BYTES) {
        throw new IllegalArgumentException(
            "Complete bounded Account publication binding is required");
      }
      accountPublicationAuthorizationBinding = accountPublicationAuthorizationBinding.clone();
      AccountPublicationAuthorizationBinding account =
          AccountPublicationAuthorizationBinding.fromStored(accountPublicationAuthorizationBinding);
      if (!Arrays.equals(accountPublicationAuthorizationBinding, account.canonicalBytes())) {
        throw new IllegalArgumentException("Account publication binding is not canonical");
      }
      var selection = account.input().selection();
      if (!account.tenantId().equals(canonicalTenantId)
          || !selection.intent().canonicalVersionId().equals(canonicalVersionId)
          || !selection.intent().publishRequestId().equals(publicationRequestId)
          || !selection
              .intent()
              .expectedVersionStateEpoch()
              .equals(Long.toString(expectedVersionStateEpoch))
          || !selection.digest().equals("sha256:" + requestDigest)) {
        throw new IllegalArgumentException(
            "World freeze request differs from the exact Account-bound immutable selection");
      }
      // Also validates the canonical full-version request identity and stable workflow syntax.
      PublicationDigestRequestBinding.full(
          canonicalTenantId.toString(),
          Long.toString(selection.target().gameDesignVersionRowId()),
          publicationRequestId);
    }

    public static Request create(
        String targetNamespace,
        UUID canonicalTenantId,
        UUID canonicalVersionId,
        String publicationRequestId,
        long expectedVersionStateEpoch,
        String requestDigest,
        AccountPublicationAuthorizationBinding accountBinding) {
      Objects.requireNonNull(accountBinding, "accountBinding");
      return new Request(
          SCHEMA_VERSION,
          targetNamespace,
          canonicalTenantId,
          canonicalVersionId,
          publicationRequestId,
          expectedVersionStateEpoch,
          requestDigest,
          accountBinding.canonicalBytes());
    }

    @Override
    public byte[] accountPublicationAuthorizationBinding() {
      return accountPublicationAuthorizationBinding.clone();
    }

    /** Reconstructs the complete canonical original Account publication order. */
    public AccountPublicationAuthorizationBinding accountBinding() {
      AccountPublicationAuthorizationBinding binding =
          AccountPublicationAuthorizationBinding.fromStored(accountPublicationAuthorizationBinding);
      if (!Arrays.equals(accountPublicationAuthorizationBinding, binding.canonicalBytes())) {
        throw new IllegalArgumentException("Account publication binding is not canonical");
      }
      return binding;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Request that
          && schemaVersion == that.schemaVersion
          && targetNamespace.equals(that.targetNamespace)
          && canonicalTenantId.equals(that.canonicalTenantId)
          && canonicalVersionId.equals(that.canonicalVersionId)
          && publicationRequestId.equals(that.publicationRequestId)
          && expectedVersionStateEpoch == that.expectedVersionStateEpoch
          && requestDigest.equals(that.requestDigest)
          && Arrays.equals(
              accountPublicationAuthorizationBinding, that.accountPublicationAuthorizationBinding);
    }

    @Override
    public int hashCode() {
      return 31
              * Objects.hash(
                  schemaVersion,
                  targetNamespace,
                  canonicalTenantId,
                  canonicalVersionId,
                  publicationRequestId,
                  expectedVersionStateEpoch,
                  requestDigest)
          + Arrays.hashCode(accountPublicationAuthorizationBinding);
    }

    private static void requireUuid(UUID value, String label) {
      if (value == null || NIL_UUID.equals(value)) {
        throw new IllegalArgumentException(label + " must be a non-nil UUID");
      }
    }
  }

  /** Acknowledgement of the committed FROZEN event only, never a terminal or admission result. */
  public record Acknowledgement(
      Request request,
      UUID intakeRequestId,
      long versionStateEpoch,
      UUID publicationFence,
      OwnerFreezePhase ownerFreezePhase,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {
    private static final UUID NIL_UUID = new UUID(0L, 0L);
    private static final Pattern CONTENT_DIGEST = Pattern.compile("[0-9a-f]{64}");

    public Acknowledgement {
      Objects.requireNonNull(request, "request");
      if (intakeRequestId == null || NIL_UUID.equals(intakeRequestId)) {
        throw new IllegalArgumentException(
            "World intake correlation must be a non-nil owner-produced UUID");
      }
      if (versionStateEpoch <= 0L || versionStateEpoch != request.expectedVersionStateEpoch()) {
        throw new IllegalArgumentException(
            "World-observed Version state epoch differs from the exact request expectation");
      }
      if (publicationFence == null || NIL_UUID.equals(publicationFence)) {
        throw new IllegalArgumentException("World publication fence must be a non-nil UUID");
      }
      if (ownerFreezePhase != OwnerFreezePhase.FROZEN) {
        throw new IllegalArgumentException("World freeze acknowledgement must be FROZEN");
      }
      UUID selectedCommitId =
          request.accountBinding().input().selection().selectedCommit().commitId();
      if (!selectedCommitId.toString().equals(appliedCommitId)) {
        throw new IllegalArgumentException(
            "World applied commit differs from the exact selected Draft commit");
      }
      if (contentDigest == null || !CONTENT_DIGEST.matcher(contentDigest).matches()) {
        throw new IllegalArgumentException(
            "World content digest must be canonical lowercase SHA-256");
      }
      if (digestSchemaVersion <= 0) {
        throw new IllegalArgumentException("World digest schema version must be positive");
      }
    }

    public String tenantId() {
      return request.canonicalTenantId().toString();
    }

    public String versionId() {
      return request.canonicalVersionId().toString();
    }

    public String publicationRequestId() {
      return request.publicationRequestId();
    }

    public String requestDigest() {
      return request.requestDigest();
    }

    public long versionStateEpoch() {
      return versionStateEpoch;
    }
  }

  public enum OwnerFreezePhase {
    FROZEN
  }
}
