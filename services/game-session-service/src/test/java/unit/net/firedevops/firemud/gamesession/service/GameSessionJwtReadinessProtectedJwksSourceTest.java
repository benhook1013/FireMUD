package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.gamesession.service.GameSessionJwtReadinessLocalIdentityProvider.LocalObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameSessionJwtReadinessProtectedJwksSourceTest {
  private static final String JWKS = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"projected\"}]}";
  private static final Set<PosixFilePermission> MODE_0755 =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_EXECUTE);
  private static final Set<PosixFilePermission> MODE_0444 =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

  @TempDir Path tempDirectory;

  @Test
  void readsOnlyDigestMatchedAtomicProjectionAndAllowsOwnerWritable0755Directories()
      throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    PublicJwksSnapshot snapshot = source.load();

    assertThat(snapshot.sourceIdentity()).isEqualTo(fixture.sourceIdentity);
    assertThat(snapshot.jwksBytes()).isEqualTo(JWKS.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void rejectsProjectedBytesThatDoNotMatchAccountMetadataDigest() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    var source = fixture.source(fixture.observation("a".repeat(64)));

    assertUnavailable(source);
  }

  @Test
  void rejectsNonAtomicWriterProjectionShape() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    Files.delete(fixture.projection.resolve("jwks.json"));
    Files.createSymbolicLink(fixture.projection.resolve("jwks.json"), Path.of("jwks.json"));
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    assertUnavailable(source);
  }

  @Test
  void rejectsGroupOrOtherWritableProjectionDirectoriesAndAnyWritableFile() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE),
        MODE_0755,
        MODE_0444);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));
    assertUnavailable(source);

    fixture.createProjection(MODE_0755, MODE_0755, MODE_0755);
    source = fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));
    assertUnavailable(source);
  }

  @Test
  void rejectsChangedLocalIdentityAcrossProtectedFileRead() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    var source =
        fixture.source(
            fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))),
            fixture.observation("b".repeat(64)));

    assertUnavailable(source);
  }

  private static void assertUnavailable(GameSessionJwtReadinessProtectedJwksSource source) {
    assertThatThrownBy(source::load).isInstanceOf(SourceUnavailableException.class);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception unavailable) {
      throw new AssertionError(unavailable);
    }
  }

  private final class ProjectionFixture {
    private final Path projection = tempDirectory.resolve("jwks");
    private final Path dataDirectory = projection.resolve("..2026_10_09_00_00_00");
    private final Path jwksPath = projection.resolve("jwks.json");
    private final long ownerUid;
    private final SourceIdentity sourceIdentity =
        new SourceIdentity(
            "prod",
            "cluster-a",
            "11111111-1111-4111-8111-111111111111",
            "firemud-prod",
            "22222222-2222-4222-8222-222222222222",
            "55555555-5555-4555-8555-555555555555",
            "account-api-r1",
            "https://kubernetes.example.test:6443",
            "9".repeat(64));

    private ProjectionFixture(String bytes) throws Exception {
      Files.createDirectories(projection);
      Files.createDirectory(dataDirectory);
      Files.writeString(dataDirectory.resolve("jwks.json"), bytes, StandardCharsets.US_ASCII);
      ownerUid = ((Number) Files.getAttribute(projection, "unix:uid")).longValue();
    }

    private void createProjection(
        Set<PosixFilePermission> rootMode,
        Set<PosixFilePermission> dataMode,
        Set<PosixFilePermission> fileMode)
        throws Exception {
      Files.setPosixFilePermissions(projection, rootMode);
      Files.setPosixFilePermissions(dataDirectory, dataMode);
      Files.setPosixFilePermissions(dataDirectory.resolve("jwks.json"), fileMode);
      Path dataLink = projection.resolve("..data");
      if (Files.exists(dataLink, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(dataLink);
      }
      Files.createSymbolicLink(dataLink, dataDirectory.getFileName());
      if (Files.exists(jwksPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(jwksPath);
      }
      Files.createSymbolicLink(jwksPath, Path.of("..data/jwks.json"));
    }

    private LocalObservation observation(String digest) {
      var identity =
          ReadinessReceiverLocalIdentity.newBuilder()
              .setAccountJwksSourceIdentity(
                  AccountSourceIdentity.newBuilder()
                      .setEnvironmentId(sourceIdentity.environmentId())
                      .setClusterId(sourceIdentity.clusterId())
                      .setClusterIncarnationUid(sourceIdentity.clusterIncarnationUid())
                      .setNamespace(sourceIdentity.namespace())
                      .setNamespaceUid(sourceIdentity.namespaceUid())
                      .setConfigMapUid(sourceIdentity.configMapUid())
                      .setBindingRevision(sourceIdentity.bindingRevision())
                      .setApiServerOrigin(sourceIdentity.apiServerOrigin())
                      .setServingCaSha256(sourceIdentity.servingCaSha256()))
              .setAccountJwksTrustBindingRevision(sourceIdentity.bindingRevision())
              .setAccountPublicJwksSha256(digest)
              .build();
      return new LocalObservation(identity, sourceIdentity);
    }

    private GameSessionJwtReadinessProtectedJwksSource source(LocalObservation... observations) {
      var identityProvider = mock(GameSessionJwtReadinessLocalIdentityProvider.class);
      AtomicInteger index = new AtomicInteger();
      when(identityProvider.observe())
          .thenAnswer(
              ignored -> observations[Math.min(index.getAndIncrement(), observations.length - 1)]);
      return new GameSessionJwtReadinessProtectedJwksSource(identityProvider, jwksPath, ownerUid);
    }
  }
}
