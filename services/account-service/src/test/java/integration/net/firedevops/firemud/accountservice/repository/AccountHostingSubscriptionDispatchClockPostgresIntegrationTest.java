package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture.Fixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL clock/locks; controlled enrollment only, with no provider calls or registration.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountHostingSubscriptionDispatchClockPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @ParameterizedTest
  @ValueSource(ints = {-365, 365})
  void claimAndFirstDispatchUseDatabaseTimeDespiteObservationClockSkew(int skewDays) {
    Fixture f = fixture();
    var owner = owner(f, Duration.ofSeconds(10), Duration.ofHours(1), skewDays);
    Instant before = databaseNow(f);
    var claim =
        Objects.requireNonNull(
                f.transaction()
                    .execute(s -> owner.claim(f.creation().requestId(), 1, UUID.randomUUID())))
            .claim();
    assertThat(claim.claimedAt()).isBetween(before, databaseNow(f));
    assertThat(claim.expiresAt()).isEqualTo(claim.claimedAt().plusSeconds(10));

    var prepared =
        f.transaction().execute(s -> owner.prepareProviderDispatch(claim, UUID.randomUUID()));
    var read =
        Objects.requireNonNull(f.transaction().execute(s -> owner.readProviderDispatch(prepared)));
    assertThat(read.readyForProviderIo()).isTrue();
    assertThat(read.initialCallAllowed()).isTrue();
    Instant recorded =
        Objects.requireNonNull(
                f.dsl().fetchOne("SELECT recorded_at FROM subscription_provider_dispatch_claim"))
            .get("recorded_at", OffsetDateTime.class)
            .toInstant();
    assertThat(recorded).isBetween(claim.claimedAt(), databaseNow(f));
    assertThat(recorded).isBefore(claim.expiresAt());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void backdatedDispatchCannotConsumeFirstPermissionAfterActualDatabaseExpiry(
      boolean absoluteDeadline) {
    Fixture f = fixture();
    var owner =
        owner(
            f,
            absoluteDeadline ? Duration.ofSeconds(5) : Duration.ofSeconds(3),
            absoluteDeadline ? Duration.ofSeconds(5) : Duration.ofHours(1),
            0);
    var claim =
        Objects.requireNonNull(
                f.transaction()
                    .execute(s -> owner.claim(f.creation().requestId(), 1, UUID.randomUUID())))
            .claim();
    assertThat(claim).isNotNull();
    if (absoluteDeadline) {
      assertThat(claim.expiresAt()).isEqualTo(f.creation().createdAt().plusSeconds(5));
    }
    // Retain the same timestamp the old application-clock INSERT could sample while still live.
    // Start this transaction before expiry, then wait on PostgreSQL time. Neither transaction time
    // nor the caller's earlier recorded_at is a valid substitute for the trigger's late clock read.
    Instant sampled = databaseNow(f);
    assertThat(sampled).isBefore(claim.expiresAt());
    assertThatThrownBy(
            () ->
                f.transaction()
                    .executeWithoutResult(
                        s -> {
                          assertThat(databaseNow(f)).isBefore(claim.expiresAt());
                          f.dsl()
                              .execute(
                                  "SELECT pg_sleep_until(?::timestamptz)",
                                  claim.expiresAt().plusMillis(5).atOffset(ZoneOffset.UTC));
                          assertThat(databaseNow(f)).isAfterOrEqualTo(claim.expiresAt());
                          f.dsl()
                              .execute(
                                  "INSERT INTO subscription_provider_dispatch_claim "
                                      + "(creation_request_id, subscription_id, provider_operation_id, dispatch_id, "
                                      + "classification, first_attempt_id, first_claimed_row_version, recorded_at) "
                                      + "VALUES (?, ?, ?, ?, 'FIRST_DISPATCH', ?, ?, ?::timestamptz)",
                                  f.creation().requestId(),
                                  f.creation().subscriptionId(),
                                  f.creation().subscriptionProvisioningRequestId(),
                                  UUID.randomUUID(),
                                  claim.attemptId(),
                                  claim.subscriptionRowVersion(),
                                  sampled.atOffset(ZoneOffset.UTC));
                        }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("Provider dispatch lacks exact live original provisioning fence")
        .satisfies(
            failure -> assertThat(((DataAccessException) failure).sqlState()).isEqualTo("23514"));
    assertThat(f.dsl().fetchCount(org.jooq.impl.DSL.table("subscription_provider_dispatch_claim")))
        .isZero();
    var replay =
        Objects.requireNonNull(
            f.transaction()
                .execute(s -> owner.claim(f.creation().requestId(), 1, claim.attemptId())));
    assertThat(replay.disposition()).isEqualTo(Disposition.ATTEMPT_EXPIRED);
    assertThat(replay.claim()).isNull();
    assertThat(f.transaction().<Disposition>execute(s -> owner.deferUnresolved(claim)))
        .isEqualTo(absoluteDeadline ? Disposition.TERMINAL_FAILURE : Disposition.ATTEMPT_EXPIRED);
  }

  private static AccountHostingSubscriptionLifecycleRepository owner(
      Fixture f, Duration lease, Duration timeout, int skewDays) {
    return new AccountHostingSubscriptionLifecycleRepository(
        f.dsl(),
        f.intents(),
        skewDays == 0
            ? Clock.systemUTC()
            : Clock.fixed(f.creation().createdAt().plus(Duration.ofDays(skewDays)), ZoneOffset.UTC),
        new ReconciliationLimits(4, 3, lease, Duration.ofMillis(1), timeout),
        new AccountHostingSubscriptionLifecyclePolicy(
            Duration.ofSeconds(15),
            new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                Duration.ofSeconds(60), Duration.ofSeconds(120))),
        "prod",
        false);
  }

  private static Instant databaseNow(Fixture f) {
    return Objects.requireNonNull(f.dsl().fetchOne("SELECT clock_timestamp() AS now"))
        .get("now", OffsetDateTime.class)
        .toInstant();
  }

  private static Fixture fixture() {
    String schema = "dispatch_clock_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .load()
        .migrate();
    Fixture f = CanonicalHostingSubscriptionTestFixture.create(source);
    f.transaction().setTimeout(10);
    return f;
  }
}
