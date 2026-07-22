package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.repository.GameSettingsOverrideRepository;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class SettingsAuthorityServiceIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private SettingsAuthorityService settingsAuthorityService;
  @Autowired private GameSettingsOverrideRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void persistsTenantAndGameInstanceOverridesSeparately() {
    settingsAuthorityService.putDomainOverride(
        "42",
        null,
        ScopedSettingsOverrides.SettingsDomain.PRESENTATION,
        new ScopedSettingsOverrides(
            null,
            null,
            new ScopedSettingsOverrides.PresentationOverride(
                null, ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC, true, null),
            null,
            null));
    settingsAuthorityService.putDomainOverride(
        "42",
        7L,
        ScopedSettingsOverrides.SettingsDomain.MOVEMENT,
        new ScopedSettingsOverrides(
            null, null, null, new ScopedSettingsOverrides.MovementOverride(false), null));
    settingsAuthorityService.putDomainOverride(
        "42",
        null,
        ScopedSettingsOverrides.SettingsDomain.COMMAND_CAPABILITIES,
        new ScopedSettingsOverrides(
            null,
            null,
            null,
            null,
            null,
            null,
            new ScopedSettingsOverrides.CommandCapabilitiesOverride(false, null, null, true)));

    ScopedSettingsSnapshot snapshot = settingsAuthorityService.getScopedOverrides("42", 7L);

    assertThat(snapshot.tenantOverrides().presentation()).isNotNull();
    assertThat(snapshot.tenantOverrides().presentation().defaultColorMode())
        .isEqualTo(ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC);
    assertThat(snapshot.tenantOverrides().presentation().briefEnabledByDefault()).isTrue();
    assertThat(snapshot.gameInstanceOverrides().movement()).isNotNull();
    assertThat(snapshot.gameInstanceOverrides().movement().postMoveLookEnabled()).isFalse();
    assertThat(snapshot.tenantOverrides().commandCapabilities()).isNotNull();
    assertThat(snapshot.tenantOverrides().commandCapabilities().socialEnabled()).isFalse();
    assertThat(snapshot.tenantOverrides().commandCapabilities().commandHistoryEnabled()).isTrue();
  }

  @Test
  void deleteRemovesPersistedDomainOverride() {
    settingsAuthorityService.putDomainOverride(
        "84",
        9L,
        ScopedSettingsOverrides.SettingsDomain.RECONNECTION,
        new ScopedSettingsOverrides(
            new ScopedSettingsOverrides.ReconnectionOverride(
                new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(300_000L, false),
                null),
            null,
            null,
            null,
            null));

    assertThat(
            settingsAuthorityService
                .getScopedOverrides("84", 9L)
                .gameInstanceOverrides()
                .reconnection())
        .isNotNull();

    settingsAuthorityService.deleteDomainOverride(
        "84", 9L, ScopedSettingsOverrides.SettingsDomain.RECONNECTION);

    assertThat(
            settingsAuthorityService
                .getScopedOverrides("84", 9L)
                .gameInstanceOverrides()
                .reconnection())
        .isNull();
  }

  @Test
  void reconnectionScopeLockIsHeldUntilOwningTransactionCompletes() throws Exception {
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    CountDownLatch firstLockAcquired = new CountDownLatch(1);
    CountDownLatch secondTransactionStarted = new CountDownLatch(1);
    CountDownLatch secondLockAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> firstTransaction =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        repository.findReconnectionRowsByTenantIdForUpdate("lock-tenant");
                        firstLockAcquired.countDown();
                        awaitLatch(releaseFirstTransaction);
                      }));

      assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
      Future<?> secondTransaction =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        secondTransactionStarted.countDown();
                        repository.findReconnectionRowsByTenantIdForUpdate("lock-tenant");
                        secondLockAcquired.countDown();
                      }));

      assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(secondLockAcquired.await(250, TimeUnit.MILLISECONDS)).isFalse();
      releaseFirstTransaction.countDown();

      firstTransaction.get(5, TimeUnit.SECONDS);
      secondTransaction.get(5, TimeUnit.SECONDS);
      assertThat(secondLockAcquired.getCount()).isZero();
    } finally {
      releaseFirstTransaction.countDown();
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for transaction coordination", exception);
    }
  }
}
