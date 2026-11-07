package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly unwired immutable graph/2 journal; historical reads never reenter an owner writer. */
public class WorldCanonicalFrozenTopologyRepository {
  static final int MAX_DEADLINE_READS = 4;
  private static final Semaphore DEADLINE_READ_SLOTS = new Semaphore(MAX_DEADLINE_READS);
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();
  private final DSLContext dsl;
  private final WorldAuthoredGraphSnapshotRepository owner;
  private final WorldDraftTopologyCommitRepository topology;
  private final WorldDraftDesignDigestService digestService;

  @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW", justification = "No resources or finalizer.")
  public WorldCanonicalFrozenTopologyRepository(
      DSLContext dsl,
      WorldAuthoredGraphSnapshotRepository owner,
      WorldDraftTopologyCommitRepository topology,
      WorldDraftDesignDigestService digestService) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.digestService = Objects.requireNonNull(digestService, "digestService");
  }

  /** Absence is UNKNOWN, not abort or permission to reopen an owner operation. */
  public Optional<WorldCanonicalFrozenTopology> readCommitted(Request request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("Frozen topology readback requires no caller transaction");
    }
    return Optional.ofNullable(find(Objects.requireNonNull(request, "request")));
  }

  /** Complete publication selection; owner and original commit are reconstructed internally. */
  public Optional<WorldCanonicalFrozenTopology> readCommitted(CaptureRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new ConflictException("Frozen topology readback requires no caller transaction");
    }
    return readFrozen(Objects.requireNonNull(request, "request"));
  }

  /**
   * The complete local read, including connection acquisition, shares the caller's absolute budget.
   */
  public Optional<WorldCanonicalFrozenTopology> readCommitted(
      CaptureRequest request, Deadline deadline) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new ConflictException("Frozen topology readback requires no caller transaction");
    }
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(deadline, "deadline");
    Context context = Context.current();
    if (context.getDeadline() != null) deadline = deadline.minimum(context.getDeadline());
    var read = new DeadlineRead(request, deadline, context);
    read.requireLive();
    if (!DEADLINE_READ_SLOTS.tryAcquire()) throw Status.RESOURCE_EXHAUSTED.asRuntimeException();
    Context.CancellationListener listener = ignored -> read.cancel(Status.CANCELLED);
    try {
      context.addListener(listener, Runnable::run);
      try {
        Thread.ofVirtual().name("world-frozen-topology-read").start(read::run);
      } catch (RuntimeException | Error failure) {
        read.finished();
        throw failure;
      }
      return read.await();
    } finally {
      context.removeListener(listener);
    }
  }

  /** Reads this immutable selector only inside the caller-owned read-only RR snapshot. */
  Optional<WorldCanonicalFrozenTopology> readInOwnedSnapshot(CaptureRequest request) {
    requireOwnedReadOnlyRepeatableReadSnapshot();
    return readFrozen(Objects.requireNonNull(request, "request"));
  }

  private Optional<WorldCanonicalFrozenTopology> readFrozen(CaptureRequest request) {
    return readFrozen(request, dsl);
  }

  private Optional<WorldCanonicalFrozenTopology> readFrozen(
      CaptureRequest request, DSLContext reader) {
    Record row =
        reader
            .resultQuery(
                "SELECT binding_json,binding_digest,owner_binding_json FROM world_canonical_frozen_topology WHERE publication_fence=?",
                request.publicationFence())
            .fetchOne();
    if (row == null) return Optional.empty();
    var binding =
        DraftCommitBinding.fromStored(
            required(row, "binding_json", String.class),
            required(row, "binding_digest", String.class));
    var ownerBinding =
        JSON.readValue(required(row, "owner_binding_json", String.class), OwnerBinding.class);
    return Optional.ofNullable(
        find(
            new Request(WorldDraftTopologyCommitPlan.create(binding, ownerBinding), request),
            reader));
  }

  private void requireOwnedReadOnlyRepeatableReadSnapshot() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new ConflictException(
          "Frozen topology snapshot read requires an active read-only REPEATABLE READ owner transaction");
    }
    Integer jdbcIsolation = dsl.connectionResult(Connection::getTransactionIsolation);
    if (!Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ).equals(jdbcIsolation)) {
      throw new ConflictException(
          "Frozen topology snapshot read requires actual JDBC REPEATABLE READ isolation");
    }
  }

  WorldCanonicalFrozenTopology capture(Request request) {
    // This seam validates actual PostgreSQL isolation and serializes through the existing V25 row.
    var provenance = owner.lockAndResolve(request.freeze());
    WorldCanonicalFrozenTopology prior = find(request);
    if (prior != null) return prior;
    if (request.freeze().digestSchemaVersion() != 3
        && request.freeze().digestSchemaVersion() != 4) {
      throw new ConflictException("Canonical graph capture requires schema 3 or selected schema 4");
    }
    if (!"FROZEN".equals(provenance.ownerFreezePhase())) {
      throw new ConflictException(
          "New canonical graph capture requires the exact current FROZEN attempt");
    }
    var stored = topology.readUnderFrozenLock(request.plan());
    Record original =
        dsl.resultQuery(
                "SELECT to_jsonb(v)::text AS identity_json, to_jsonb(i)::text AS intake_json "
                    + "FROM world_authored_version_identity v JOIN world_authored_source_intake i "
                    + "ON i.operation_id=v.intake_operation_id WHERE v.operation_id=?",
                request.plan().ownerBinding().versionIdentityOperationId())
            .fetchOne();
    if (original == null)
      throw new ConflictException("Frozen topology has no exact original source identity");
    String identityJson = required(original, "identity_json", String.class);
    String intakeJson = required(original, "intake_json", String.class);
    var identity = topology.verifyOriginalSource(request.plan(), identityJson, intakeJson);
    var graph =
        topology.verifyImmutableBytes(request.plan(), stored.graphBytes(), stored.resultBytes());
    requirePrivateKeys(graph, identity);
    if (graph.inboundSourceClosure().isPresent() != (request.freeze().digestSchemaVersion() == 4)) {
      throw new ConflictException(
          "Selected inbound closure and schema-4 frozen checkpoint must be bound together");
    }
    var digest =
        request.freeze().digestSchemaVersion() == 4
            ? digestService.getSelectedDraftDesignDigest(
                Long.toString(provenance.localTenantKey()),
                Long.toString(provenance.localVersionKey()),
                graph
                    .inboundSourceClosure()
                    .orElseThrow(
                        () ->
                            new ConflictException(
                                "Selected graph capture requires its exact original inbound closure")))
            : digestService.getDraftDesignDigest(
                Long.toString(provenance.localTenantKey()),
                Long.toString(provenance.localVersionKey()));
    if (!Long.toString(provenance.localTenantKey()).equals(digest.tenantId())
        || !Long.toString(provenance.localVersionKey()).equals(digest.scopeValue())
        || digest.digestSchemaVersion() != request.freeze().digestSchemaVersion()
        || !request.freeze().contentDigest().equals(digest.contentDigest())) {
      throw new ConflictException(
          "Canonical graph capture differs from exact frozen content checkpoint");
    }
    UUID captureId = UUID.randomUUID();
    String freezeJson = JSON.writeValueAsString(request.freeze());
    String ownerJson =
        WorldDraftTopologyCommitRepository.ownerBindingJson(request.plan().ownerBinding());
    byte[] result =
        result(
            captureId,
            request,
            identityJson,
            intakeJson,
            stored.graphBytes(),
            stored.resultBytes());
    dsl.execute(
        "INSERT INTO world_canonical_frozen_topology (capture_id,publication_fence,"
            + "request_id,commit_id,version_identity_operation_id,freeze_request_json,owner_binding_json,"
            + "binding_json,binding_digest,identity_json,intake_json,graph_bytes,graph_sha256,"
            + "storage_result_bytes,result_bytes,capture_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        captureId,
        request.freeze().publicationFence(),
        request.plan().binding().requestId(),
        request.plan().binding().commitId(),
        request.plan().ownerBinding().versionIdentityOperationId(),
        freezeJson,
        ownerJson,
        request.plan().binding().canonicalJson(),
        request.plan().binding().digest(),
        identityJson,
        intakeJson,
        stored.graphBytes(),
        WorldAuthoredGraphSnapshotCapture.sha256(stored.graphBytes()),
        stored.resultBytes(),
        result,
        WorldCanonicalFrozenTopology.STATUS);
    WorldCanonicalFrozenTopology readback = find(request);
    if (readback == null
        || !captureId.equals(readback.captureId())
        || !Arrays.equals(result, readback.resultBytes())) {
      throw new ConflictException(
          "Frozen topology differs from exact immutable insertion readback");
    }
    return readback;
  }

  private WorldCanonicalFrozenTopology find(Request supplied) {
    return find(supplied, dsl);
  }

  private WorldCanonicalFrozenTopology find(Request supplied, DSLContext reader) {
    Record row =
        reader
            .resultQuery(
                "SELECT * FROM world_canonical_frozen_topology WHERE publication_fence=?",
                supplied.freeze().publicationFence())
            .fetchOne();
    if (row == null) return null;
    String bindingJson = required(row, "binding_json", String.class);
    var binding =
        DraftCommitBinding.fromStored(bindingJson, required(row, "binding_digest", String.class));
    String ownerJson = required(row, "owner_binding_json", String.class);
    var ownerBinding = JSON.readValue(ownerJson, OwnerBinding.class);
    String freezeJson = required(row, "freeze_request_json", String.class);
    var freeze = JSON.readValue(freezeJson, CaptureRequest.class);
    Request retained =
        new Request(WorldDraftTopologyCommitPlan.create(binding, ownerBinding), freeze);
    if (!binding.equals(supplied.plan().binding())
        || !ownerBinding.equals(supplied.plan().ownerBinding())
        || !freeze.equals(supplied.freeze())
        || !bindingJson.equals(binding.canonicalJson())
        || !ownerJson.equals(WorldDraftTopologyCommitRepository.ownerBindingJson(ownerBinding))
        || !freezeJson.equals(JSON.writeValueAsString(freeze))) {
      throw new ConflictException(
          "Frozen topology request was reused with changed complete input or checkpoint");
    }
    UUID captureId = required(row, "capture_id", UUID.class);
    if (!freeze.publicationFence().equals(required(row, "publication_fence", UUID.class))
        || !binding.requestId().equals(required(row, "request_id", UUID.class))
        || !binding.commitId().equals(required(row, "commit_id", UUID.class))
        || !ownerBinding
            .versionIdentityOperationId()
            .equals(required(row, "version_identity_operation_id", UUID.class))
        || !WorldCanonicalFrozenTopology.STATUS.equals(
            required(row, "capture_status", String.class))) {
      throw new ConflictException("Frozen topology journal identity or proof state differs");
    }
    String identityJson = required(row, "identity_json", String.class);
    String intakeJson = required(row, "intake_json", String.class);
    JSON.readTree(identityJson);
    JSON.readTree(intakeJson);
    var identity = topology.verifyOriginalSource(retained.plan(), identityJson, intakeJson);
    byte[] graphBytes = required(row, "graph_bytes", byte[].class);
    byte[] storageResult = required(row, "storage_result_bytes", byte[].class);
    if (!WorldAuthoredGraphSnapshotCapture.sha256(graphBytes)
        .equals(required(row, "graph_sha256", String.class))) {
      throw new ConflictException(
          "Frozen canonical graph bytes failed immutable integrity readback");
    }
    var graph = topology.verifyImmutableBytes(retained.plan(), graphBytes, storageResult);
    requirePrivateKeys(graph, identity);
    byte[] resultBytes = required(row, "result_bytes", byte[].class);
    if (!Arrays.equals(
        resultBytes,
        result(captureId, retained, identityJson, intakeJson, graphBytes, storageResult))) {
      throw new ConflictException(
          "Frozen topology result differs from every original retained field");
    }
    return new WorldCanonicalFrozenTopology(
        captureId, retained, identity, graph, graphBytes, storageResult, resultBytes);
  }

  /**
   * A stalled JDBC acquire or cancel retains its slot. There is no queue and no replacement worker
   * until both resource and cancellation work end, so an unresponsive driver cannot grow this work.
   */
  private final class DeadlineRead {
    private final CaptureRequest request;
    private final Deadline deadline;
    private final Context context;
    private final CompletableFuture<Optional<WorldCanonicalFrozenTopology>> result =
        new CompletableFuture<>();
    private final AtomicReference<Status> cancellation = new AtomicReference<>();
    private final Object ownership = new Object();
    private Statement active;
    private boolean resourceFinished;
    private boolean resourceReleaseFailed;
    private boolean cancellationStarted;
    private boolean cancellationFinished;
    private boolean released;

    private DeadlineRead(CaptureRequest request, Deadline deadline, Context context) {
      this.request = request;
      this.deadline = deadline;
      this.context = context;
    }

    private void requireLive() {
      Status stopped = cancellation.get();
      if (stopped != null) throw stopped.asRuntimeException();
      if (context.isCancelled()) throw Status.CANCELLED.asRuntimeException();
      if (deadline.isExpired()) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    }

    private Optional<WorldCanonicalFrozenTopology> await() {
      try {
        var value =
            result.get(
                Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS)), TimeUnit.NANOSECONDS);
        requireLive();
        return value;
      } catch (TimeoutException expired) {
        cancel(Status.DEADLINE_EXCEEDED);
        throw Status.DEADLINE_EXCEEDED.asRuntimeException();
      } catch (InterruptedException interrupted) {
        cancel(Status.CANCELLED);
        Thread.currentThread().interrupt();
        throw Status.CANCELLED.asRuntimeException();
      } catch (ExecutionException failed) {
        requireLive();
        if (failed.getCause() instanceof RuntimeException failure) throw failure;
        if (failed.getCause() instanceof Error failure) throw failure;
        throw new IllegalStateException("Frozen topology read failed", failed.getCause());
      }
    }

    private void run() {
      Connection connection = null;
      Optional<WorldCanonicalFrozenTopology> value = null;
      Throwable failure = null;
      var provider = dsl.configuration().connectionProvider();
      try {
        requireLive();
        connection = provider.acquire();
        // An acquire may ignore interruption. Never execute on a connection arriving after expiry.
        requireLive();
        ExecuteListenerProvider[] existing = dsl.configuration().executeListenerProviders();
        ExecuteListenerProvider[] listeners = Arrays.copyOf(existing, existing.length + 1);
        listeners[existing.length] =
            new DefaultExecuteListenerProvider(
                ExecuteListener.onExecuteStart(this::startStatement));
        var reader = DSL.using(dsl.configuration().derive(connection).derive(listeners));
        value = readFrozen(request, reader);
        requireLive();
      } catch (RuntimeException | Error stopped) {
        failure = stopped;
      } finally {
        // cancel() only touches active while holding this monitor. Detach before pool release;
        // an in-flight driver cancel must finish before the connection can be returned/reused.
        synchronized (ownership) {
          active = null;
        }
        boolean connectionReleased = true;
        try {
          if (connection != null) provider.release(connection);
        } catch (RuntimeException | Error releaseFailure) {
          connectionReleased = false;
          if (failure == null) failure = releaseFailure;
          else failure.addSuppressed(releaseFailure);
        } finally {
          finished(connectionReleased);
        }
      }
      if (failure == null) result.complete(value);
      else result.completeExceptionally(failure);
    }

    private void cancel(Status reason) {
      if (!cancellation.compareAndSet(null, reason)) return;
      result.completeExceptionally(reason.asRuntimeException());
      synchronized (ownership) {
        if (resourceFinished) return;
        cancellationStarted = true;
      }
      try {
        Thread.ofVirtual()
            .name("world-frozen-topology-cancel")
            .start(
                () -> {
                  synchronized (ownership) {
                    try {
                      if (active != null) active.cancel();
                    } catch (SQLException ignored) {
                      // Deadline refusal is delivered; the JDBC query timeout also remains.
                    } finally {
                      cancellationFinished = true;
                      releaseSlot();
                    }
                  }
                });
      } catch (RuntimeException | Error startFailure) {
        synchronized (ownership) {
          // No cancellation worker owns resources. JDBC's timeout remains the backstop, and
          // the resource worker must still end before its slot can be released.
          cancellationFinished = true;
          releaseSlot();
        }
      }
    }

    private void finished() {
      finished(true);
    }

    private void finished(boolean connectionReleased) {
      synchronized (ownership) {
        resourceFinished = true;
        resourceReleaseFailed = !connectionReleased;
        releaseSlot();
      }
    }

    private void releaseSlot() {
      if (!released
          && resourceFinished
          && !resourceReleaseFailed
          && (!cancellationStarted || cancellationFinished)) {
        released = true;
        DEADLINE_READ_SLOTS.release();
      }
    }

    private void startStatement(ExecuteContext execution) {
      requireLive();
      Statement statement =
          Objects.requireNonNull(
              execution.statement(), "Frozen topology query has no JDBC statement");
      synchronized (ownership) {
        active = statement;
      }
      long nanos = deadline.timeRemaining(TimeUnit.NANOSECONDS);
      if (nanos <= 0) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
      // JDBC uses whole seconds. The independent cancellation worker enforces subsecond expiry.
      int seconds =
          (int) Math.min(Integer.MAX_VALUE, 1 + (nanos - 1) / TimeUnit.SECONDS.toNanos(1));
      try {
        statement.setQueryTimeout(seconds);
      } catch (SQLException failure) {
        throw new DataAccessException("Cannot bound frozen topology query", failure);
      }
      requireLive();
    }
  }

  private static void requirePrivateKeys(
      WorldCanonicalAuthoredGraph graph, WorldAuthoredVersionIdentityReceipt identity) {
    if (graph.localTenantKey() != identity.sourceIntakeReceipt().localTenantKey()
        || graph.localVersionKey() != identity.localVersionKey()) {
      throw new ConflictException(
          "Canonical graph private keys differ from original source identity");
    }
  }

  private byte[] result(
      UUID id,
      Request request,
      String identityJson,
      String intakeJson,
      byte[] graph,
      byte[] storageResult) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", "1");
    result.put("status", WorldCanonicalFrozenTopology.STATUS);
    result.put(
        "missingProof",
        java.util.List.of(
            "ACCOUNT_AUTHORIZATION",
            "COMPLETE_PARTICIPANT_COMMIT",
            "PUBLICATION_CHECKPOINT_DIGEST"));
    result.put("captureId", id.toString());
    result.put("freeze", request.freeze());
    result.put("ownerBinding", request.plan().ownerBinding());
    result.put("bindingJson", request.plan().binding().canonicalJson());
    result.put("bindingDigest", request.plan().binding().digest());
    result.put("identityJson", identityJson);
    result.put("intakeJson", intakeJson);
    result.put("graphBytes", graph);
    result.put("storageResultBytes", storageResult);
    return JSON.writeValueAsBytes(result);
  }

  private static <T> T required(Record row, String name, Class<T> type) {
    T value = row.get(name, type);
    if (value == null) throw new ConflictException("Frozen topology is missing " + name);
    return value;
  }
}
