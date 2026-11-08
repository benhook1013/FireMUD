package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Preparation;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository.Allocation;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository.CatalogConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository.IdempotencyConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository.InvalidPrivateTargetException;
import net.firedevops.firemud.gamesession.repository.GameSessionPrivateFreshTargetRepository.PreparationUnavailableException;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Retained private catalog revisions behind one stable realm identity. These manually composed
 * source facts remain PREPARING; they allocate neither a runtime nor admission authority.
 */
public final class GameSessionPrivateFreshCatalogRevisionRepository {
  private static final String HISTORY = "game_session_private_catalog_revision_history";
  private static final String HEAD = "game_session_current_private_catalog_revisions";
  private static final String CATALOG = "game_session_canonical_realm_catalog";
  private static final UUID NIL = new UUID(0, 0);

  private final DSLContext dsl;
  private final GameSessionPrivateFreshTargetRepository originals;
  private final GameSessionInitialPlaytestLifecycleRepository preparations;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The transaction-bound DSLContext is an injected database dependency, not owned mutable value state")
  public GameSessionPrivateFreshCatalogRevisionRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    originals = new GameSessionPrivateFreshTargetRepository(dsl);
    preparations = new GameSessionInitialPlaytestLifecycleRepository(dsl);
  }

  /**
   * Appends exactly one lifecycle-bound revision and CAS-advances the catalog head. The caller owns
   * the writable SERIALIZABLE transaction and must retry its entire transaction after 40001 using
   * the same immutable request. Replay precedes current-head and expiry qualification.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Allocation advance(AdvanceRequest request) {
    Objects.requireNonNull(request);
    originals.requireWritableSerializableOwnerTransaction();
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "private-fresh-preparation/" + request.preparationRequestId());
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "private-fresh-request/" + request.requestId());
    Record prior = revisionByRequest(request.requestId());
    if (prior != null) {
      if (!request.equals(storedRequest(prior)))
        throw new IdempotencyConflictException("Private catalog revision request changed");
      return new Allocation(decode(prior, ownerPreparation(request)), true);
    }
    if (dsl.fetchExists(
        dsl.selectOne()
            .from(CATALOG)
            .where(
                "creation_request_id=? AND private_preparation_request_id IS NOT NULL",
                request.requestId())))
      throw new IdempotencyConflictException("Request already identifies an original allocation");

    Record head =
        dsl.fetchOne("SELECT * FROM " + HEAD + " WHERE realm_id=? FOR UPDATE", request.realmId());
    if (head == null
        || required(head, "catalog_revision", Long.class) != request.expectedCatalogRevision()
        || !request
            .expectedCurrentLifecycleId()
            .equals(required(head, "playtest_lifecycle_id", UUID.class)))
      throw new CatalogConflictException("Private catalog expected revision or lifecycle changed");
    var observed =
        preparations
            .readExactForPrivateCatalogTarget(
                request.preparationRequestId(), request.playtestLifecycleId())
            .orElseThrow(
                () -> new PreparationUnavailableException("Exact later preparation is absent"));
    if (observed.state() != ReadState.INCOMPLETE)
      throw new PreparationUnavailableException("Later private preparation has expired");
    Preparation preparation = observed.preparation();
    qualifyOriginalIdentity(request.realmId(), preparation, true);
    originals.readExactSourceCatalog(preparation);
    originals.readCompleteHumanPolicyEvidence(preparation);
    if (request.preparationRequestId().equals(required(head, "preparation_request_id", UUID.class))
        || request
            .playtestLifecycleId()
            .equals(required(head, "playtest_lifecycle_id", UUID.class)))
      throw new CatalogConflictException("A new catalog revision requires a later lifecycle");
    if (dsl.fetchExists(
            dsl.selectOne()
                .from(HISTORY)
                .where("preparation_request_id=?", request.preparationRequestId()))
        || dsl.fetchExists(
            dsl.selectOne()
                .from(CATALOG)
                .where("private_preparation_request_id=?", request.preparationRequestId())))
      throw new IdempotencyConflictException("Preparation already has a catalog allocation");

    long next = Math.addExact(request.expectedCatalogRevision(), 1);
    byte[] preimage = request.canonicalPreimage(preparation.requestDigest());
    String requestDigest = digest(preimage);
    String receipt = receiptDigest(request, next, requestDigest, preparation);
    Record inserted =
        dsl.fetchSingle(
            "INSERT INTO "
                + HISTORY
                + "(realm_id,catalog_revision,request_id,request_preimage,request_digest,receipt_digest,"
                + "preparation_request_id,playtest_lifecycle_id,playable_state_namespace_id,"
                + "expected_catalog_revision,expected_current_lifecycle_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING *",
            request.realmId(),
            next,
            request.requestId(),
            preimage,
            requestDigest,
            receipt,
            request.preparationRequestId(),
            request.playtestLifecycleId(),
            preparation.playableStateNamespaceId(),
            request.expectedCatalogRevision(),
            request.expectedCurrentLifecycleId());
    int changed =
        dsl.execute(
            "UPDATE "
                + HEAD
                + " SET catalog_revision=?,revision_request_id=?,preparation_request_id=?,playtest_lifecycle_id=?"
                + " WHERE realm_id=? AND catalog_revision=? AND playtest_lifecycle_id=?",
            next,
            request.requestId(),
            request.preparationRequestId(),
            request.playtestLifecycleId(),
            request.realmId(),
            request.expectedCatalogRevision(),
            request.expectedCurrentLifecycleId());
    if (changed != 1)
      throw new CatalogConflictException("Private catalog revision lost its exact head CAS");
    return new Allocation(decode(inserted, preparation), false);
  }

  /** Immutable original outcome, independent of later heads and preparation expiry. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readByRequest(UUID requestId) {
    committedRead();
    uuid(requestId);
    Record row = revisionByRequest(requestId);
    if (row != null) return Optional.of(decode(row, retainedPreparation(row)));
    Record original =
        dsl.fetchOne(
            "SELECT private_preparation_request_id,private_playtest_lifecycle_id FROM "
                + CATALOG
                + " WHERE creation_request_id=? AND private_preparation_request_id IS NOT NULL",
            requestId);
    return original == null
        ? Optional.empty()
        : originals.readByPreparation(
            required(original, "private_preparation_request_id", UUID.class),
            required(original, "private_playtest_lifecycle_id", UUID.class));
  }

  /** Exact historical preparation; never substitutes whichever revision is now current. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readByPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    committedRead();
    uuid(preparationRequestId);
    uuid(playtestLifecycleId);
    Record row = revisionByPreparation(preparationRequestId, playtestLifecycleId);
    return row == null
        ? originals.readByPreparation(preparationRequestId, playtestLifecycleId)
        : Optional.of(decode(row, retainedPreparation(row)));
  }

  /** One as-of committed catalog head, separate from workflow selection and admission pointers. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readCurrent(UUID realmId) {
    committedRead();
    uuid(realmId);
    Record current = head(realmId, false);
    if (current == null) return Optional.empty();
    var result =
        readByPreparation(
                required(current, "preparation_request_id", UUID.class),
                required(current, "playtest_lifecycle_id", UUID.class))
            .orElseThrow(
                () ->
                    new InvalidPrivateTargetException(
                        "Current catalog original evidence is absent"));
    requireHead(current, result);
    return Optional.of(result);
  }

  /**
   * Current-qualified World source under a short owner read; no protection through World commit.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalPrivateFreshTargetSnapshot> readForWorldPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Private World source requires a writable owner transaction");
    uuid(preparationRequestId);
    uuid(playtestLifecycleId);
    Record row = revisionByPreparation(preparationRequestId, playtestLifecycleId);
    CanonicalPrivateFreshTargetSnapshot result;
    if (row == null) {
      var original =
          originals.readInitialForWorldPreparation(preparationRequestId, playtestLifecycleId);
      if (original.isEmpty()) return Optional.empty();
      result = original.orElseThrow();
    } else {
      var observed =
          preparations
              .readExactForPrivateCatalogTarget(preparationRequestId, playtestLifecycleId)
              .orElseThrow(
                  () -> new PreparationUnavailableException("Exact private preparation is absent"));
      if (observed.state() != ReadState.INCOMPLETE)
        throw new PreparationUnavailableException("Private World preparation has expired");
      var preparation = observed.preparation();
      originals.readExactSourceCatalog(preparation);
      originals.readCompleteHumanPolicyEvidence(preparation);
      result = decode(row, preparation);
    }
    Record current = head(result.realmId(), true);
    if (current == null)
      throw new PreparationUnavailableException("Private catalog current revision is absent");
    requireHead(current, result);
    return Optional.of(result);
  }

  private Preparation ownerPreparation(AdvanceRequest request) {
    var preparation =
        preparations
            .readExactForPrivateCatalogTarget(
                request.preparationRequestId(), request.playtestLifecycleId())
            .orElseThrow(
                () ->
                    new PreparationUnavailableException("Original revision preparation is absent"))
            .preparation();
    originals.readExactSourceCatalog(preparation);
    originals.readCompleteHumanPolicyEvidence(preparation);
    return preparation;
  }

  private Preparation retainedPreparation(Record revision) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_session_initial_playtest_preparations"
                + " WHERE request_id=? AND playtest_lifecycle_id=?",
            required(revision, "preparation_request_id", UUID.class),
            required(revision, "playtest_lifecycle_id", UUID.class));
    if (row == null) throw new InvalidPrivateTargetException("Retained preparation is absent");
    // The immutable preparation decoder validates its original preimage and published policy.
    // This historical read supplies no new allocation or currentness authority.
    return GameSessionInitialPlaytestLifecycleRepository.preparation(row);
  }

  private CanonicalPrivateFreshTargetSnapshot decode(Record row, Preparation preparation) {
    AdvanceRequest request = storedRequest(row);
    long revision = required(row, "catalog_revision", Long.class);
    String requestDigest = required(row, "request_digest", String.class);
    String receipt = required(row, "receipt_digest", String.class);
    if (revision != Math.addExact(request.expectedCatalogRevision(), 1)
        || !request.preparationRequestId().equals(preparation.request().requestId())
        || !request.playtestLifecycleId().equals(preparation.playtestLifecycleId())
        || !preparation
            .playableStateNamespaceId()
            .equals(required(row, "playable_state_namespace_id", UUID.class))
        || !Arrays.equals(
            request.canonicalPreimage(preparation.requestDigest()),
            required(row, "request_preimage", byte[].class))
        || !request.requestDigest(preparation.requestDigest()).equals(requestDigest)
        || !receiptDigest(request, revision, requestDigest, preparation).equals(receipt))
      throw new InvalidPrivateTargetException("Private catalog revision evidence is inconsistent");
    qualifyOriginalIdentity(request.realmId(), preparation, false);
    var policy = preparation.source().targetPolicy();
    return new CanonicalPrivateFreshTargetSnapshot(
        preparation.request().targetNamespace(),
        preparation.request().tenantId(),
        preparation.request().worldSlug(),
        preparation.request().realmSlug(),
        request.realmId(),
        policy.source().policy().realmDisplayName(),
        false,
        false,
        "ISOLATED",
        preparation.playableStateNamespaceId(),
        policy.source().policy().entryPolicy().name(),
        revision,
        request.requestId(),
        requestDigest,
        receipt,
        request.preparationRequestId(),
        preparation.playtestLifecycleId(),
        preparation.playtestStateGeneration(),
        "PREPARING",
        preparation.source().catalogRealmId(),
        policy.policyId(),
        policy.policyDigest(),
        preparation.source().canonicalVersionId(),
        preparation.source().publishedReleaseBundleRef(),
        preparation.source().publishedReleaseBundleDigest());
  }

  private void qualifyOriginalIdentity(UUID realmId, Preparation preparation, boolean lock) {
    Record original =
        dsl.fetchOne(
            "SELECT target_namespace,canonical_tenant_id,world_slug,realm_slug,visible,public_production,"
                + "state_scope,private_preparation_request_id FROM "
                + CATALOG
                + " WHERE realm_id=?"
                + (lock ? " FOR SHARE" : ""),
            realmId);
    if (original == null
        || original.get("private_preparation_request_id", UUID.class) == null
        || !Boolean.FALSE.equals(original.get("visible", Boolean.class))
        || !Boolean.FALSE.equals(original.get("public_production", Boolean.class))
        || !"ISOLATED".equals(original.get("state_scope", String.class))
        || !preparation
            .request()
            .targetNamespace()
            .equals(original.get("target_namespace", String.class))
        || !preparation.request().tenantId().equals(original.get("canonical_tenant_id", UUID.class))
        || !preparation.request().worldSlug().equals(original.get("world_slug", String.class))
        || !preparation.request().realmSlug().equals(original.get("realm_slug", String.class)))
      throw new CatalogConflictException(
          "Later preparation differs from stable private realm identity");
  }

  private static void requireHead(Record head, CanonicalPrivateFreshTargetSnapshot target) {
    if (!target.realmId().equals(required(head, "realm_id", UUID.class))
        || target.catalogRevision() != required(head, "catalog_revision", Long.class)
        || !target.creationRequestId().equals(required(head, "revision_request_id", UUID.class))
        || !target
            .preparationRequestId()
            .equals(required(head, "preparation_request_id", UUID.class))
        || !target
            .playtestLifecycleId()
            .equals(required(head, "playtest_lifecycle_id", UUID.class)))
      throw new PreparationUnavailableException(
          "Exact private target is not the current catalog revision");
  }

  private Record head(UUID realmId, boolean lock) {
    return dsl.fetchOne(
        "SELECT * FROM " + HEAD + " WHERE realm_id=?" + (lock ? " FOR SHARE" : ""), realmId);
  }

  private Record revisionByRequest(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + HISTORY + " WHERE request_id=?", requestId);
  }

  private Record revisionByPreparation(UUID preparationId, UUID lifecycleId) {
    return dsl.fetchOne(
        "SELECT * FROM " + HISTORY + " WHERE preparation_request_id=? AND playtest_lifecycle_id=?",
        preparationId,
        lifecycleId);
  }

  private static AdvanceRequest storedRequest(Record row) {
    return new AdvanceRequest(
        required(row, "request_id", UUID.class),
        required(row, "realm_id", UUID.class),
        required(row, "preparation_request_id", UUID.class),
        required(row, "playtest_lifecycle_id", UUID.class),
        required(row, "expected_catalog_revision", Long.class),
        required(row, "expected_current_lifecycle_id", UUID.class));
  }

  private static String receiptDigest(
      AdvanceRequest request, long revision, String requestDigest, Preparation p) {
    var s = p.source();
    var policy = s.targetPolicy();
    return digest(
        frame(
            "game-session-private-catalog-revision-receipt/v1",
            request.requestId().toString(),
            requestDigest,
            request.realmId().toString(),
            Long.toString(revision),
            p.request().targetNamespace(),
            p.request().tenantId().toString(),
            p.request().worldSlug(),
            p.request().realmSlug(),
            p.request().requestId().toString(),
            p.requestDigest(),
            p.playtestLifecycleId().toString(),
            p.playableStateNamespaceId().toString(),
            Long.toString(p.playtestStateGeneration()),
            s.catalogRealmId().toString(),
            s.catalogRequestId().toString(),
            Long.toString(s.catalogRevision()),
            s.catalogReceiptDigest(),
            policy.policyId().toString(),
            policy.policyDigest(),
            policy.source().commitId().toString(),
            policy.source().revisionId().toString(),
            policy.source().logicalRevisionId(),
            s.canonicalVersionId().toString(),
            s.publishedReleaseBundleRef(),
            s.publishedReleaseBundleDigest(),
            s.policySetDigest(),
            s.policyCarrierDigest(),
            policy.source().policy().realmDisplayName(),
            policy.source().policy().entryPolicy().name()));
  }

  private static byte[] frame(String... fields) {
    var out = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] value = Objects.requireNonNull(field).getBytes(StandardCharsets.UTF_8);
      out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
      out.writeBytes(value);
    }
    return out.toByteArray();
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null)
      throw new InvalidPrivateTargetException("Missing private revision field " + field);
    return value;
  }

  private static void committedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Private catalog readback requires committed owner evidence");
  }

  private static void uuid(UUID value) {
    if (Objects.requireNonNull(value).equals(NIL))
      throw new IllegalArgumentException("Non-nil UUID required");
  }

  public record AdvanceRequest(
      UUID requestId,
      UUID realmId,
      UUID preparationRequestId,
      UUID playtestLifecycleId,
      long expectedCatalogRevision,
      UUID expectedCurrentLifecycleId) {
    public AdvanceRequest {
      uuid(requestId);
      uuid(realmId);
      uuid(preparationRequestId);
      uuid(playtestLifecycleId);
      uuid(expectedCurrentLifecycleId);
      if (expectedCatalogRevision < 1 || expectedCatalogRevision == Long.MAX_VALUE)
        throw new IllegalArgumentException("A positive advancing catalog revision is required");
    }

    public byte[] canonicalPreimage(String preparationDigest) {
      if (!Objects.requireNonNull(preparationDigest).matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException("Exact preparation digest required");
      return frame(
          "game-session-private-catalog-revision-request/v1",
          requestId.toString(),
          realmId.toString(),
          preparationRequestId.toString(),
          playtestLifecycleId.toString(),
          Long.toString(expectedCatalogRevision),
          expectedCurrentLifecycleId.toString(),
          preparationDigest);
    }

    public String requestDigest(String preparationDigest) {
      return digest(canonicalPreimage(preparationDigest));
    }
  }
}
