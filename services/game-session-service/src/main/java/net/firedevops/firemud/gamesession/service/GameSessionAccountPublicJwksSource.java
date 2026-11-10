package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceCustodyRejectedException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourcePreparedDenyException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;

/**
 * Pins current Account provenance once and requires the exact protected mounted bytes each load.
 */
public final class GameSessionAccountPublicJwksSource implements TrustedPublicJwksSource {
  private final Supplier<ReadResult> account;
  private final ProtectedAccountPublicJwksProjection projection;
  private final String namespace;
  private SourceIdentity pinned;
  private volatile boolean quarantined;
  private volatile boolean preparedDenied;
  private final java.util.concurrent.atomic.AtomicLong preparedDenyEpoch =
      new java.util.concurrent.atomic.AtomicLong();
  private byte[] lastAcceptedBytes;

  public GameSessionAccountPublicJwksSource(AccountPublicJwksReadClient account, String namespace) {
    this(
        Objects.requireNonNull(account)::readCurrent,
        new ProtectedAccountPublicJwksProjection(),
        namespace);
  }

  GameSessionAccountPublicJwksSource(
      Supplier<ReadResult> account,
      ProtectedAccountPublicJwksProjection projection,
      String namespace) {
    this.account = Objects.requireNonNull(account);
    this.projection = Objects.requireNonNull(projection);
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Game Session workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Initializes from authenticated Account evidence, never from configured identity strings. */
  public synchronized SourceIdentity sourceIdentity() {
    if (quarantined) throw new SourceCustodyRejectedException();
    if (pinned == null) {
      try {
        load();
      } catch (SourcePreparedDenyException denied) {
        // An authenticated deny supplies the lifetime pin without qualifying any keys.
        if (pinned == null) throw denied;
      }
    }
    return pinned;
  }

  @Override
  public boolean isPreparedDenied() {
    // Cache calls this under its own monitor; never wait for an in-flight remote read.
    return preparedDenied;
  }

  @Override
  public boolean isCustodyRejected() {
    return quarantined;
  }

  @Override
  public long preparedDenyEpoch() {
    return preparedDenyEpoch.get();
  }

  @Override
  public synchronized PublicJwksSnapshot load() {
    if (quarantined) throw new SourceCustodyRejectedException();
    byte[] first = null;
    byte[] last = null;
    byte[] rechecked = null;
    boolean qualifiedTransition = false;
    try {
      first = projection.read();
      ReadResult before = requireResult(account.get(), null);
      requireBytes(before, first);
      ReadResult after = requireResult(account.get(), before.sourceIdentity());
      last = projection.read();
      if (!before.sourceIdentity().equals(after.sourceIdentity())) {
        throw new InvalidJwksException();
      }
      requireBytes(after, last);
      if (!Arrays.equals(first, last)) {
        if (pinned == null
            || lastAcceptedBytes == null
            || !Arrays.equals(first, lastAcceptedBytes)) {
          throw new InvalidJwksException();
        }
        AccountPublicJwksCache.validatePublicJwks(first);
        AccountPublicJwksCache.validatePublicJwks(last);
        // Both protected reads matched their authenticated same-pin Account snapshots. A
        // publication between those intact reads requires a later stable load, not requalification.
        qualifiedTransition = true;
        throw new SourceUnavailableException();
      }
      if (preparedDenied) {
        AccountPublicJwksCache.validatePublicJwks(first);
        if (!before.committedRecoveryEligible() || !after.committedRecoveryEligible()) {
          throw new SourcePreparedDenyException();
        }
      }
      pinned = before.sourceIdentity();
      if (lastAcceptedBytes != null) Arrays.fill(lastAcceptedBytes, (byte) 0);
      lastAcceptedBytes = first.clone();
      boolean recoveryEligible =
          before.committedRecoveryEligible() && after.committedRecoveryEligible();
      preparedDenied = false;
      return new PublicJwksSnapshot(pinned, first, recoveryEligible, preparedDenyEpoch.get());
    } catch (SourcePreparedDenyException denied) {
      // A valid prepared deny is recoverable and never installs bytes or renews cache age.
      throw denied;
    } catch (SourceUnavailableException unavailable) {
      // A network outage cannot conceal local custody loss or changed projected content.
      try {
        rechecked = projection.read();
        byte[] expected = qualifiedTransition ? last : first;
        if (expected == null
            || !Arrays.equals(expected, rechecked)
            || (!qualifiedTransition
                && lastAcceptedBytes != null
                && !Arrays.equals(lastAcceptedBytes, rechecked))) {
          throw new InvalidJwksException();
        }
      } catch (RuntimeException | IOException invalid) {
        quarantined = true;
        throw new SourceCustodyRejectedException();
      }
      throw unavailable;
    } catch (RuntimeException | IOException invalid) {
      // No replacement identity, custody mismatch or concurrent divergence can silently recover.
      quarantined = true;
      throw new SourceCustodyRejectedException();
    } finally {
      if (first != null) Arrays.fill(first, (byte) 0);
      if (last != null) Arrays.fill(last, (byte) 0);
      if (rechecked != null) Arrays.fill(rechecked, (byte) 0);
    }
  }

  private ReadResult requireResult(ReadResult result, SourceIdentity expected) {
    if (result == null
        || result.sourceIdentity() == null
        || !namespace.equals(result.sourceIdentity().namespace())
        || (pinned != null && !pinned.equals(result.sourceIdentity()))
        || (expected != null && !expected.equals(result.sourceIdentity()))) {
      throw new InvalidJwksException();
    }
    if (result.preparedDeny()) {
      if (pinned == null) pinned = result.sourceIdentity();
      if (!preparedDenied) {
        if (preparedDenyEpoch.get() == Long.MAX_VALUE) throw new InvalidJwksException();
        preparedDenyEpoch.incrementAndGet();
      }
      preparedDenied = true;
      throw new SourcePreparedDenyException();
    }
    return result;
  }

  private void requireBytes(ReadResult snapshot, byte[] expected) {
    byte[] actual = snapshot.jwksBytes();
    try {
      if (actual == null) throw new InvalidJwksException();
      if (!Arrays.equals(actual, expected)) {
        if (pinned != null
            && lastAcceptedBytes != null
            && Arrays.equals(lastAcceptedBytes, expected)) {
          AccountPublicJwksCache.validatePublicJwks(actual);
          // Account may publish before this already qualified mount converges. The unavailable
          // catch rechecks local custody and requires the unchanged last accepted bytes.
          throw new SourceUnavailableException();
        }
        throw new InvalidJwksException();
      }
    } finally {
      if (actual != null) Arrays.fill(actual, (byte) 0);
    }
  }
}
