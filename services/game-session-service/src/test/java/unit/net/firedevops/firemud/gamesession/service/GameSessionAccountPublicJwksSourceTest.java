package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceCustodyRejectedException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;
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

  private static GameSessionAccountPublicJwksSource source(
      TrustedPublicJwksSource account, ProtectedAccountPublicJwksProjection projection) {
    return new GameSessionAccountPublicJwksSource(account, projection, "test");
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
