package net.firedevops.firemud.gamedesign.publication;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionClient;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalCompletionGrpcCodec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit, unregistered reconciliation of the original committed GDS seal with World. */
public final class GameDesignWorldPublicationTerminalProducer implements AutoCloseable {
  private final GameDesignPublicationOperationService owner;
  private final WorldPublicationTerminalCompletionClient completion;
  private final String namespace;
  private final Object lifecycleLock = new Object();
  private int inFlightReconciliations;
  private boolean closed;
  private boolean closeComplete;

  public GameDesignWorldPublicationTerminalProducer(
      GameDesignPublicationOperationService owner,
      WorldPublicationTerminalCompletionClient completion,
      String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.completion = Objects.requireNonNull(completion);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical workload namespace required");
    this.namespace = namespace;
  }

  /** File-backed mTLS lifecycle for an explicitly assembled component; never registers ingress. */
  public static GameDesignWorldPublicationTerminalProducer usingFileBackedMtls(
      GameDesignPublicationOperationService owner,
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tls,
      GrpcChannelFactory channels,
      String namespace)
      throws IOException {
    Objects.requireNonNull(owner);
    var client = new WorldPublicationTerminalCompletionClient(endpoints, tls, channels, namespace);
    try {
      client.init();
      return new GameDesignWorldPublicationTerminalProducer(owner, client, namespace);
    } catch (IOException | RuntimeException failure) {
      try {
        client.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /**
   * Every retry independently reads the immutable GDS result and exactly replays World completion.
   */
  public GameDesignPublicationTerminalEvidence reconcile(
      String tenant,
      String workflow,
      long version,
      String selectionDigest,
      GameDesignPublicationTerminalEvidence.Outcome expectedOutcome) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Independent World publication handoff required");
    admit(expectedOutcome);
    try {
      var retained =
          owner
              .readExact(tenant, workflow, version, selectionDigest)
              .orElseThrow(() -> new IllegalStateException("PUBLICATION_TERMINAL_PENDING"));
      var operation = retained.operation();
      // Repeat the complete scope check even for controlled owner doubles.
      if (!tenant.equals(operation.tenantKey())
          || !workflow.equals(operation.workflowId())
          || version != operation.versionId()
          || !selectionDigest.equals(operation.selectionDigest()))
        throw new IllegalStateException("PUBLICATION_TERMINAL_SCOPE_CONFLICT");
      if (!expectedOutcome.name().equals(retained.outcome())
          || retained.terminalEvidenceBytes() == null)
        throw new IllegalStateException("PUBLICATION_TERMINAL_UNRESOLVED");
      var terminal =
          GameDesignPublicationTerminalEvidence.fromStored(retained.terminalEvidenceBytes());
      // Historical original bytes remain readable, but cannot acquire a fresh producer profile.
      operation.inventory();
      if (terminal.outcome() != expectedOutcome
          || !Arrays.equals(operation.canonicalBytes(), terminal.operationBytes())
          || !namespace.equals(terminal.worldEvidence().request().targetNamespace()))
        throw new IllegalStateException("PUBLICATION_TERMINAL_EVIDENCE_CONFLICT");
      var request =
          WorldPublicationTerminalCompletionGrpcCodec.Request.create(
              namespace, operation.canonicalBytes(), terminal.canonicalBytes());
      var confirmed = completion.complete(request);
      if (confirmed == null
          || !Arrays.equals(terminal.canonicalBytes(), confirmed.canonicalBytes()))
        throw new IllegalStateException("WORLD_PUBLICATION_TERMINAL_PENDING");
      return confirmed;
    } finally {
      release();
    }
  }

  private void admit(GameDesignPublicationTerminalEvidence.Outcome expectedOutcome) {
    synchronized (lifecycleLock) {
      if (closed) throw new IllegalStateException("World publication terminal producer closed");
      Objects.requireNonNull(expectedOutcome);
      inFlightReconciliations++;
    }
  }

  private void release() {
    synchronized (lifecycleLock) {
      inFlightReconciliations--;
      if (inFlightReconciliations == 0) lifecycleLock.notifyAll();
    }
  }

  @Override
  public void close() throws IOException {
    boolean interrupted = false;
    boolean closeClient = false;
    synchronized (lifecycleLock) {
      if (!closed) {
        closed = true;
        while (inFlightReconciliations > 0) {
          try {
            lifecycleLock.wait();
          } catch (InterruptedException ignored) {
            interrupted = true;
          }
        }
        closeClient = true;
      } else {
        while (!closeComplete) {
          try {
            lifecycleLock.wait();
          } catch (InterruptedException ignored) {
            interrupted = true;
          }
        }
      }
    }
    if (closeClient) {
      try {
        completion.close();
      } finally {
        synchronized (lifecycleLock) {
          closeComplete = true;
          lifecycleLock.notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
      }
    } else if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
