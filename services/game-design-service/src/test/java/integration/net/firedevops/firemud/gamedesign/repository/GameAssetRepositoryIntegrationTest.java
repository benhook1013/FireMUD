package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameAssetRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private DSLContext dsl;
  @Autowired private GameAssetRepository repository;
  @Autowired private VersionRepository versionRepository;

  @Test
  void versionScopedLookupExcludesUnmappedTenantAsset() {
    Version version = new Version();
    version.setTenantId("tenant-1");
    version.setVersionNumber(1);
    version = versionRepository.save(version);

    GameAsset mapped = asset("tenant-1", "mapped.png");
    GameAsset unmapped = asset("tenant-1", "unmapped.png");
    mapped = repository.save(mapped);
    repository.save(unmapped);

    Table<?> versionAsset = DSL.table(DSL.name("version_asset"));
    Field<String> tenantId = DSL.field(DSL.name("tenant_id"), String.class);
    Field<Long> versionId = DSL.field(DSL.name("version_id"), Long.class);
    Field<Long> assetId = DSL.field(DSL.name("asset_id"), Long.class);
    dsl.insertInto(versionAsset)
        .set(tenantId, "tenant-1")
        .set(versionId, version.getId())
        .set(assetId, mapped.getId())
        .execute();

    List<GameAsset> selected =
        repository.findByTenantIdAndVersionId("tenant-1", version.getId());

    assertThat(selected).extracting(GameAsset::getFileName).containsExactly("mapped.png");
    assertThat(selected).extracting(GameAsset::getFileName).doesNotContain("unmapped.png");
  }

  private GameAsset asset(String tenantId, String fileName) {
    GameAsset asset = new GameAsset();
    asset.setTenantId(tenantId);
    asset.setFileName(fileName);
    asset.setContentType("image/png");
    asset.setData(fileName.getBytes(StandardCharsets.UTF_8));
    return asset;
  }
}
