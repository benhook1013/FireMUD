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
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshRuntimeAllocationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalPrivateFreshTargetSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionCurrentPrivatePlaytestRepository.Current;
import net.firedevops.firemud.gamesession.repository.GameSessionInitialPlaytestLifecycleRepository.ReadState;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Allocates one immutable private FRESH runtime UUID from exact current source owners. This
 * unregistered preparation primitive writes provenance only, never game_instances, World state,
 * actors, credentials, manifests, admission pointers or lifecycle transitions.
 */
public final class GameSessionPrivateFreshRuntimeAllocationRepository {
  private static final String TABLE = "game_session_private_fresh_runtime_allocations";
  private static final String REQUEST_SCHEMA = "game-session-private-fresh-runtime-request/v1";
  private static final String RECEIPT_SCHEMA = "game-session-private-fresh-runtime-receipt/v1";
  private static final UUID NIL = new UUID(0, 0);
  private final DSLContext dsl;
  private final GameSessionPrivateFreshTargetRepository targets;
  private final GameSessionCurrentPrivatePlaytestRepository selections;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The transaction-bound DSLContext is an injected database dependency, not owned mutable value state")
  public GameSessionPrivateFreshRuntimeAllocationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
    targets = new GameSessionPrivateFreshTargetRepository(dsl);
    selections = new GameSessionCurrentPrivatePlaytestRepository(dsl);
  }

  /**
   * Caller owns the writable SERIALIZABLE transaction. Retry the entire transaction with the same
   * request after SQLSTATE 40001. Commit and independently readByRequest before publishing source
   * evidence. Immutable replay is historical only and grants no new effects.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Allocation allocate(Request request) {
    Objects.requireNonNull(request);
    targets.requireWritableSerializableOwnerTransaction();
    lock("private-fresh-preparation/" + request.preparationRequestId());
    lock("private-fresh-runtime-request/" + request.requestId());
    Record prior = byRequest(request.requestId());
    if (prior != null) {
      var result = decode(prior);
      if (!request.equals(storedRequest(result)))
        throw new IdempotencyConflictException("Private runtime allocation request changed");
      return new Allocation(result, true);
    }
    var source = currentSource(request.preparationRequestId(), request.playtestLifecycleId());
    var target = source.target();
    var selection = source.current().selection();
    if (!request.realmId().equals(target.realmId())
        || request.expectedCatalogRevision() != target.catalogRevision()
        || !request.expectedCatalogRequestId().equals(target.creationRequestId())
        || !request.expectedCatalogReceiptDigest().equals(target.receiptDigest())
        || !request.expectedSelectionRequestId().equals(selection.request().requestId())
        || request.expectedSelectionHeadRevision() != selection.headRevision())
      throw new SourceUnavailableException("Private runtime allocation expected source changed");
    if (byPreparation(request.preparationRequestId(), request.playtestLifecycleId()) != null)
      throw new IdempotencyConflictException(
          "Private preparation already has a runtime allocation");
    UUID runtime;
    do {
      runtime = UUID.randomUUID();
    } while (NIL.equals(runtime)
        || runtime.equals(target.realmId())
        || runtime.equals(target.playtestLifecycleId())
        || runtime.equals(target.playableStateNamespaceId()));
    var preparation = source.current().selectedPrepSnapshot().preparation();
    var draft =
        new CanonicalPrivateFreshRuntimeAllocationSnapshot(
            request.requestId(),
            runtime,
            target.targetNamespace(),
            target.tenantId(),
            target.worldSlug(),
            target.realmSlug(),
            target.realmId(),
            target.catalogRevision(),
            target.creationRequestId(),
            target.requestDigest(),
            target.receiptDigest(),
            target.preparationRequestId(),
            preparation.requestDigest(),
            target.playtestLifecycleId(),
            target.playableStateNamespaceId(),
            target.playtestStateGeneration(),
            target.canonicalVersionId(),
            target.publishedReleaseBundleRef(),
            target.publishedReleaseBundleDigest(),
            selection.request().requestId(),
            selection.headRevision(),
            selection.requestDigest(),
            "sha256:" + "0".repeat(64),
            "sha256:" + "0".repeat(64));
    byte[] requestBytes = requestPreimage(draft);
    String requestDigest = digest(requestBytes);
    String receipt = receiptDigest(draft, requestDigest);
    Record inserted =
        dsl.fetchOne(
            "INSERT INTO "
                + TABLE
                + " (request_id,game_instance_id,target_namespace,canonical_tenant_id,"
                + "world_slug,realm_slug,realm_id,catalog_revision,catalog_request_id,catalog_request_digest,"
                + "catalog_receipt_digest,preparation_request_id,preparation_request_digest,playtest_lifecycle_id,"
                + "playable_state_namespace_id,playtest_state_generation,canonical_version_id,"
                + "published_release_bundle_ref,published_release_bundle_digest,selection_request_id,"
                + "selection_head_revision,selection_request_digest,request_preimage,request_digest,receipt_digest)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING *",
            draft.allocationRequestId(),
            draft.gameInstanceId(),
            draft.targetNamespace(),
            draft.tenantId(),
            draft.worldSlug(),
            draft.realmSlug(),
            draft.realmId(),
            draft.catalogRevision(),
            draft.catalogRequestId(),
            draft.catalogRequestDigest(),
            draft.catalogReceiptDigest(),
            draft.preparationRequestId(),
            draft.preparationRequestDigest(),
            draft.playtestLifecycleId(),
            draft.playableStateNamespaceId(),
            draft.playtestStateGeneration(),
            draft.canonicalVersionId(),
            draft.publishedReleaseBundleRef(),
            draft.publishedReleaseBundleDigest(),
            draft.selectionRequestId(),
            draft.selectionHeadRevision(),
            draft.selectionRequestDigest(),
            requestBytes,
            requestDigest,
            receipt);
    if (inserted == null) {
      Record raced = byRequest(request.requestId());
      if (raced != null && request.equals(storedRequest(decode(raced))))
        return new Allocation(decode(raced), true);
      throw new IdempotencyConflictException(
          "Private runtime identity or preparation already claimed");
    }
    var result = decode(inserted);
    // Transaction-local stored-row readback, not the candidate or INSERT return alone. The
    // owner must commit and independently readByRequest before publishing source evidence.
    var readback = decode(Objects.requireNonNull(byRequest(request.requestId())));
    if (!result.equals(readback)
        || !readback.matches(target)
        || !readback.selectionRequestDigest().equals(selection.requestDigest()))
      throw new InvalidAllocationException(
          "Private runtime allocation transaction-local readback differs");
    return new Allocation(readback, false);
  }

  /**
   * Immutable historical outcome only; expiry or supersession never turns replay into authority.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED, readOnly = true)
  public Optional<CanonicalPrivateFreshRuntimeAllocationSnapshot> readByRequest(UUID requestId) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Private runtime history requires committed readback");
    uuid(requestId);
    Record row = byRequest(requestId);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /**
   * Existing exact current-qualified allocation, without allocating on read. Source locks protect
   * this local transaction only; the result is not an ongoing grant, lifecycle or admission fence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CanonicalPrivateFreshRuntimeAllocationSnapshot> readForWorldPreparation(
      UUID preparationRequestId, UUID playtestLifecycleId) {
    writableRead();
    uuid(preparationRequestId);
    uuid(playtestLifecycleId);
    var source = currentSource(preparationRequestId, playtestLifecycleId);
    Record row = byPreparation(preparationRequestId, playtestLifecycleId);
    if (row == null) return Optional.empty();
    var result = decode(row);
    var selected = source.current().selection();
    if (!result.matches(source.target())
        || !result
            .preparationRequestDigest()
            .equals(source.current().selectedPrepSnapshot().preparation().requestDigest())
        || !result.selectionRequestId().equals(selected.request().requestId())
        || result.selectionHeadRevision() != selected.headRevision()
        || !result.selectionRequestDigest().equals(selected.requestDigest()))
      throw new SourceUnavailableException(
          "Private runtime allocation is no longer exact current source");
    return Optional.of(result);
  }

  private Source currentSource(UUID preparationRequestId, UUID lifecycleId) {
    var target =
        targets
            .readForWorldPreparation(preparationRequestId, lifecycleId)
            .orElseThrow(
                () -> new SourceUnavailableException("Exact current private catalog is absent"));
    Current current =
        selections
            .readCurrent(
                target.targetNamespace(), target.tenantId(), target.worldSlug(), target.realmSlug())
            .orElseThrow(
                () -> new SourceUnavailableException("Current private selection is absent"));
    var preparation = current.selectedPrepSnapshot().preparation();
    if (current.selectedPrepSnapshot().state() != ReadState.INCOMPLETE
        || !current.selection().preparationRequestId().equals(preparationRequestId)
        || !current.selection().playtestLifecycleId().equals(lifecycleId)
        || !preparation.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        || preparation.playtestStateGeneration() != target.playtestStateGeneration()
        || !preparation.source().canonicalVersionId().equals(target.canonicalVersionId())
        || !preparation
            .source()
            .publishedReleaseBundleRef()
            .equals(target.publishedReleaseBundleRef())
        || !preparation
            .source()
            .publishedReleaseBundleDigest()
            .equals(target.publishedReleaseBundleDigest()))
      throw new SourceUnavailableException(
          "Exact private catalog, selection and preparation disagree");
    return new Source(target, current);
  }

  private Record byRequest(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id=?", requestId);
  }

  private Record byPreparation(UUID preparation, UUID lifecycle) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE preparation_request_id=? AND playtest_lifecycle_id=?",
        preparation,
        lifecycle);
  }

  private void lock(String identity) {
    dsl.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", identity);
  }

  private static CanonicalPrivateFreshRuntimeAllocationSnapshot decode(Record row) {
    try {
      var result =
          new CanonicalPrivateFreshRuntimeAllocationSnapshot(
              row.get("request_id", UUID.class), row.get("game_instance_id", UUID.class),
              row.get("target_namespace", String.class), row.get("canonical_tenant_id", UUID.class),
              row.get("world_slug", String.class), row.get("realm_slug", String.class),
              row.get("realm_id", UUID.class), row.get("catalog_revision", Long.class),
              row.get("catalog_request_id", UUID.class),
                  row.get("catalog_request_digest", String.class),
              row.get("catalog_receipt_digest", String.class),
                  row.get("preparation_request_id", UUID.class),
              row.get("preparation_request_digest", String.class),
                  row.get("playtest_lifecycle_id", UUID.class),
              row.get("playable_state_namespace_id", UUID.class),
                  row.get("playtest_state_generation", Long.class),
              row.get("canonical_version_id", UUID.class),
                  row.get("published_release_bundle_ref", String.class),
              row.get("published_release_bundle_digest", String.class),
                  row.get("selection_request_id", UUID.class),
              row.get("selection_head_revision", Long.class),
                  row.get("selection_request_digest", String.class),
              row.get("request_digest", String.class), row.get("receipt_digest", String.class));
      byte[] bytes = requestPreimage(result);
      if (!Arrays.equals(bytes, row.get("request_preimage", byte[].class))
          || !digest(bytes).equals(result.requestDigest())
          || !receiptDigest(result, result.requestDigest()).equals(result.receiptDigest()))
        throw new InvalidAllocationException(
            "Private runtime allocation provenance is inconsistent");
      return result;
    } catch (InvalidAllocationException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw new InvalidAllocationException("Invalid retained private runtime allocation", invalid);
    }
  }

  private static Request storedRequest(CanonicalPrivateFreshRuntimeAllocationSnapshot s) {
    return new Request(
        s.allocationRequestId(),
        s.preparationRequestId(),
        s.playtestLifecycleId(),
        s.realmId(),
        s.catalogRevision(),
        s.catalogRequestId(),
        s.catalogReceiptDigest(),
        s.selectionRequestId(),
        s.selectionHeadRevision());
  }

  private static byte[] requestPreimage(CanonicalPrivateFreshRuntimeAllocationSnapshot s) {
    return framed(
        REQUEST_SCHEMA,
        s.allocationRequestId().toString(),
        s.preparationRequestId().toString(),
        s.playtestLifecycleId().toString(),
        s.realmId().toString(),
        Long.toString(s.catalogRevision()),
        s.catalogRequestId().toString(),
        s.catalogReceiptDigest(),
        s.selectionRequestId().toString(),
        Long.toString(s.selectionHeadRevision()),
        s.targetNamespace(),
        s.tenantId().toString(),
        s.worldSlug(),
        s.realmSlug(),
        s.catalogRequestDigest(),
        s.preparationRequestDigest(),
        s.playableStateNamespaceId().toString(),
        Long.toString(s.playtestStateGeneration()),
        s.canonicalVersionId().toString(),
        s.publishedReleaseBundleRef(),
        s.publishedReleaseBundleDigest(),
        s.selectionRequestDigest());
  }

  private static String receiptDigest(
      CanonicalPrivateFreshRuntimeAllocationSnapshot s, String digest) {
    return digest(
        framed(
            RECEIPT_SCHEMA,
            s.allocationRequestId().toString(),
            digest,
            s.gameInstanceId().toString()));
  }

  private static byte[] framed(String... fields) {
    var out = new ByteArrayOutputStream();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
      out.writeBytes(bytes);
    }
    return out.toByteArray();
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void writableRead() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException(
          "Private runtime current source requires a writable owner read");
  }

  private static void uuid(UUID id) {
    if (id == null || NIL.equals(id))
      throw new IllegalArgumentException("Non-nil identity required");
  }

  public record Request(
      UUID requestId,
      UUID preparationRequestId,
      UUID playtestLifecycleId,
      UUID realmId,
      long expectedCatalogRevision,
      UUID expectedCatalogRequestId,
      String expectedCatalogReceiptDigest,
      UUID expectedSelectionRequestId,
      long expectedSelectionHeadRevision) {
    public Request {
      uuid(requestId);
      uuid(preparationRequestId);
      uuid(playtestLifecycleId);
      uuid(realmId);
      uuid(expectedCatalogRequestId);
      uuid(expectedSelectionRequestId);
      if (expectedCatalogRevision < 1
          || expectedSelectionHeadRevision < 1
          || expectedCatalogReceiptDigest == null
          || !expectedCatalogReceiptDigest.matches("sha256:[0-9a-f]{64}"))
        throw new IllegalArgumentException(
            "Exact positive private catalog and selection binding required");
    }
  }

  private record Source(CanonicalPrivateFreshTargetSnapshot target, Current current) {}

  public record Allocation(
      CanonicalPrivateFreshRuntimeAllocationSnapshot snapshot, boolean replayed) {}

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }

  public static final class SourceUnavailableException extends IllegalStateException {
    public SourceUnavailableException(String message) {
      super(message);
    }
  }

  public static final class InvalidAllocationException extends IllegalStateException {
    public InvalidAllocationException(String message) {
      super(message);
    }

    public InvalidAllocationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
