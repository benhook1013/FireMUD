package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
    Files.createSymbolicLink(
        fixture.projection.resolve("jwks.json"),
        fixture.projection.relativize(fixture.dataDirectory.resolve("jwks.json")));
    assertThat(Files.readString(fixture.jwksPath, StandardCharsets.US_ASCII)).isEqualTo(JWKS);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    assertUnavailable(source);
  }

  @Test
  void rejectsGroupOrOtherWritableProjectionDirectoriesAndOwnerWritableFile() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    for (PosixFilePermission writePermission :
        Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)) {
      Set<PosixFilePermission> writableDirectory = withPermission(MODE_0755, writePermission);
      fixture.createProjection(writableDirectory, MODE_0755, MODE_0444);
      assertUnavailable(source);

      fixture.createProjection(MODE_0755, writableDirectory, MODE_0444);
      assertUnavailable(source);
    }

    fixture.createProjection(
        MODE_0755, MODE_0755, withPermission(MODE_0444, PosixFilePermission.OWNER_WRITE));
    assertUnavailable(source);
  }

  @Test
  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The fixture verifies the exact normative production alias and rejects other absolute roots; it does not access those filesystem paths.")
  void recognizesOnlyTheFixedProductionVarRunAlias() {
    assertThat(
            GameSessionJwtReadinessProtectedJwksSource.isCanonicalProductionAlias(
                Path.of("/var/run/secrets/firemud/jwks"), Path.of("/run/secrets/firemud/jwks")))
        .isTrue();
    assertThat(
            GameSessionJwtReadinessProtectedJwksSource.isCanonicalProductionAlias(
                Path.of("/tmp/var/run/secrets/firemud/jwks"),
                Path.of("/tmp/run/secrets/firemud/jwks")))
        .isFalse();
    assertThat(
            GameSessionJwtReadinessProtectedJwksSource.isCanonicalProductionAlias(
                Path.of("/var/run/secrets/firemud/jwks"), Path.of("/srv/jwks")))
        .isFalse();
  }

  @Test
  void rejectsCustomRootWithIntermediateSymlinkAlias() throws Exception {
    Path physicalParent = tempDirectory.resolve("physical");
    Files.createDirectory(physicalParent);
    Path aliasParent = tempDirectory.resolve("alias");
    Files.createSymbolicLink(aliasParent, physicalParent.getFileName());
    ProjectionFixture fixture = new ProjectionFixture(JWKS, aliasParent.resolve("jwks"));
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    assertUnavailable(source);
  }

  @Test
  void rejectsProjectionRootThatIsItselfASymlink() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    Path rootAlias = tempDirectory.resolve("root-alias");
    Files.createSymbolicLink(rootAlias, fixture.projection.getFileName());
    var source =
        fixture.sourceAt(
            rootAlias.resolve("jwks.json"),
            fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    assertUnavailable(source);
  }

  @Test
  void rejectsProjectionLeafSymlinkEscape() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    Path escapedFile = tempDirectory.resolve("escaped-jwks.json");
    Files.writeString(escapedFile, JWKS, StandardCharsets.US_ASCII);
    Files.delete(fixture.jwksPath);
    Files.createSymbolicLink(fixture.jwksPath, escapedFile);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

    assertUnavailable(source);
  }

  @Test
  void rejectsAtomicWriterDirectorySymlinkEscape() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    Path escapedDirectory = tempDirectory.resolve("escaped-projection");
    Files.createDirectory(escapedDirectory);
    Files.writeString(escapedDirectory.resolve("jwks.json"), JWKS, StandardCharsets.US_ASCII);
    Files.delete(fixture.dataDirectory.resolve("jwks.json"));
    Files.delete(fixture.dataDirectory);
    Files.createSymbolicLink(fixture.dataDirectory, escapedDirectory);
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

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

  @Test
  void rejectsMissingProjectedJwksFile() throws Exception {
    ProjectionFixture fixture = new ProjectionFixture(JWKS);
    fixture.createProjection(MODE_0755, MODE_0755, MODE_0444);
    Files.delete(fixture.dataDirectory.resolve("jwks.json"));
    var source =
        fixture.source(fixture.observation(sha256(JWKS.getBytes(StandardCharsets.US_ASCII))));

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

  private static Set<PosixFilePermission> withPermission(
      Set<PosixFilePermission> permissions, PosixFilePermission permission) {
    var updated = new java.util.HashSet<>(permissions);
    updated.add(permission);
    return Set.copyOf(updated);
  }

  private final class ProjectionFixture {
    private final Path projection;
    private final Path dataDirectory;
    private final Path jwksPath;
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
      this(bytes, tempDirectory.resolve("jwks"));
    }

    private ProjectionFixture(String bytes, Path projection) throws Exception {
      this.projection = projection;
      this.dataDirectory = projection.resolve("..2026_10_09_00_00_00");
      this.jwksPath = projection.resolve("jwks.json");
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
      if (Files.exists(dataLink, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(dataLink);
      }
      Files.createSymbolicLink(dataLink, dataDirectory.getFileName());
      if (Files.exists(jwksPath, LinkOption.NOFOLLOW_LINKS)) {
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
      return sourceAt(jwksPath, observations);
    }

    private GameSessionJwtReadinessProtectedJwksSource sourceAt(
        Path path, LocalObservation... observations) {
      var identityProvider = mock(GameSessionJwtReadinessLocalIdentityProvider.class);
      AtomicInteger index = new AtomicInteger();
      when(identityProvider.observe())
          .thenAnswer(
              ignored -> observations[Math.min(index.getAndIncrement(), observations.length - 1)]);
      return new GameSessionJwtReadinessProtectedJwksSource(identityProvider, path, ownerUid);
    }
  }
}
