package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class GameDesignMigrationVersionTest {
  private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([^_]+)__.*\\.sql$");

  @Test
  void migrationVersionsAreUniqueAndTenantIdentityFollowsBaselineLockBeforeAssociations()
      throws Exception {
    List<MigrationVersion> versions = allMigrationVersions();
    MigrationVersion baselineLock =
        migrationVersion("V30__restricted_entity_digest_baseline_version_lock.sql");
    MigrationVersion canonicalIdentity =
        migrationVersion("V30.1__canonical_game_tenant_identity.sql");
    MigrationVersion associations =
        migrationVersion("V31__approved_legacy_account_tenant_associations.sql");

    assertThat(versions).doesNotHaveDuplicates();
    assertThat(versions).contains(baselineLock, canonicalIdentity, associations);
    assertThat(baselineLock).isEqualTo(MigrationVersion.fromVersion("30"));
    assertThat(baselineLock).isLessThan(canonicalIdentity);
    assertThat(canonicalIdentity).isLessThan(associations);

    // Flyway considers both former V30 filenames the same version, so they cannot coexist.
    assertThat(migrationVersion("V30__canonical_game_tenant_identity.sql")).isEqualTo(baselineLock);
  }

  private static List<MigrationVersion> allMigrationVersions() throws Exception {
    Path migrationDirectory = migrationDirectory();
    try (Stream<Path> files = Files.list(migrationDirectory)) {
      return files
          .map(path -> Objects.requireNonNull(path.getFileName()).toString())
          .filter(name -> VERSIONED_MIGRATION.matcher(name).matches())
          .map(GameDesignMigrationVersionTest::migrationVersion)
          .toList();
    }
  }

  private static MigrationVersion migrationVersion(String filename) {
    Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Not a versioned Flyway migration: " + filename);
    }
    return MigrationVersion.fromVersion(matcher.group(1));
  }

  private static Path migrationDirectory() throws URISyntaxException {
    return Path.of(
            Objects.requireNonNull(
                    GameDesignMigrationVersionTest.class
                        .getClassLoader()
                        .getResource("db/migration/V30.1__canonical_game_tenant_identity.sql"),
                    "Missing canonical Game Design tenant-identity migration resource")
                .toURI())
        .getParent();
  }
}
