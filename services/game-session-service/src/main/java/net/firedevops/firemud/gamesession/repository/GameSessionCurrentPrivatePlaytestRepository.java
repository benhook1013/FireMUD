package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.Snapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered internal current-preparation selection for protected Game Session workflows.
 *
 * <p>The composing workflow must authenticate and authorize its actual caller. Requests contain
 * selectors and explicit CAS intent, never actor or private-policy authority. Actual immutable
 * preparation and closed published-policy owners qualify every new selection. This is neither a
 * player catalog nor an admission pointer and all selected preparation remains non-admissible.
 * Readback is coherent as-of source evidence; it protects no subsequent cross-owner grant commit.
 */
public class GameSessionCurrentPrivatePlaytestRepository {
  private static final String HISTORY = "game_session_private_playtest_selections";
  private static final String HEAD = "game_session_current_private_playtests";
  private static final String REQUEST_SCHEMA = "game-session-current-private-playtest-selection/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private final DSLContext dsl;
  private final GameSessionInitialPlaytestLifecycleRepository preparations;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Validate the internal collaborator before use; no reference escapes or resource is acquired.")
  public GameSessionCurrentPrivatePlaytestRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.preparations = new GameSessionInitialPlaytestLifecycleRepository(dsl);
  }

  /** Records an explicit exact lifecycle selection with a revision-and-lifecycle CAS. */
  @Transactional(propagation = Propagation.MANDATORY)
  public SelectionResult select(Request request) {
    requireWritableOwnerTransaction();
    Objects.requireNonNull(request, "request");
    byte[] preimage = request.canonicalPreimage();
    lock("private-playtest-selection-request/" + request.requestId());
    Record prior =
        dsl.fetchOne("SELECT * FROM " + HISTORY + " WHERE request_id = ?", request.requestId());
    if (prior != null) {
      if (!request.requestDigest().equals(prior.get("request_digest", String.class))
          || !Arrays.equals(preimage, prior.get("request_preimage", byte[].class)))
        throw new IdempotencyConflictException();
      // Completed history replays independently of subsequent head replacements and expiry.
      return new SelectionResult(receipt(prior), true);
    }
    Scope scope = request.scope();
    lock("private-playtest-selection-scope/" + scopeDigest(scope));
    Record head = head(scope, true);
    if ((head == null && request.expectedHeadRevision() != null)
        || (head != null
            && (!Objects.equals(
                    request.expectedHeadRevision(), head.get("head_revision", Long.class))
                || !Objects.equals(
                    request.expectedCurrentLifecycleId(),
                    head.get("playtest_lifecycle_id", UUID.class))))) {
      throw new HeadRevisionConflictException();
    }
    Snapshot selected =
        preparations
            .readExact(
                scope.targetNamespace(),
                scope.tenantId(),
                scope.worldSlug(),
                scope.realmSlug(),
                request.playtestLifecycleId())
            .orElseThrow(
                () -> new SourceUnavailableException("Exact private preparation is unavailable"));
    requireQualifiedPreparation(request, selected);
    if (selected.state() == GameSessionInitialPlaytestLifecycleRepository.ReadState.EXPIRED) {
      throw new SourceUnavailableException("Selected private preparation has expired");
    }
    long nextRevision =
        head == null ? 1L : Math.addExact(head.get("head_revision", Long.class), 1L);
    dsl.execute(
        "INSERT INTO "
            + HISTORY
            + " (request_id, request_preimage, request_digest, target_namespace, canonical_tenant_id, "
            + "world_slug, realm_slug, preparation_request_id, playtest_lifecycle_id, expected_head_revision, expected_current_lifecycle_id, head_revision) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.requestId(),
        preimage,
        request.requestDigest(),
        scope.targetNamespace(),
        scope.tenantId(),
        scope.worldSlug(),
        scope.realmSlug(),
        request.preparationRequestId(),
        request.playtestLifecycleId(),
        request.expectedHeadRevision(),
        request.expectedCurrentLifecycleId(),
        nextRevision);
    if (head == null) {
      dsl.execute(
          "INSERT INTO "
              + HEAD
              + " (target_namespace, canonical_tenant_id, world_slug, realm_slug, head_revision, selection_request_id, playtest_lifecycle_id) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?)",
          scope.targetNamespace(),
          scope.tenantId(),
          scope.worldSlug(),
          scope.realmSlug(),
          nextRevision,
          request.requestId(),
          request.playtestLifecycleId());
    } else {
      int changed =
          dsl.execute(
              "UPDATE "
                  + HEAD
                  + " SET head_revision = ?, selection_request_id = ?, playtest_lifecycle_id = ? "
                  + "WHERE target_namespace = ? AND canonical_tenant_id = ? AND world_slug = ? AND realm_slug = ? AND head_revision = ? AND playtest_lifecycle_id = ?",
              nextRevision,
              request.requestId(),
              request.playtestLifecycleId(),
              scope.targetNamespace(),
              scope.tenantId(),
              scope.worldSlug(),
              scope.realmSlug(),
              request.expectedHeadRevision(),
              request.expectedCurrentLifecycleId());
      if (changed != 1)
        throw new IllegalStateException("Locked private preparation head changed concurrently");
    }
    SelectionReceipt receipt =
        receipt(
            Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT * FROM " + HISTORY + " WHERE request_id = ?", request.requestId())));
    if (!receipt.request().equals(request) || receipt.headRevision() != nextRevision)
      throw new IllegalStateException("Private preparation selection readback differs from intent");
    return new SelectionResult(receipt, false);
  }

  /**
   * Locks one exact head and joins its qualified immutable preparation. A writable owner
   * transaction is required for PostgreSQL's row share lock; this method performs no writes.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Current> readCurrent(
      String targetNamespace, UUID tenantId, String worldSlug, String realmSlug) {
    requireWritableOwnerTransaction();
    Scope scope = new Scope(targetNamespace, tenantId, worldSlug, realmSlug);
    Record head = head(scope, false);
    if (head == null) return Optional.empty();
    Record history =
        dsl.fetchOne(
            "SELECT * FROM " + HISTORY + " WHERE request_id = ?",
            head.get("selection_request_id", UUID.class));
    if (history == null)
      throw new SourceUnavailableException(
          "Current private preparation has no immutable selection receipt");
    SelectionReceipt selected = receipt(history);
    if (!selected.scope().equals(scope)
        || selected.headRevision() != head.get("head_revision", Long.class)
        || !selected.playtestLifecycleId().equals(head.get("playtest_lifecycle_id", UUID.class))) {
      throw new SourceUnavailableException(
          "Current private preparation head differs from its exact receipt");
    }
    Snapshot snapshot =
        preparations
            .readExact(
                targetNamespace, tenantId, worldSlug, realmSlug, selected.playtestLifecycleId())
            .orElseThrow(
                () ->
                    new SourceUnavailableException(
                        "Current private preparation source is unavailable"));
    requireQualifiedPreparation(selected.request(), snapshot);
    return Optional.of(new Current(selected, snapshot));
  }

  private Record head(Scope scope, boolean exclusive) {
    return dsl.fetchOne(
        "SELECT * FROM "
            + HEAD
            + " WHERE target_namespace = ? AND canonical_tenant_id = ? AND world_slug = ? AND realm_slug = ? "
            + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
        scope.targetNamespace(),
        scope.tenantId(),
        scope.worldSlug(),
        scope.realmSlug());
  }

  private void lock(String identity) {
    dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", identity);
  }

  private static void requireQualifiedPreparation(Request request, Snapshot snapshot) {
    var preparation = snapshot.preparation();
    var original = preparation.request();
    var policy = preparation.source().targetPolicy();
    if (!request.preparationRequestId().equals(original.requestId())
        || !request.playtestLifecycleId().equals(preparation.playtestLifecycleId())
        || !request
            .scope()
            .equals(
                new Scope(
                    original.targetNamespace(),
                    original.tenantId(),
                    original.worldSlug(),
                    original.realmSlug()))
        || original.initializationMode()
            != GameSessionInitialPlaytestLifecycleRepository.InitializationMode.FRESH
        || !"PREPARING".equals(preparation.preparationState())
        || preparation.playtestStateGeneration() != 1L
        || policy == null
        || !policy.source().policy().visible()
        || policy.source().policy().publicProduction()
        || policy.source().policy().stateScope() != RealmEntryPolicy.StateScope.ISOLATED
        || !policy.source().policy().worldSlug().equals(request.scope().worldSlug())
        || !policy.source().policy().realmSlug().equals(request.scope().realmSlug())) {
      throw new SourceUnavailableException(
          "Selection differs from exact qualified FRESH private preparation");
    }
  }

  private static SelectionReceipt receipt(Record row) {
    Request request =
        new Request(
            row.get("request_id", UUID.class),
            new Scope(
                row.get("target_namespace", String.class),
                row.get("canonical_tenant_id", UUID.class),
                row.get("world_slug", String.class),
                row.get("realm_slug", String.class)),
            row.get("preparation_request_id", UUID.class),
            row.get("playtest_lifecycle_id", UUID.class),
            row.get("expected_head_revision", Long.class),
            row.get("expected_current_lifecycle_id", UUID.class));
    long revision = row.get("head_revision", Long.class);
    if (!request.requestDigest().equals(row.get("request_digest", String.class))
        || !Arrays.equals(request.canonicalPreimage(), row.get("request_preimage", byte[].class))
        || revision
            != (request.expectedHeadRevision() == null
                ? 1L
                : Math.addExact(request.expectedHeadRevision(), 1L))) {
      throw new SourceUnavailableException(
          "Private preparation selection history differs from immutable original intent");
    }
    return new SelectionReceipt(
        request,
        revision,
        request.requestDigest(),
        row.get("selected_at", OffsetDateTime.class).toInstant());
  }

  private static void requireWritableOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Current private playtest source requires a writable owner transaction for coherent row locks");
    }
  }

  private static void requireIdentity(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value))
      throw new IllegalArgumentException(name + " must be non-nil");
  }

  private static void requireSlug(String value) {
    if (value == null || value.length() > 120 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
      throw new IllegalArgumentException("Canonical world/realm slug required");
  }

  private static String scopeDigest(Scope scope) {
    var out = new ByteArrayOutputStream();
    for (String field :
        new String[] {
          "game-session-private-playtest-scope/v1",
          scope.targetNamespace(),
          scope.tenantId().toString(),
          scope.worldSlug(),
          scope.realmSlug()
        }) {
      DraftAuthorizationFenceBinding.frame(out, field);
    }
    return digest(out.toByteArray());
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public record Scope(String targetNamespace, UUID tenantId, String worldSlug, String realmSlug) {
    public Scope {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace))
        throw new IllegalArgumentException("Canonical workload namespace required");
      requireIdentity(tenantId, "tenantId");
      requireSlug(worldSlug);
      requireSlug(realmSlug);
    }
  }

  public record Request(
      UUID requestId,
      Scope scope,
      UUID preparationRequestId,
      UUID playtestLifecycleId,
      Long expectedHeadRevision,
      UUID expectedCurrentLifecycleId) {
    public Request {
      requireIdentity(requestId, "requestId");
      Objects.requireNonNull(scope, "scope");
      requireIdentity(preparationRequestId, "preparationRequestId");
      requireIdentity(playtestLifecycleId, "playtestLifecycleId");
      if ((expectedHeadRevision == null) != (expectedCurrentLifecycleId == null)
          || (expectedHeadRevision != null && expectedHeadRevision <= 0L)) {
        throw new IllegalArgumentException(
            "Expected head revision and lifecycle must both be absent or both be positive exact values");
      }
      if (expectedCurrentLifecycleId != null)
        requireIdentity(expectedCurrentLifecycleId, "expectedCurrentLifecycleId");
    }

    public byte[] canonicalPreimage() {
      var out = new ByteArrayOutputStream();
      for (String field :
          new String[] {
            REQUEST_SCHEMA,
            requestId.toString(),
            scope.targetNamespace(),
            scope.tenantId().toString(),
            scope.worldSlug(),
            scope.realmSlug(),
            preparationRequestId.toString(),
            playtestLifecycleId.toString(),
            expectedHeadRevision == null ? "ABSENT" : "PRESENT"
          }) {
        DraftAuthorizationFenceBinding.frame(out, field);
      }
      if (expectedHeadRevision != null) {
        DraftAuthorizationFenceBinding.frame(out, expectedHeadRevision.toString());
        DraftAuthorizationFenceBinding.frame(out, expectedCurrentLifecycleId.toString());
      }
      return out.toByteArray();
    }

    public String requestDigest() {
      return digest(canonicalPreimage());
    }
  }

  public record SelectionReceipt(
      Request request, long headRevision, String requestDigest, Instant selectedAt) {
    public Scope scope() {
      return request.scope();
    }

    public UUID preparationRequestId() {
      return request.preparationRequestId();
    }

    public UUID playtestLifecycleId() {
      return request.playtestLifecycleId();
    }

    public byte[] canonicalPreimage() {
      return request.canonicalPreimage();
    }
  }

  public record SelectionResult(SelectionReceipt receipt, boolean replayed) {}

  public record Current(SelectionReceipt selection, Snapshot selectedPrepSnapshot) {
    public boolean admissionAllowed() {
      return false;
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
      super("IDEMPOTENCY_CONFLICT");
    }
  }

  public static final class HeadRevisionConflictException extends IllegalStateException {
    public HeadRevisionConflictException() {
      super("CURRENT_PLAYTEST_REVISION_CONFLICT");
    }
  }

  public static final class SourceUnavailableException extends IllegalStateException {
    public SourceUnavailableException(String message) {
      super(message);
    }
  }
}
