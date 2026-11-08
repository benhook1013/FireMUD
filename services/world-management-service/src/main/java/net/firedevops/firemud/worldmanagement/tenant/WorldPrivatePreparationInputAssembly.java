package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.gamesession.CurrentPrivatePlaytestLifecycleReadEvidence.Scope;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Snapshot;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Independent committed source reread only; no World write, manifest or ongoing source hold. */
public final class WorldPrivatePreparationInputAssembly {
  private final WorldPrivatePreparationSourceClient source;
  private final Clock clock;

  public WorldPrivatePreparationInputAssembly(
      WorldPrivatePreparationSourceClient source, Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.clock = Objects.requireNonNull(clock);
  }

  public Inputs assemble(Scope scope, UUID expectedPreparationRequestId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Private World input assembly refuses ambient SQL");
    Deadline deadline =
        Deadline.after(
            PrivateWorldPreparationReadGrpcCodec.CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    if (Context.current().getDeadline() != null)
      deadline = deadline.minimum(Context.current().getDeadline());
    Evidence first = source.read(scope, expectedPreparationRequestId, deadline).evidence();
    Evidence second = source.read(scope, expectedPreparationRequestId, deadline).evidence();
    if (first.request().readRequestId().equals(second.request().readRequestId())
        || second.snapshot().observedAt().isBefore(first.snapshot().observedAt())
        || !Arrays.equals(
            PrivateWorldPreparationReadGrpcCodec.stableBytes(first.snapshot()),
            PrivateWorldPreparationReadGrpcCodec.stableBytes(second.snapshot())))
      throw new IllegalStateException("Private World preparation changed between committed reads");
    first.snapshot().requireCurrentAt(clock.instant());
    second.snapshot().requireCurrentAt(clock.instant());
    if (Context.current().isCancelled()) throw Status.CANCELLED.asRuntimeException();
    if (deadline.isExpired()) throw Status.DEADLINE_EXCEEDED.asRuntimeException();
    return new Inputs(first, second);
  }

  /** Two actual source observations, explicitly non-admitting and unprotected after return. */
  public static final class Inputs {
    private final Evidence first;
    private final Evidence readback;

    private Inputs(Evidence first, Evidence readback) {
      this.first = first;
      this.readback = readback;
    }

    public Snapshot snapshot() {
      return readback.snapshot();
    }

    public Evidence firstRead() {
      return first;
    }

    public Evidence committedReadback() {
      return readback;
    }

    public boolean admissionAllowed() {
      return false;
    }
  }
}
