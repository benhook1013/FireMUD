package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.InvalidJwksException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceCustodyRejectedException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourcePreparedDenyException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.UnknownKeyException;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlled Account snapshots exercise custody checks, not Account producer or deployed PKI. */
class GameSessionAccountPublicJwksSourceTest {
  private static final byte[] BYTES = "{\"keys\":[]}".getBytes(StandardCharsets.US_ASCII);
  @TempDir Path temporaryDirectory;

  @Test
  void pinsActualAccountIdentityAndCopiesExactProtectedProjectionBytes() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("projection"));
    Path version = Files.createDirectory(root.resolve("..version1"));
    Path file = Files.write(version.resolve("jwks.json"), BYTES);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.setPosixFilePermissions(version, PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    Files.createSymbolicLink(root.resolve("..data"), Path.of("..version1"));
    Files.createSymbolicLink(root.resolve("jwks.json"), Path.of("..data/jwks.json"));
    long uid =
        ((Number) Files.getAttribute(root, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
    var projection = new ProtectedAccountPublicJwksProjection(root.resolve("jwks.json"), uid);
    var source = source(() -> snapshot(identity("test", "revision-1")), projection);

    PublicJwksSnapshot result = source.load();
    byte[] modified = result.jwksBytes();
    modified[0] = 0;

    assertThat(source.sourceIdentity()).isEqualTo(identity("test", "revision-1"));
    assertThat(result.jwksBytes()).containsExactly(BYTES);
    assertThat(source.load().jwksBytes()).containsExactly(BYTES);
  }

  @Test
  void rejectsReplacementForeverWithoutSilentlyRepinning() throws Exception {
    AtomicReference<SourceIdentity> current = new AtomicReference<>(identity("test", "revision-1"));
    var source = source(() -> snapshot(current.get()), projection(BYTES, BYTES));
    assertThat(source.sourceIdentity()).isEqualTo(current.get());
    current.set(identity("test", "revision-2"));
    assertInvalid(source);
    current.set(identity("test", "revision-1"));
    assertInvalid(source);
    assertThatThrownBy(source::sourceIdentity).isInstanceOf(SourceCustodyRejectedException.class);
  }

  @Test
  void crossNamespaceEvidenceIsInvalidEvenWithMatchingBytes() throws Exception {
    assertInvalid(
        source(() -> snapshot(identity("other", "revision-1")), projection(BYTES, BYTES)));
  }

  @Test
  void exactBytesMatterEvenForEquivalentJson() throws Exception {
    byte[] reformatted = "{ \"keys\": [] }".getBytes(StandardCharsets.US_ASCII);
    assertInvalid(
        source(
            () -> snapshot(identity("test", "revision-1")), projection(reformatted, reformatted)));
  }

  @Test
  void rejectsMountedBytesChangingAcrossAccountReads() throws Exception {
    byte[] changed = "{\"keys\":[{}]}".getBytes(StandardCharsets.US_ASCII);
    assertInvalid(
        source(() -> snapshot(identity("test", "revision-1")), projection(BYTES, changed)));
  }

  @Test
  void rejectsAccountIdentityOrBytesChangingAcrossMountedReads() throws Exception {
    for (boolean changeIdentity : new boolean[] {false, true}) {
      AtomicInteger calls = new AtomicInteger();
      TrustedPublicJwksSource account =
          () -> {
            if (calls.incrementAndGet() == 1) return snapshot(identity("test", "revision-1"));
            return changeIdentity
                ? snapshot(identity("test", "revision-2"))
                : new PublicJwksSnapshot(identity("test", "revision-1"), new byte[] {1});
          };
      assertInvalid(source(account, projection(BYTES, BYTES)));
    }
  }

  @Test
  void actualAccountOutageIsRetryableAndDoesNotRepin() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TrustedPublicJwksSource account =
        () -> {
          if (calls.incrementAndGet() == 1) throw new SourceUnavailableException();
          return snapshot(identity("test", "revision-1"));
        };
    var source = source(account, projection(BYTES, BYTES));
    assertThatThrownBy(source::load).isInstanceOf(SourceUnavailableException.class);
    assertThat(source.load().sourceIdentity()).isEqualTo(identity("test", "revision-1"));
  }

  @Test
  void outageCannotHideRemovedOrChangedLocalCustody() throws Exception {
    var projection = mock(ProtectedAccountPublicJwksProjection.class);
    when(projection.read()).thenAnswer(invocation -> BYTES.clone());
    AtomicReference<RuntimeException> unavailable = new AtomicReference<>();
    TrustedPublicJwksSource account =
        () -> {
          if (unavailable.get() != null) throw unavailable.get();
          return snapshot(identity("test", "revision-1"));
        };
    var source = source(account, projection);
    source.load();
    unavailable.set(new SourceUnavailableException());
    when(projection.read()).thenAnswer(invocation -> new byte[] {1});
    assertInvalid(source);
  }

  @Test
  void validUnchangedProjectionCanSurviveAFiniteAccountOutage() throws Exception {
    AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    TrustedPublicJwksSource account =
        () -> {
          if (unavailable.get()) throw new SourceUnavailableException();
          return snapshot(identity("test", "revision-1"));
        };
    var source = source(account, projection(BYTES, BYTES));
    source.load();
    unavailable.set(true);
    assertThatThrownBy(source::load).isInstanceOf(SourceUnavailableException.class);
    unavailable.set(false);
    assertThat(source.load().sourceIdentity()).isEqualTo(identity("test", "revision-1"));
  }

  @Test
  void missingOrUnsafeProjectionCannotBecomeAnOutageException() throws Exception {
    var missing =
        new ProtectedAccountPublicJwksProjection(temporaryDirectory.resolve("jwks.json"), 0L);
    assertInvalid(source(() -> snapshot(identity("test", "revision-1")), missing));
    var unsafe = mock(ProtectedAccountPublicJwksProjection.class);
    when(unsafe.read()).thenThrow(new IllegalArgumentException("unsafe test projection"));
    assertInvalid(source(() -> snapshot(identity("test", "revision-1")), unsafe));
  }

  @Test
  void absentSnapshotIsInvalid() throws Exception {
    assertInvalid(source(() -> null, projection(BYTES, BYTES)));
  }

  @Test
  void qualifiedPublicationLagRecoversOnTheSameSourceWithoutRepinning() throws Exception {
    var fixture = new PublicationFixture();
    fixture.source.load();
    fixture.publishNext();
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourceUnavailableException.class);
    assertThat(fixture.source.sourceIdentity()).isEqualTo(identity("test", "revision-1"));
    fixture.converge();
    assertThat(fixture.source.load().jwksBytes()).containsExactly(fixture.current.get());
  }

  @Test
  void proactiveAndDemandLagPreserveOriginalAgeAndRecoverWithoutAdmittingUnknownKeys()
      throws Exception {
    for (boolean proactive : new boolean[] {true, false}) {
      var fixture = new PublicationFixture();
      var cache = fixture.cache();
      cache.refresh();
      fixture.advance(5);
      fixture.publishNext();
      if (proactive) {
        cache.refresh();
      } else {
        assertThatThrownBy(() -> cache.keyFor("next")).isInstanceOf(UnknownKeyException.class);
      }
      assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
      assertThatThrownBy(() -> cache.keyFor("next")).isInstanceOf(UnknownKeyException.class);
      fixture.advance(5);
      assertThatThrownBy(() -> cache.keyFor("known"))
          .isInstanceOf(SourceUnavailableException.class);
      assertThatThrownBy(() -> cache.keyFor("next"))
          .isInstanceOfAny(SourceUnavailableException.class, UnknownKeyException.class);
      fixture.converge();
      fixture.advance(1);
      assertThat(cache.keyFor("next")).isEqualTo(fixture.key);
      cache.invalidateKid("known");
      cache.refresh();
      assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(UnknownKeyException.class);
      fixture.pin.set(identity("test", "revision-2"));
      assertThatThrownBy(cache::refresh).isInstanceOf(InvalidJwksException.class);
      fixture.pin.set(identity("test", "revision-1"));
      assertThatThrownBy(cache::refresh).isInstanceOf(InvalidJwksException.class);
      assertThatThrownBy(() -> cache.keyFor("next")).isInstanceOf(InvalidJwksException.class);
    }
  }

  @Test
  void hardDeniedKeysRemainDeniedAcrossPublicationLagAndRecovery() throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    cache.invalidateKid("known");
    fixture.publishNext();
    cache.refresh();
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(UnknownKeyException.class);
    fixture.converge();
    cache.refresh();
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(UnknownKeyException.class);
    assertThat(cache.keyFor("next")).isEqualTo(fixture.key);
  }

  @Test
  void qualifiedButChangedLocalBytesCannotBecomePublicationLag() throws Exception {
    var fixture = new PublicationFixture();
    fixture.source.load();
    fixture.mounted.set(publicJwks(fixture.key, "unexplained"));
    assertInvalid(fixture.source);
    fixture.converge();
    assertInvalid(fixture.source);
  }

  @Test
  void fingerprintReplacementRemainsTerminalAfterPublicationLagConverges() throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    fixture.current.set(publicJwks(newPublicKey(), "known"));
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
    fixture.converge();
    assertThatThrownBy(cache::refresh).isInstanceOf(InvalidJwksException.class);
    fixture.current.set(publicJwks(fixture.key, "known"));
    fixture.converge();
    assertThatThrownBy(cache::refresh).isInstanceOf(InvalidJwksException.class);
  }

  @Test
  void invalidRemoteProfilesCannotBecomePublicationLag() throws Exception {
    var key = newPublicKey();
    String valid = new String(publicJwks(key, "known"), StandardCharsets.US_ASCII);
    for (String invalid :
        new String[] {
          "{broken",
          "{\"keys\":[]}",
          valid.replace("\"RS256\"", "\"HS256\""),
          valid.replace("\"kty\":\"RSA\"", "\"d\":\"private\",\"kty\":\"RSA\"")
        }) {
      var fixture = new PublicationFixture();
      fixture.source.load();
      fixture.current.set(invalid.getBytes(StandardCharsets.US_ASCII));
      assertInvalid(fixture.source);
      fixture.publishNext();
      fixture.converge();
      assertInvalid(fixture.source);
    }
  }

  @Test
  void lagCannotHideChangedOrUnsafeProjectionDuringCustodyRecheck() throws Exception {
    for (boolean unsafe : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      fixture.source.load();
      fixture.publishNext();
      if (unsafe) {
        when(fixture.projection.read())
            .thenReturn(fixture.mounted.get().clone())
            .thenThrow(new IllegalArgumentException("unsafe projection"));
      } else {
        when(fixture.projection.read())
            .thenReturn(fixture.mounted.get().clone())
            .thenReturn(fixture.current.get().clone());
      }
      assertInvalid(fixture.source);
    }
  }

  @Test
  void matchedPublicationTransitionPreservesExpiryUntilALaterStableLoad() throws Exception {
    for (boolean proactive : new boolean[] {true, false}) {
      var fixture = new PublicationFixture();
      var cache = fixture.cache();
      cache.refresh();
      fixture.advance(5);
      fixture.afterAccountRead.set(
          () -> {
            fixture.publishNext();
            fixture.converge();
          });
      if (proactive) {
        cache.refresh();
      } else {
        assertThatThrownBy(() -> cache.keyFor("next")).isInstanceOf(UnknownKeyException.class);
      }
      int readsAfterTransition = fixture.accountReads.get();
      assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
      assertThat(fixture.accountReads.get()).isEqualTo(readsAfterTransition);
      assertThat(fixture.source.sourceIdentity()).isEqualTo(identity("test", "revision-1"));
      fixture.advance(5);
      assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
      assertThat(fixture.accountReads.get()).isEqualTo(readsAfterTransition + 2);
      assertThat(cache.keyFor("next")).isEqualTo(fixture.key);
    }
  }

  @Test
  void matchedTransitionDoesNotRenewExpiredCacheOrInstallAnUnknownKey() throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    fixture.advance(10);
    fixture.afterAccountRead.set(
        () -> {
          fixture.publishNext();
          fixture.converge();
        });
    assertThatThrownBy(() -> cache.keyFor("next")).isInstanceOf(SourceUnavailableException.class);
    cache.refresh();
    assertThat(cache.keyFor("next")).isEqualTo(fixture.key);
  }

  @Test
  void matchedTransitionCannotQualifyStartupOrAReplacementIdentity() throws Exception {
    for (boolean qualified : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      if (qualified) fixture.source.load();
      fixture.afterAccountRead.set(
          () -> {
            fixture.publishNext();
            fixture.converge();
            if (qualified) fixture.pin.set(identity("test", "revision-2"));
          });
      assertInvalid(fixture.source);
      fixture.pin.set(identity("test", "revision-1"));
      assertInvalid(fixture.source);
    }
  }

  @Test
  void transitionRequiresAnExactValidAuthenticatedNewSnapshot() throws Exception {
    for (boolean matched : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      fixture.source.load();
      fixture.afterAccountRead.set(
          () -> {
            fixture.current.set("{broken".getBytes(StandardCharsets.US_ASCII));
            fixture.mounted.set(
                matched ? fixture.current.get() : publicJwks(fixture.key, "unexplained"));
          });
      assertInvalid(fixture.source);
      fixture.publishNext();
      fixture.converge();
      assertInvalid(fixture.source);
    }
  }

  @Test
  void matchedTransitionCannotHideUnsafeOrChangedProjectionOnRecheck() throws Exception {
    for (boolean unsafe : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      fixture.source.load();
      AtomicInteger projectionReads = new AtomicInteger();
      when(fixture.projection.read())
          .thenAnswer(
              invocation -> {
                if (projectionReads.incrementAndGet() == 3) {
                  if (unsafe) throw new IllegalArgumentException("unsafe projection");
                  return publicJwks(fixture.key, "unexplained");
                }
                return fixture.mounted.get().clone();
              });
      fixture.afterAccountRead.set(
          () -> {
            fixture.publishNext();
            fixture.converge();
          });
      assertInvalid(fixture.source);
      assertInvalid(fixture.source);
    }
  }

  @Test
  void separatelyObservedCustodyRejectionImmediatelyBlocksWarmCachedKeys() throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
    byte[] original = fixture.mounted.get();
    fixture.mounted.set("tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertInvalid(fixture.source);
    assertThat(fixture.source.isCustodyRejected()).isTrue();
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(InvalidJwksException.class);
    fixture.mounted.set(original);
    assertThatThrownBy(cache::refresh).isInstanceOf(InvalidJwksException.class);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(InvalidJwksException.class);
  }

  @Test
  void preparedDenyAtStartupPinsIdentityAndRecoversOnlyFromEligibleCommittedSnapshot()
      throws Exception {
    var fixture = new PublicationFixture();
    assertThat(fixture.source.preparedDenyEpoch()).isZero();
    fixture.denied.set(true);
    assertThat(fixture.source.sourceIdentity()).isEqualTo(fixture.pin.get());
    assertThat(fixture.source.isPreparedDenied()).isTrue();
    assertThat(fixture.source.preparedDenyEpoch()).isEqualTo(1);
    var cache = fixture.cache();
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourcePreparedDenyException.class);
    fixture.denied.set(false);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThat(fixture.source.isPreparedDenied()).isTrue();
    fixture.recoveryEligible.set(true);
    cache.refresh();
    assertThat(fixture.source.isPreparedDenied()).isFalse();
    assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
    assertThat(fixture.source.sourceIdentity()).isEqualTo(fixture.pin.get());
    assertThat(fixture.source.preparedDenyEpoch()).isEqualTo(1);
    fixture.denied.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThat(fixture.source.preparedDenyEpoch()).isEqualTo(2);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourcePreparedDenyException.class);
  }

  @Test
  void startupPreparedDenyNeverPermitsSourceReplacement() throws Exception {
    for (boolean wrongNamespace : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      fixture.denied.set(true);
      SourceIdentity original = fixture.source.sourceIdentity();
      fixture.pin.set(
          wrongNamespace ? identity("other", "revision-1") : identity("test", "revision-2"));
      assertInvalid(fixture.source);
      fixture.pin.set(original);
      fixture.denied.set(false);
      fixture.recoveryEligible.set(true);
      assertInvalid(fixture.source);
      assertThatThrownBy(fixture.source::sourceIdentity)
          .isInstanceOf(SourceCustodyRejectedException.class);
    }
  }

  @Test
  void separatelyObservedDenyBlocksWarmCachedKeysThroughoutOutageWithoutRenewingAge()
      throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    fixture.advance(5);
    fixture.denied.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourcePreparedDenyException.class);
    fixture.unavailable.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourceUnavailableException.class);
    assertThatThrownBy(cache::refresh).isInstanceOf(SourcePreparedDenyException.class);
    fixture.advance(6);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourcePreparedDenyException.class);
    fixture.unavailable.set(false);
    fixture.denied.set(false);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourcePreparedDenyException.class);
    fixture.recoveryEligible.set(true);
    cache.refresh();
    assertThat(cache.keyFor("known")).isEqualTo(fixture.key);
    fixture.advance(10);
    fixture.unavailable.set(true);
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(SourceUnavailableException.class);
  }

  @Test
  void recoveryRequiresBothEligibleAccountReadsAndCoherentMountedBytes() throws Exception {
    var fixture = new PublicationFixture();
    fixture.source.load();
    fixture.denied.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    fixture.denied.set(false);
    fixture.afterAccountRead.set(() -> fixture.recoveryEligible.set(true));
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThat(fixture.source.isPreparedDenied()).isTrue();
    fixture.publishNext();
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourceUnavailableException.class);
    assertThat(fixture.source.isPreparedDenied()).isTrue();
    fixture.converge();
    PublicJwksSnapshot recovered = fixture.source.load();
    assertThat(recovered.committedRecoveryEligible()).isTrue();
    assertThat(recovered.jwksBytes()).containsExactly(fixture.current.get());
    assertThat(fixture.source.isPreparedDenied()).isFalse();
  }

  @Test
  void cachedKeyDenialDoesNotWaitForBlockedSamePinRecoveryLoad() throws Exception {
    var fixture = new PublicationFixture();
    var cache = fixture.cache();
    cache.refresh();
    fixture.denied.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    fixture.denied.set(false);
    fixture.recoveryEligible.set(true);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    fixture.beforeAccountRead.set(
        () -> {
          entered.countDown();
          try {
            if (!release.await(3, TimeUnit.SECONDS))
              throw new IllegalStateException("test timeout");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        });
    try (var executor = Executors.newFixedThreadPool(2)) {
      var recovering = executor.submit(fixture.source::load);
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        var denied =
            executor.submit(
                () -> {
                  assertThat(fixture.source.isPreparedDenied()).isTrue();
                  assertThatThrownBy(() -> cache.keyFor("known"))
                      .isInstanceOf(SourcePreparedDenyException.class);
                });
        denied.get(1, TimeUnit.SECONDS);
        cache.invalidateKid("known");
      } finally {
        release.countDown();
      }
      assertThat(recovering.get(3, TimeUnit.SECONDS).committedRecoveryEligible()).isTrue();
    }
    cache.refresh();
    assertThatThrownBy(() -> cache.keyFor("known")).isInstanceOf(UnknownKeyException.class);
  }

  @Test
  void secondAccountReadDenyLatchesBeforeReturningAnySnapshot() throws Exception {
    var fixture = new PublicationFixture();
    fixture.source.load();
    fixture.afterAccountRead.set(() -> fixture.denied.set(true));
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    assertThat(fixture.source.isPreparedDenied()).isTrue();
    fixture.denied.set(false);
    fixture.recoveryEligible.set(true);
    assertThat(fixture.source.load().sourceIdentity()).isEqualTo(fixture.pin.get());
    assertThat(fixture.source.isPreparedDenied()).isFalse();
  }

  @Test
  void preparedDenyCannotHideReplacementIdentityOrLocalTampering() throws Exception {
    for (boolean replaceIdentity : new boolean[] {false, true}) {
      var fixture = new PublicationFixture();
      fixture.source.load();
      fixture.denied.set(true);
      assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
      if (replaceIdentity) fixture.pin.set(identity("test", "revision-2"));
      else when(fixture.projection.read()).thenThrow(new IllegalArgumentException("unsafe"));
      assertInvalid(fixture.source);
      fixture.denied.set(false);
      fixture.recoveryEligible.set(true);
      fixture.pin.set(identity("test", "revision-1"));
      doAnswer(invocation -> fixture.mounted.get().clone()).when(fixture.projection).read();
      assertInvalid(fixture.source);
    }
  }

  @Test
  void malformedSnapshotAfterDenyRemainsTerminalEvenWithoutRecoveryEligibility() throws Exception {
    var fixture = new PublicationFixture();
    fixture.source.load();
    fixture.denied.set(true);
    assertThatThrownBy(fixture.source::load).isInstanceOf(SourcePreparedDenyException.class);
    fixture.denied.set(false);
    fixture.current.set("{broken".getBytes(StandardCharsets.US_ASCII));
    fixture.converge();
    assertInvalid(fixture.source);
    fixture.publishNext();
    fixture.converge();
    fixture.recoveryEligible.set(true);
    assertInvalid(fixture.source);
  }

  private static RSAPublicKey newPublicKey() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(3072);
      return (RSAPublicKey) generator.generateKeyPair().getPublic();
    } catch (GeneralSecurityException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static byte[] publicJwks(RSAPublicKey key, String... kids) {
    String modulus = unsignedBase64(key.getModulus().toByteArray());
    String exponent = unsignedBase64(key.getPublicExponent().toByteArray());
    String[] entries = new String[kids.length];
    for (int index = 0; index < kids.length; index++) {
      entries[index] =
          "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
              + kids[index]
              + "\",\"n\":\""
              + modulus
              + "\",\"e\":\""
              + exponent
              + "\",\"key_ops\":[\"verify\"]}";
    }
    return ("{\"keys\":[" + String.join(",", entries) + "]}").getBytes(StandardCharsets.US_ASCII);
  }

  private static String unsignedBase64(byte[] value) {
    byte[] unsigned = value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
  }

  private static final class PublicationFixture {
    private static final RSAPublicKey SHARED_KEY = newPublicKey();
    private final RSAPublicKey key = SHARED_KEY;
    private final AtomicReference<Runnable> beforeAccountRead = new AtomicReference<>();
    private final AtomicReference<Runnable> afterAccountRead = new AtomicReference<>();
    private final AtomicInteger accountReads = new AtomicInteger();
    private final AtomicReference<Boolean> denied = new AtomicReference<>(false);
    private final AtomicReference<Boolean> recoveryEligible = new AtomicReference<>(false);
    private final AtomicReference<Boolean> unavailable = new AtomicReference<>(false);
    private final AtomicReference<SourceIdentity> pin =
        new AtomicReference<>(identity("test", "revision-1"));
    private final AtomicReference<byte[]> current = new AtomicReference<>(publicJwks(key, "known"));
    private final AtomicReference<byte[]> mounted = new AtomicReference<>(current.get());
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-10T00:00:00Z"));
    private final ProtectedAccountPublicJwksProjection projection =
        mock(ProtectedAccountPublicJwksProjection.class);
    private final GameSessionAccountPublicJwksSource source;

    private PublicationFixture() throws Exception {
      when(projection.read()).thenAnswer(invocation -> mounted.get().clone());
      source =
          new GameSessionAccountPublicJwksSource(
              () -> {
                accountReads.incrementAndGet();
                Runnable before = beforeAccountRead.getAndSet(null);
                if (before != null) before.run();
                if (unavailable.get()) throw new SourceUnavailableException();
                var snapshot =
                    new ReadResult(
                        pin.get(),
                        denied.get() ? new byte[0] : current.get(),
                        denied.get(),
                        !denied.get() && recoveryEligible.get());
                Runnable action = afterAccountRead.getAndSet(null);
                if (action != null) action.run();
                return snapshot;
              },
              projection,
              "test");
    }

    private AccountPublicJwksCache cache() {
      Clock clock = mock(Clock.class);
      when(clock.instant()).thenAnswer(invocation -> now.get());
      return new AccountPublicJwksCache(
          source, source.sourceIdentity(), clock, Duration.ofSeconds(10));
    }

    private void publishNext() {
      current.set(publicJwks(key, "known", "next"));
    }

    private void converge() {
      mounted.set(current.get());
    }

    private void advance(long seconds) {
      now.updateAndGet(value -> value.plusSeconds(seconds));
    }
  }

  private static GameSessionAccountPublicJwksSource source(
      TrustedPublicJwksSource account, ProtectedAccountPublicJwksProjection projection) {
    return new GameSessionAccountPublicJwksSource(
        () -> {
          PublicJwksSnapshot snapshot = account.load();
          return snapshot == null
              ? null
              : new ReadResult(
                  snapshot.sourceIdentity(),
                  snapshot.jwksBytes(),
                  false,
                  snapshot.committedRecoveryEligible());
        },
        projection,
        "test");
  }

  private static ProtectedAccountPublicJwksProjection projection(byte[] before, byte[] after)
      throws Exception {
    var projection = mock(ProtectedAccountPublicJwksProjection.class);
    when(projection.read())
        .thenAnswer(invocation -> before.clone())
        .thenAnswer(invocation -> after.clone());
    return projection;
  }

  private static PublicJwksSnapshot snapshot(SourceIdentity identity) {
    return new PublicJwksSnapshot(identity, BYTES);
  }

  private static SourceIdentity identity(String namespace, String revision) {
    return new SourceIdentity(
        "test",
        "test-cluster",
        "11111111-1111-4111-8111-111111111111",
        namespace,
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        revision,
        "https://kubernetes.example:6443",
        "a".repeat(64));
  }

  private static void assertInvalid(GameSessionAccountPublicJwksSource source) {
    assertThatThrownBy(source::load)
        .isInstanceOf(SourceCustodyRejectedException.class)
        .hasNoCause();
  }
}
