package net.firedevops.firemud.loggingadmin;

import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.mockito.Mockito;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Test-fixture-only constructors for isolated reservation mutation proofs. */
public final class StartSessionReservationMutationTestFixtures {
  private static final String MUTATION_CAPABILITY = "MutationCapability";
  private static final String DEFAULT_SCHEMA = "public";

  private StartSessionReservationMutationTestFixtures() {}

  public static TestcontainersScope forRunOwnedPostgres(PostgreSQLContainer<?> container) {
    return forRunOwnedPostgres(container, DEFAULT_SCHEMA);
  }

  public static TestcontainersScope forRunOwnedPostgres(
      PostgreSQLContainer<?> container, String schema) {
    requireRunOwnedPostgres(container, schema);
    String jdbcUrl = container.getJdbcUrl();
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUser(container.getUsername());
    dataSource.setPassword(container.getPassword());
    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    verifyRunOwnedSchema(dsl, container, schema);
    StartSessionPreAuthorizationReservationRepository repository = instantiateRepository(dsl);
    StartSessionPreAuthorizationReservationService service =
        instantiateService(repository, Clock.systemUTC());
    return new TestcontainersScope(repository, service);
  }

  public static StartSessionPreAuthorizationReservationService forMockBackedUnitTest(
      StartSessionPreAuthorizationReservationRepository repository, Clock clock) {
    Objects.requireNonNull(repository, "repository is required");
    Objects.requireNonNull(clock, "clock is required");
    if (!Mockito.mockingDetails(repository).isMock()) {
      throw new IllegalArgumentException("unit-test mutation scope requires a mocked repository");
    }
    return instantiateService(repository, clock);
  }

  private static void requireRunOwnedPostgres(PostgreSQLContainer<?> container, String schema) {
    Objects.requireNonNull(container, "run-owned PostgreSQL container is required");
    if (!container.isRunning()) {
      throw new IllegalArgumentException("run-owned PostgreSQL container must already be running");
    }
    if (schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}")) {
      throw new IllegalArgumentException("run-owned PostgreSQL schema name is invalid");
    }
  }

  private static void verifyRunOwnedSchema(
      DSLContext dsl, PostgreSQLContainer<?> container, String expectedSchema) {
    ConnectionProvider connectionProvider =
        Objects.requireNonNull(
            dsl.configuration().connectionProvider(), "DSLContext connection provider is required");
    try {
      Connection connection =
          Objects.requireNonNull(
              connectionProvider.acquire(), "run-owned PostgreSQL connection is required");
      try {
        String actualUrl = connection.getMetaData().getURL();
        if (!databaseEndpoint(actualUrl).equals(databaseEndpoint(container.getJdbcUrl()))) {
          throw new IllegalArgumentException(
              "mutation scope must use the exact run-owned PostgreSQL container database");
        }
      } finally {
        connectionProvider.release(connection);
      }
      String actualSchema =
          dsl.select(DSL.field("current_schema()", String.class)).fetchOne(0, String.class);
      if (!expectedSchema.equals(actualSchema)) {
        throw new IllegalArgumentException(
            "mutation scope must use the exact run-owned PostgreSQL schema");
      }
    } catch (SQLException exception) {
      throw new IllegalArgumentException(
          "run-owned PostgreSQL mutation scope could not be verified", exception);
    }
  }

  private static String databaseEndpoint(String jdbcUrl) {
    int queryStart = jdbcUrl.indexOf('?');
    return queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
  }

  private static StartSessionPreAuthorizationReservationRepository instantiateRepository(
      DSLContext dsl) {
    Class<?> capabilityType =
        capabilityType(StartSessionPreAuthorizationReservationRepository.class);
    Object capability = instantiateCapability(capabilityType);
    return instantiate(
        StartSessionPreAuthorizationReservationRepository.class,
        new Class<?>[] {DSLContext.class, capabilityType},
        dsl,
        capability);
  }

  private static StartSessionPreAuthorizationReservationService instantiateService(
      StartSessionPreAuthorizationReservationRepository repository, Clock clock) {
    Class<?> capabilityType = capabilityType(StartSessionPreAuthorizationReservationService.class);
    Object capability = instantiateCapability(capabilityType);
    return instantiate(
        StartSessionPreAuthorizationReservationService.class,
        new Class<?>[] {
          StartSessionPreAuthorizationReservationRepository.class, Clock.class, capabilityType
        },
        repository,
        clock,
        capability);
  }

  private static Class<?> capabilityType(Class<?> owner) {
    return Arrays.stream(owner.getDeclaredClasses())
        .filter(candidate -> candidate.getSimpleName().equals(MUTATION_CAPABILITY))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException("private mutation capability is missing from " + owner));
  }

  private static Object instantiateCapability(Class<?> capabilityType) {
    return instantiate(capabilityType, new Class<?>[0]);
  }

  private static <T> T instantiate(Class<T> type, Class<?>[] parameterTypes, Object... arguments) {
    try {
      Constructor<T> constructor = type.getDeclaredConstructor(parameterTypes);
      constructor.setAccessible(true);
      return constructor.newInstance(arguments);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("test-only mutation fixture construction failed", exception);
    }
  }

  public record TestcontainersScope(
      StartSessionPreAuthorizationReservationRepository repository,
      StartSessionPreAuthorizationReservationService service) {
    public TestcontainersScope {
      Objects.requireNonNull(repository, "repository is required");
      Objects.requireNonNull(service, "service is required");
    }
  }
}
