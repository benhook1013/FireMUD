package net.firedevops.firemud.gamesession.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceCustodyRejectedException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;

/**
 * Pins current Account provenance once and requires the exact protected mounted bytes each load.
 */
public final class GameSessionAccountPublicJwksSource implements TrustedPublicJwksSource {
  private final TrustedPublicJwksSource account;
  private final ProtectedAccountPublicJwksProjection projection;
  private final String namespace;
  private SourceIdentity pinned;
  private boolean quarantined;
  private byte[] lastAcceptedBytes;

  public GameSessionAccountPublicJwksSource(AccountPublicJwksReadClient account, String namespace) {
    this(
        Objects.requireNonNull(account)::readCurrent,
        new ProtectedAccountPublicJwksProjection(),
        namespace);
  }

  GameSessionAccountPublicJwksSource(
      TrustedPublicJwksSource account,
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
    if (pinned == null) load();
    return pinned;
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
      PublicJwksSnapshot before = requireSnapshot(account.load());
      requireBytes(before, first);
      PublicJwksSnapshot after = requireSnapshot(account.load());
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
      pinned = before.sourceIdentity();
      if (lastAcceptedBytes != null) Arrays.fill(lastAcceptedBytes, (byte) 0);
      lastAcceptedBytes = first.clone();
      return new PublicJwksSnapshot(pinned, first);
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

  private PublicJwksSnapshot requireSnapshot(PublicJwksSnapshot snapshot) {
    if (snapshot == null
        || !namespace.equals(snapshot.sourceIdentity().namespace())
        || (pinned != null && !pinned.equals(snapshot.sourceIdentity()))) {
      throw new InvalidJwksException();
    }
    return snapshot;
  }

  private void requireBytes(PublicJwksSnapshot snapshot, byte[] expected) {
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
