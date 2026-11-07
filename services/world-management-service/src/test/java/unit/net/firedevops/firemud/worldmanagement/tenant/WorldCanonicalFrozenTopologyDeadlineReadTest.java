package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.protobuf.util.JsonFormat;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.OwnedAffectedTuple;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled JDBC acquisition/statements only; these cases do not prove physical PostgreSQL I/O.
 */
@Timeout(15)
class WorldCanonicalFrozenTopologyDeadlineReadTest {
  @Test
  void expiredCancelledAndAmbientCallsNeverAcquire() throws Exception {
    var fixture = new Fixture(0, 1);
    assertThatThrownBy(
            () ->
                fixture.repository.readCommitted(
                    fixture.request, Deadline.after(-1, TimeUnit.SECONDS)))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error ->
                assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    try (var context = Context.current().withCancellation()) {
      context.cancel(null);
      assertStatus(
          fixture.async(context, Deadline.after(5, TimeUnit.SECONDS)), Status.Code.CANCELLED);
    }
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(
              () ->
                  fixture.repository.readCommitted(
                      fixture.request, Deadline.after(5, TimeUnit.SECONDS)))
          .isInstanceOf(WorldDesignPublicationFenceRepository.ConflictException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    assertThat(fixture.acquisitions.get()).isZero();
  }

  @Test
  void inheritedDeadlineBoundsAcquisitionAndLateConnectionNeverExecutes() throws Exception {
    var fixture = new Fixture(0, 1);
    fixture.acquireGate = new CountDownLatch(1);
    try (var context = Context.current().withDeadlineAfter(1, TimeUnit.SECONDS, timer())) {
      var read = fixture.async(context, Deadline.after(30, TimeUnit.SECONDS));
      await(fixture.acquireEntered);
      assertStatus(read, Status.Code.CANCELLED, Status.Code.DEADLINE_EXCEEDED);
      assertThat(fixture.released.getCount()).isEqualTo(1);
    } finally {
      fixture.acquireGate.countDown();
      fixture.awaitReleased();
    }
    assertThat(fixture.executions.get()).isZero();
  }

  @Test
  void cancelledAcquisitionClosesLateConnectionWithoutSql() throws Exception {
    var fixture = new Fixture(0, 1);
    fixture.acquireGate = new CountDownLatch(1);
    try (var context = Context.current().withCancellation()) {
      var read = fixture.async(context, Deadline.after(10, TimeUnit.SECONDS));
      await(fixture.acquireEntered);
      context.cancel(null);
      assertStatus(read, Status.Code.CANCELLED);
    } finally {
      fixture.acquireGate.countDown();
      fixture.awaitReleased();
    }
    assertThat(fixture.executions.get()).isZero();
  }

  @Test
  void firstAndSecondQueriesCancelTheirActualStatementsAtDeadline() throws Exception {
    for (int blocked : List.of(1, 2)) {
      var fixture = new Fixture(blocked, 1);
      var read = fixture.async(Context.current(), Deadline.after(1, TimeUnit.SECONDS));
      try {
        await(fixture.executeEntered);
        assertStatus(read, Status.Code.DEADLINE_EXCEEDED);
        await(fixture.cancelEntered);
      } finally {
        fixture.sqlGate.countDown();
        fixture.awaitReleased();
      }
      assertThat(fixture.executions.get()).isEqualTo(blocked);
      assertThat(fixture.cancellations.get()).isEqualTo(1);
    }
  }

  @Test
  void cancellationCannotTouchReturnedConnectionOrOutliveItsCapacitySlot() throws Exception {
    var fixture = new Fixture(1, 1);
    fixture.cancelGate = new CountDownLatch(1);
    try (var context = Context.current().withCancellation()) {
      var read = fixture.async(context, Deadline.after(10, TimeUnit.SECONDS));
      try {
        await(fixture.executeEntered);
        context.cancel(null);
        assertStatus(read, Status.Code.CANCELLED);
        await(fixture.cancelEntered);
        fixture.sqlGate.countDown();
        // The JDBC execute can finish while cancel is still running. Pool release must wait.
        await(fixture.executeFinished);
        assertThat(fixture.released.getCount()).isEqualTo(1);
      } finally {
        fixture.sqlGate.countDown();
        fixture.cancelGate.countDown();
        fixture.awaitReleased();
      }
      context.cancel(null);
      assertThat(fixture.cancellations.get()).isEqualTo(1);
      assertThat(fixture.cancelsAfterRelease.get()).isZero();
    }
  }

  @Test
  void abandonedAcquisitionsRemainCappedWithoutAQueue() throws Exception {
    int count = WorldCanonicalFrozenTopologyRepository.MAX_DEADLINE_READS;
    var fixture = new Fixture(0, count);
    fixture.acquireGate = new CountDownLatch(1);
    List<CompletableFuture<Optional<WorldCanonicalFrozenTopology>>> reads = new ArrayList<>();
    try {
      for (int index = 0; index < count; index++) {
        reads.add(fixture.async(Context.current(), Deadline.after(1, TimeUnit.SECONDS)));
      }
      await(fixture.acquireEntered);
      for (var read : reads) assertStatus(read, Status.Code.DEADLINE_EXCEEDED);
      assertThatThrownBy(
              () ->
                  fixture.repository.readCommitted(
                      fixture.request, Deadline.after(5, TimeUnit.SECONDS)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
      assertThat(fixture.acquisitions.get()).isEqualTo(count);
    } finally {
      fixture.acquireGate.countDown();
      fixture.awaitReleased();
    }
    assertThat(fixture.executions.get()).isZero();
    var next = new Fixture(0, 1);
    // Cancellation workers with no active statement may finish after the acquire workers.
    var cleanupBudget = Deadline.after(5, TimeUnit.SECONDS);
    while (true) {
      try {
        assertThat(next.repository.readCommitted(next.request, cleanupBudget)).isEmpty();
        break;
      } catch (StatusRuntimeException busy) {
        assertThat(busy.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        if (cleanupBudget.isExpired()) throw busy;
        Thread.yield();
      }
    }
    next.awaitReleased();
  }

  @Test
  void bothQueriesConsumeTheOriginalAbsoluteBudget() throws Exception {
    var now = new AtomicLong();
    var deadline =
        Deadline.after(
            5,
            TimeUnit.SECONDS,
            new Deadline.Ticker() {
              @Override
              public long nanoTime() {
                return now.get();
              }
            });
    var fixture = new Fixture(0, 1);
    fixture.includeBinding = true;
    fixture.afterFirst = () -> now.set(TimeUnit.SECONDS.toNanos(4));
    assertThat(fixture.repository.readCommitted(fixture.request, deadline)).isEmpty();
    assertThat(fixture.queryTimeouts).containsExactly(5, 1);
    assertThat(fixture.executions.get()).isEqualTo(2);
    assertThat(fixture.acquisitions.get()).isEqualTo(1);
  }

  @Test
  void expiryBetweenQueriesPreventsTheSecondQuery() throws Exception {
    var now = new AtomicLong();
    var deadline =
        Deadline.after(
            5,
            TimeUnit.SECONDS,
            new Deadline.Ticker() {
              @Override
              public long nanoTime() {
                return now.get();
              }
            });
    var fixture = new Fixture(0, 1);
    fixture.includeBinding = true;
    fixture.afterFirst = () -> now.set(TimeUnit.SECONDS.toNanos(6));
    assertThatThrownBy(() -> fixture.repository.readCommitted(fixture.request, deadline))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            error ->
                assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    fixture.awaitReleased();
    assertThat(fixture.executions.get()).isEqualTo(1);
  }

  @Test
  void completedReadRemovesCancellationListener() throws Exception {
    var fixture = new Fixture(0, 1);
    try (var context = Context.current().withCancellation()) {
      assertThat(
              fixture.async(context, Deadline.after(5, TimeUnit.SECONDS)).get(5, TimeUnit.SECONDS))
          .isEmpty();
      context.cancel(null);
      assertThat(fixture.cancellations.get()).isZero();
      assertThat(fixture.cancelsAfterRelease.get()).isZero();
    }
  }

  @Test
  void failedConnectionReturnRetainsItsSlotWithoutRetry() throws Exception {
    var field =
        WorldCanonicalFrozenTopologyRepository.class.getDeclaredField("DEADLINE_READ_SLOTS");
    field.setAccessible(true);
    var capacity = (Semaphore) field.get(null);
    int reserved = WorldCanonicalFrozenTopologyRepository.MAX_DEADLINE_READS - 1;
    var fixture = new Fixture(0, 1);
    fixture.failRelease = true;
    var isolationBudget = Deadline.after(5, TimeUnit.SECONDS);
    while (!capacity.tryAcquire(reserved + 1)) {
      if (isolationBudget.isExpired())
        throw new AssertionError("Prior read resources are still owned");
      Thread.yield();
    }
    capacity.release();
    boolean resourceStopped = false;
    try {
      try {
        assertThatThrownBy(
                () ->
                    fixture.repository.readCommitted(
                        fixture.request, Deadline.after(5, TimeUnit.SECONDS)))
            .isInstanceOf(DataAccessException.class)
            .hasMessage("Controlled connection return failed");
      } finally {
        fixture.awaitReleased();
        resourceStopped = true;
      }
      assertThat(fixture.releaseAttempts.get()).isEqualTo(1);
      assertThat(capacity.availablePermits()).isZero();
      assertThatThrownBy(
              () ->
                  fixture.repository.readCommitted(
                      fixture.request, Deadline.after(5, TimeUnit.SECONDS)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    } finally {
      try {
        if (resourceStopped) {
          // Only this fixture's mock resource is reclaimed, after its worker has ended. Restore
          // the one deliberately retained charge, never reset or drain shared admission state.
          fixture.backing.close();
          if (capacity.availablePermits() == 0) capacity.release();
        }
      } finally {
        capacity.release(reserved);
      }
    }
  }

  private static void assertStatus(CompletableFuture<?> read, Status.Code... expected)
      throws Exception {
    try {
      read.get(5, TimeUnit.SECONDS);
      throw new AssertionError("Expected a canceled or expired read");
    } catch (ExecutionException failure) {
      assertThat(failure.getCause())
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isIn((Object[]) expected));
    }
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private static java.util.concurrent.ScheduledExecutorService timer() {
    return Timer.INSTANCE;
  }

  private static final class Timer {
    private static final java.util.concurrent.ScheduledExecutorService INSTANCE =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).factory());
  }

  private static final class Fixture {
    private final AtomicInteger acquisitions = new AtomicInteger();
    private final AtomicInteger executions = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final AtomicInteger releaseAttempts = new AtomicInteger();
    private final AtomicInteger cancelsAfterRelease = new AtomicInteger();
    private final List<Integer> queryTimeouts = new CopyOnWriteArrayList<>();
    private final List<Thread> resourceWorkers = new CopyOnWriteArrayList<>();
    private final CountDownLatch acquireEntered;
    private final CountDownLatch executeEntered = new CountDownLatch(1);
    private final CountDownLatch executeFinished = new CountDownLatch(1);
    private final CountDownLatch cancelEntered = new CountDownLatch(1);
    private final CountDownLatch released;
    private CountDownLatch acquireGate = new CountDownLatch(0);
    private CountDownLatch sqlGate = new CountDownLatch(1);
    private CountDownLatch cancelGate = new CountDownLatch(0);
    private boolean includeBinding;
    private boolean failRelease;
    private Runnable afterFirst = () -> {};
    private final DraftCommitBinding binding;
    private final OwnerBinding owner;
    private final CaptureRequest request;
    private final WorldCanonicalFrozenTopologyRepository repository;
    private final MockConnection backing;

    private Fixture(int blockedQuery, int expectedAcquisitions) throws Exception {
      acquireEntered = new CountDownLatch(expectedAcquisitions);
      released = new CountDownLatch(expectedAcquisitions);
      var account = AuthoringFixtures.freshGraphRequest().accountBinding();
      binding =
          completeGraph(
              DraftCommitBinding.fromStored(
                  new String(account.gameDesignBinding(), StandardCharsets.UTF_8),
                  account.inputDigest()));
      var target = binding.target();
      owner =
          new OwnerBinding(
              "test",
              target.canonicalTenantId(),
              target.canonicalVersionId(),
              UUID.randomUUID(),
              target.gameDesignVersionRowId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "sha256:" + "a".repeat(64),
              UUID.randomUUID(),
              "sha256:" + "b".repeat(64),
              "sha256:" + "c".repeat(64));
      request =
          new CaptureRequest(
              owner.targetNamespace(),
              owner.canonicalTenantId(),
              owner.canonicalVersionId(),
              owner.intakeRequestId(),
              UUID.randomUUID(),
              "deadline-read",
              "d".repeat(64),
              1,
              PublicationDigestRequestBinding.full(
                      owner.canonicalTenantId().toString(),
                      Long.toString(owner.gameDesignVersionId()),
                      "deadline-read")
                  .derivedWorkflowIdentity(),
              binding.commitId().toString(),
              "e".repeat(64),
              3,
              binding.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
                  .map(
                      unit ->
                          new OwnedAffectedTuple(
                              unit.owner().name(),
                              unit.aggregateType(),
                              unit.aggregateId(),
                              unit.scopeType(),
                              unit.scopeId(),
                              unit.expectedEpoch()))
                  .toList());
      includeBinding = blockedQuery == 2;
      backing =
          new MockConnection(query -> rows(query.sql().contains("binding_json,binding_digest")));
      Connection connection =
          mock(
              Connection.class,
              invocation -> {
                Object answer = invoke(backing, invocation.getMethod(), invocation.getArguments());
                if (!(answer instanceof PreparedStatement statement)) return answer;
                return mock(
                    PreparedStatement.class,
                    call -> {
                      String name = call.getMethod().getName();
                      if (name.equals("setQueryTimeout")) queryTimeouts.add(call.getArgument(0));
                      if (name.equals("cancel")) {
                        if (released.getCount() == 0) cancelsAfterRelease.incrementAndGet();
                        cancellations.incrementAndGet();
                        cancelEntered.countDown();
                        await(cancelGate);
                        sqlGate.countDown();
                      }
                      boolean execution = name.equals("execute") || name.equals("executeQuery");
                      int index = execution ? executions.incrementAndGet() : 0;
                      if (execution && index == blockedQuery) {
                        executeEntered.countDown();
                        await(sqlGate);
                      }
                      Object value = invoke(statement, call.getMethod(), call.getArguments());
                      if (execution) {
                        if (index == 1) afterFirst.run();
                        executeFinished.countDown();
                      }
                      return value;
                    });
              });
      var configuration = new DefaultConfiguration();
      configuration.set(SQLDialect.POSTGRES);
      configuration.set(
          new ConnectionProvider() {
            @Override
            @SuppressFBWarnings(
                value = "EI_EXPOSE_REP",
                justification = "Controlled JDBC provider returns its one test-owned connection.")
            public Connection acquire() {
              resourceWorkers.add(Thread.currentThread());
              acquisitions.incrementAndGet();
              acquireEntered.countDown();
              try {
                await(acquireGate);
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
              }
              return connection;
            }

            @Override
            public void release(Connection acquired) {
              assertThat(acquired).isSameAs(connection);
              releaseAttempts.incrementAndGet();
              released.countDown();
              if (failRelease) throw new DataAccessException("Controlled connection return failed");
            }
          });
      repository =
          new WorldCanonicalFrozenTopologyRepository(
              DSL.using(configuration),
              mock(WorldAuthoredGraphSnapshotRepository.class),
              mock(WorldDraftTopologyCommitRepository.class),
              mock(WorldDraftDesignDigestService.class));
    }

    private static DraftCommitBinding completeGraph(DraftCommitBinding original) throws Exception {
      List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
      for (var revision : original.revisions()) {
        var mutation = WorldDesignMutationRevision.newBuilder();
        JsonFormat.parser().merge(revision.payload(), mutation);
        var scope =
            original.affectedUnits().stream()
                .filter(unit -> unit.aggregateId().equals(mutation.getAggregateId()))
                .filter(unit -> !"AGGREGATE".equals(unit.scopeType()))
                .findFirst()
                .orElseThrow();
        mutation
            .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
            .setScopeType(
                WorldDesignScopeType.valueOf("WORLD_DESIGN_SCOPE_TYPE_" + scope.scopeType()))
            .setScopeId(scope.scopeId());
        switch (mutation.getAggregateType()) {
          case WORLD_DESIGN_AGGREGATE_TYPE_REGION ->
              mutation.setRegion(RegionDesignMutation.newBuilder().setName("deadline region"));
          case WORLD_DESIGN_AGGREGATE_TYPE_ZONE ->
              mutation.setZone(
                  ZoneDesignMutation.newBuilder()
                      .setName("deadline zone")
                      .setRegionId(scope.scopeId()));
          case WORLD_DESIGN_AGGREGATE_TYPE_ROOM ->
              mutation.setRoom(
                  RoomDesignMutation.newBuilder()
                      .setName("deadline room")
                      .setZoneId(scope.scopeId()));
          default -> throw new AssertionError("Unexpected fixture family");
        }
        revisions.add(
            new DraftCommitBinding.RevisionPayload(
                revision.revisionOrder(),
                revision.revisionId(),
                revision.owner(),
                JsonFormat.printer().omittingInsignificantWhitespace().print(mutation.build())));
      }
      return DraftCommitBinding.create(
          original.target(),
          original.requestId(),
          original.commitId(),
          original.baseCommitId(),
          revisions,
          original.affectedUnits());
    }

    private void awaitReleased() throws InterruptedException {
      await(released);
      for (Thread worker : resourceWorkers) {
        assertThat(worker.join(Duration.ofSeconds(5))).isTrue();
      }
    }

    private MockResult[] rows(boolean selector) {
      DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
      Field<String> json = DSL.field("binding_json", String.class);
      Field<String> digest = DSL.field("binding_digest", String.class);
      Field<String> ownerJson = DSL.field("owner_binding_json", String.class);
      Result<Record> rows = dsl.newResult(new Field<?>[] {json, digest, ownerJson});
      if (selector && includeBinding) {
        Record row = dsl.newRecord(json, digest, ownerJson);
        row.set(json, binding.canonicalJson());
        row.set(digest, binding.digest());
        row.set(ownerJson, WorldDraftTopologyCommitRepository.ownerBindingJson(owner));
        rows.add(row);
      }
      return new MockResult[] {new MockResult(rows.size(), rows)};
    }

    private CompletableFuture<Optional<WorldCanonicalFrozenTopology>> async(
        Context context, Deadline deadline) {
      var value = new CompletableFuture<Optional<WorldCanonicalFrozenTopology>>();
      Thread.ofVirtual()
          .start(
              context.wrap(
                  () -> {
                    try {
                      value.complete(repository.readCommitted(request, deadline));
                    } catch (RuntimeException | Error failure) {
                      value.completeExceptionally(failure);
                    }
                  }));
      return value;
    }

    private static Object invoke(Object target, Method method, Object[] arguments)
        throws Throwable {
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }
}
