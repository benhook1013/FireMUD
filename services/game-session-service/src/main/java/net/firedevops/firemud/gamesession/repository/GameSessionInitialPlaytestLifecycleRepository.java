package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence;
import net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable initial FRESH playtest allocation, deliberately unregistered and non-admissible.
 *
 * <p>The composing owner must authenticate and authorize its real caller. A retained canonical
 * public source catalog supplies authored-world and published-build evidence only. It does not
 * supply private access, a completed playtest, owner manifests, isolation proof or admission. Every
 * successful new request allocates a new lifecycle and namespace internally and remains PREPARING.
 * Seeded/snapshot initialization, reset and admission are unavailable at this boundary.
 */
public class GameSessionInitialPlaytestLifecycleRepository {
  private static final String TABLE = "game_session_initial_playtest_preparations";
  private static final String REQUEST_SCHEMA = "game-session-initial-fresh-playtest-request/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalogs;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Validate the internal collaborator before use; no reference escapes or resource is acquired.")
  public GameSessionInitialPlaytestLifecycleRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.catalogs = new GameSessionCanonicalRealmCatalogRepository(dsl);
  }

  /** Allocates preparation identity after exact retained source/build qualification. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Allocation prepare(Request request) {
    requireOwnerTransaction(true);
    Objects.requireNonNull(request, "request");
    byte[] requestBytes = request.canonicalPreimage();
    String requestDigest = digest(requestBytes);
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "initial-playtest-request/" + request.requestId());
    Record prior =
        dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", request.requestId());
    if (prior != null) {
      if (!requestDigest.equals(prior.get("request_digest", String.class))
          || !Arrays.equals(requestBytes, prior.get("request_preimage", byte[].class))) {
        throw new IdempotencyConflictException();
      }
      Preparation original = preparation(prior);
      return new Allocation(original, true);
    }
    SourceReference source = qualifySource(request);
    if (!databaseNow().isBefore(request.forkExpiresAt())) {
      throw new IllegalArgumentException("Initial playtest fork expiry must be future");
    }
    Record allocated =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (request_id, request_preimage, request_digest, target_namespace, canonical_tenant_id, "
                + "world_slug, realm_slug, initialization_mode, preparation_state, playtest_state_generation, fork_expiry_second, "
                + "fork_expiry_nano, fork_expiry_text, source_catalog_realm_id, source_catalog_request_id, source_catalog_revision, "
                + "source_catalog_receipt_digest, source_intake_operation_id, source_intake_receipt_digest, source_operation_id, "
                + "source_evidence_digest, canonical_version_id, published_release_bundle_ref, published_release_bundle_digest, "
                + "source_policy_set_digest, source_policy_carrier_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'FRESH', 'PREPARING', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *",
            request.requestId(),
            requestBytes,
            requestDigest,
            request.targetNamespace(),
            request.tenantId(),
            request.worldSlug(),
            request.realmSlug(),
            request.forkExpiresAt().getEpochSecond(),
            request.forkExpiresAt().getNano(),
            request.forkExpiresAt().toString(),
            request.sourceCatalogRealmId(),
            request.sourceCatalogRequestId(),
            request.sourceCatalogRevision(),
            source.catalogReceiptDigest(),
            source.intakeOperationId(),
            source.intakeReceiptDigest(),
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            request.canonicalVersionId(),
            request.publishedReleaseBundleRef(),
            request.publishedReleaseBundleDigest(),
            source.policySetDigest(),
            source.policyCarrierDigest());
    if (allocated == null)
      throw new IllegalStateException("Initial playtest allocation returned no owner result");
    Preparation result = preparation(allocated);
    if (!result.request().equals(request))
      throw new IllegalStateException("Initial playtest request readback differs from original");
    return new Allocation(result, false);
  }

  /**
   * Exact lifecycle-qualified current metadata. INCOMPLETE and EXPIRED both remain non-admissible;
   * no selector-only lookup chooses a lifecycle or silently substitutes a newer preparation.
   */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<Snapshot> readExact(
      String targetNamespace,
      UUID tenantId,
      String worldSlug,
      String realmSlug,
      UUID playtestLifecycleId) {
    requireOwnerTransaction(false);
    requireNamespace(targetNamespace);
    requireNonNil(tenantId, "tenantId");
    requireSlug(worldSlug);
    requireSlug(realmSlug);
    requireNonNil(playtestLifecycleId, "playtestLifecycleId");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + TABLE
                + " WHERE target_namespace = ? AND canonical_tenant_id = ? "
                + "AND world_slug = ? AND realm_slug = ? AND playtest_lifecycle_id = ?",
            targetNamespace,
            tenantId,
            worldSlug,
            realmSlug,
            playtestLifecycleId);
    if (row == null) return Optional.empty();
    Preparation preparation = preparation(row);
    // The retained source is immutable and guarded by RESTRICT references. This read validates
    // the complete copied request/receipt without using a read as new allocation authority.
    Instant observedAt = databaseNow();
    ReadState state =
        observedAt.isBefore(preparation.request().forkExpiresAt())
            ? ReadState.INCOMPLETE
            : ReadState.EXPIRED;
    return Optional.of(new Snapshot(preparation, state, observedAt));
  }

  private SourceReference qualifySource(Request request) {
    Record row =
        dsl.fetchOne(
            "SELECT complete_policy_set_evidence, fixture_policy_request_bytes FROM game_session_canonical_realm_catalog "
                + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ? FOR SHARE",
            request.targetNamespace(),
            request.tenantId(),
            request.sourceCatalogRealmId());
    if (row == null
        || row.get("complete_policy_set_evidence", byte[].class) == null
        || row.get("fixture_policy_request_bytes", byte[].class) != null) {
      throw new SourceUnavailableException(
          "Exact retained Human canonical source catalog is unavailable");
    }
    CanonicalRealmCatalogSnapshot catalog =
        catalogs.lockExactInitialPublicProductionForShare(
            request.targetNamespace(),
            request.tenantId(),
            request.sourceCatalogRealmId(),
            request.sourceCatalogRequestId(),
            request.sourceCatalogRevision());
    RealmPolicyPublishedEvidence.PublishedSet policy =
        RealmPolicyPublishedEvidenceCodec.decode(
            row.get("complete_policy_set_evidence", byte[].class));
    var intake = catalog.sourceIntakeReceipt();
    var source = intake.source();
    if (!catalog.worldSlug().equals(request.worldSlug())
        || catalog.realmSlug().equals(request.realmSlug())
        || !intake.operationId().equals(request.sourceIntakeOperationId())
        || !policy.target().canonicalTenantId().equals(request.tenantId())
        || !policy.target().canonicalVersionId().equals(request.canonicalVersionId())
        || policy.target().sourceGameRowId() != source.sourceGameRowId()
        || !policy.target().sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !policy.target().sourceProvenanceKind().equals(source.provenanceKind())
        || !policy.publishedReleaseBundleRef().equals(request.publishedReleaseBundleRef())
        || !policy.publishedReleaseBundleDigest().equals(request.publishedReleaseBundleDigest())) {
      throw new SourceUnavailableException(
          "Initial private target/build differs from exact retained public source");
    }
    Record target =
        dsl.fetchOne(
            "SELECT realm_id FROM game_session_canonical_realm_catalog WHERE target_namespace = ? "
                + "AND canonical_tenant_id = ? AND world_slug = ? AND realm_slug = ?",
            request.targetNamespace(),
            request.tenantId(),
            request.worldSlug(),
            request.realmSlug());
    if (target != null)
      throw new SourceUnavailableException(
          "Initial private target already belongs to a canonical realm catalog");
    SourceReference actual =
        new SourceReference(
            catalog.realmId(),
            catalog.creationRequestId(),
            catalog.catalogRevision(),
            catalog.receiptDigest(),
            intake.operationId(),
            intake.receiptDigest(),
            source.operationId(),
            source.evidenceDigest(),
            request.canonicalVersionId(),
            policy.publishedReleaseBundleRef(),
            policy.publishedReleaseBundleDigest(),
            policy.policySetDigest(),
            digest(row.get("complete_policy_set_evidence", byte[].class)));
    return actual;
  }

  private static Preparation preparation(Record row) {
    Request request =
        new Request(
            row.get("request_id", UUID.class),
            row.get("target_namespace", String.class),
            row.get("canonical_tenant_id", UUID.class),
            row.get("world_slug", String.class),
            row.get("realm_slug", String.class),
            row.get("source_catalog_realm_id", UUID.class),
            row.get("source_catalog_request_id", UUID.class),
            row.get("source_catalog_revision", Long.class),
            row.get("source_intake_operation_id", UUID.class),
            row.get("canonical_version_id", UUID.class),
            row.get("published_release_bundle_ref", String.class),
            row.get("published_release_bundle_digest", String.class),
            InitializationMode.valueOf(row.get("initialization_mode", String.class)),
            Instant.ofEpochSecond(
                row.get("fork_expiry_second", Long.class),
                row.get("fork_expiry_nano", Integer.class)));
    if (!Arrays.equals(request.canonicalPreimage(), row.get("request_preimage", byte[].class))
        || !request.requestDigest().equals(row.get("request_digest", String.class))
        || !"PREPARING".equals(row.get("preparation_state", String.class))
        || row.get("playtest_state_generation", Long.class) != 1L) {
      throw new SourceUnavailableException(
          "Stored initial playtest request or preparation state is inconsistent");
    }
    SourceReference source =
        new SourceReference(
            request.sourceCatalogRealmId(),
            request.sourceCatalogRequestId(),
            request.sourceCatalogRevision(),
            row.get("source_catalog_receipt_digest", String.class),
            request.sourceIntakeOperationId(),
            row.get("source_intake_receipt_digest", String.class),
            row.get("source_operation_id", UUID.class),
            row.get("source_evidence_digest", String.class),
            request.canonicalVersionId(),
            request.publishedReleaseBundleRef(),
            request.publishedReleaseBundleDigest(),
            row.get("source_policy_set_digest", String.class),
            row.get("source_policy_carrier_digest", String.class));
    return new Preparation(
        request,
        row.get("playtest_lifecycle_id", UUID.class),
        row.get("playable_state_namespace_id", UUID.class),
        1L,
        "PREPARING",
        source,
        row.get("request_digest", String.class),
        row.get("created_at", OffsetDateTime.class).toInstant());
  }

  private Instant databaseNow() {
    return Objects.requireNonNull(dsl.fetchOne("SELECT clock_timestamp() AS now"))
        .get("now", OffsetDateTime.class)
        .toInstant();
  }

  private static void requireOwnerTransaction(boolean writable) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || (writable && TransactionSynchronizationManager.isCurrentTransactionReadOnly())) {
      throw new IllegalStateException(
          "Initial playtest primitive requires an appropriate owner transaction");
    }
  }

  private static void requireNonNil(UUID id, String name) {
    if (id == null || NIL_UUID.equals(id))
      throw new IllegalArgumentException(name + " must be non-nil");
  }

  private static void requireNamespace(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
  }

  private static void requireSlug(String slug) {
    if (slug == null || slug.length() > 120 || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
      throw new IllegalArgumentException("Canonical world/realm slug required");
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public enum InitializationMode {
    FRESH,
    SEEDED,
    SNAPSHOT
  }

  public enum ReadState {
    INCOMPLETE,
    EXPIRED
  }

  /** Exact intent only; these caller-selected fields are never an actor or source authority. */
  public record Request(
      UUID requestId,
      String targetNamespace,
      UUID tenantId,
      String worldSlug,
      String realmSlug,
      UUID sourceCatalogRealmId,
      UUID sourceCatalogRequestId,
      long sourceCatalogRevision,
      UUID sourceIntakeOperationId,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      InitializationMode initializationMode,
      Instant forkExpiresAt) {
    public Request {
      requireNonNil(requestId, "requestId");
      requireNamespace(targetNamespace);
      requireNonNil(tenantId, "tenantId");
      requireSlug(worldSlug);
      requireSlug(realmSlug);
      requireNonNil(sourceCatalogRealmId, "sourceCatalogRealmId");
      requireNonNil(sourceCatalogRequestId, "sourceCatalogRequestId");
      if (sourceCatalogRevision != 1L)
        throw new IllegalArgumentException(
            "Only exact initial canonical source revision one is supported");
      requireNonNil(sourceIntakeOperationId, "sourceIntakeOperationId");
      requireNonNil(canonicalVersionId, "canonicalVersionId");
      if (publishedReleaseBundleRef == null
          || publishedReleaseBundleRef.isBlank()
          || publishedReleaseBundleRef.length() > 1024
          || !StandardCharsets.UTF_8.newEncoder().canEncode(publishedReleaseBundleRef)
          || publishedReleaseBundleDigest == null
          || !publishedReleaseBundleDigest.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact bounded published build identity required");
      if (initializationMode != InitializationMode.FRESH)
        throw new IllegalArgumentException("Only initial FRESH preparation is available");
      Objects.requireNonNull(forkExpiresAt, "forkExpiresAt");
      if (forkExpiresAt.getEpochSecond() < -62135596800L
          || forkExpiresAt.getEpochSecond() > 253402300799L)
        throw new IllegalArgumentException("Expiry must be within UTC years 0001 through 9999");
    }

    public byte[] canonicalPreimage() {
      var out = new ByteArrayOutputStream();
      for (String value :
          new String[] {
            REQUEST_SCHEMA,
            requestId.toString(),
            targetNamespace,
            tenantId.toString(),
            worldSlug,
            realmSlug,
            sourceCatalogRealmId.toString(),
            sourceCatalogRequestId.toString(),
            Long.toString(sourceCatalogRevision),
            sourceIntakeOperationId.toString(),
            canonicalVersionId.toString(),
            publishedReleaseBundleRef,
            publishedReleaseBundleDigest,
            initializationMode.name(),
            forkExpiresAt.toString()
          }) {
        DraftAuthorizationFenceBinding.frame(out, value);
      }
      return out.toByteArray();
    }

    public String requestDigest() {
      return digest(canonicalPreimage());
    }
  }

  public record SourceReference(
      UUID catalogRealmId,
      UUID catalogRequestId,
      long catalogRevision,
      String catalogReceiptDigest,
      UUID intakeOperationId,
      String intakeReceiptDigest,
      UUID sourceOperationId,
      String sourceEvidenceDigest,
      UUID canonicalVersionId,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      String policySetDigest,
      String policyCarrierDigest) {}

  public record Preparation(
      Request request,
      UUID playtestLifecycleId,
      UUID playableStateNamespaceId,
      long playtestStateGeneration,
      String preparationState,
      SourceReference source,
      String requestDigest,
      Instant createdAt) {}

  public record Allocation(Preparation preparation, boolean replayed) {}

  public record Snapshot(Preparation preparation, ReadState state, Instant observedAt) {
    public boolean admissionAllowed() {
      return false;
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
      super("IDEMPOTENCY_CONFLICT");
    }
  }

  public static final class SourceUnavailableException extends IllegalStateException {
    public SourceUnavailableException(String message) {
      super(message);
    }
  }
}
