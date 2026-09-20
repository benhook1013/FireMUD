package net.firedevops.firemud.automationscripting.service.impl;

import static net.firedevops.firemud.automationscripting.jooq.tables.Scripts.SCRIPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.service.ScriptEventRegistryService;
import net.firedevops.firemud.common.saga.Saga;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class ScriptDefinitionServiceTransactionIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final long TENANT_ID = 1L;
  private static final String VERSION = "patch-definition";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private String schema;
  private DSLContext dsl;
  private TransactionTemplate transactionTemplate;
  private ScriptDefinitionRepository definitionRepository;
  private ScriptEventBindingRepository bindingRepository;
  private ScriptDefinitionServiceImpl service;

  @BeforeEach
  void setUp() {
    schema = "script_definition_service_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource rawDataSource = dataSource(schema);
    Flyway.configure()
        .dataSource(rawDataSource)
        .locations(MIGRATION_LOCATION)
        .schemas(schema)
        .defaultSchema(schema)
        .load()
        .migrate();

    TransactionAwareDataSourceProxy transactionAwareDataSource =
        new TransactionAwareDataSourceProxy(rawDataSource);
    dsl = DSL.using(transactionAwareDataSource, SQLDialect.POSTGRES);
    transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(rawDataSource));
    definitionRepository = new ScriptDefinitionRepository(dsl);
    bindingRepository = new ScriptEventBindingRepository(dsl);

    SagaRunner sagaRunner = mock(SagaRunner.class);
    try {
      doAnswer(
              invocation -> {
                ((Saga) invocation.getArgument(0)).run();
                return null;
              })
          .when(sagaRunner)
          .run(any(Saga.class));
    } catch (SagaException impossible) {
      throw new AssertionError(impossible);
    }
    ScriptDefinitionMapper mapper = Mappers.getMapper(ScriptDefinitionMapper.class);
    ScriptEventRegistryService eventRegistryService = new BuiltInScriptEventRegistryService();
    service =
        new ScriptDefinitionServiceImpl(
            definitionRepository, bindingRepository, mapper, sagaRunner, eventRegistryService);
  }

  @AfterEach
  void dropSchema() {
    if (schema != null) {
      DSL.using(dataSource(null), SQLDialect.POSTGRES)
          .execute("DROP SCHEMA " + schema + " CASCADE");
      schema = null;
    }
  }

  @Test
  void exactRetryRetainsDefinitionIdAndCompleteBindingSet() {
    String name = "retry-" + UUID.randomUUID();
    ScriptDefinitionDto firstRequest =
        request(
            null,
            name,
            "{\"value\":1}",
            List.of(binding("binding-a", "scope-a"), binding("binding-b", "scope-b")));

    ScriptDefinitionDto first = updateInTransaction(firstRequest);
    ScriptDefinitionDto retry =
        updateInTransaction(
            request(
                null,
                name,
                first.definition(),
                List.of(binding("binding-a", "scope-a"), binding("binding-b", "scope-b"))));

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(retry.definition()).isEqualTo(first.definition());
    assertThat(
            definitionRepository
                .findByTenantIdAndScriptVersionAndName(TENANT_ID, VERSION, name)
                .orElseThrow()
                .getId())
        .isEqualTo(first.id());
    assertThat(bindingIds(name)).containsExactly("binding-a", "binding-b");
  }

  @Test
  void concurrentChangedPayloadsRetainOneDefinitionAndMatchingCompleteBindingSet()
      throws Exception {
    String name = "concurrent-" + UUID.randomUUID();
    ScriptDefinitionDto initial =
        updateInTransaction(
            request(null, name, "{\"value\":0}", List.of(binding("binding-base", "scope-base"))));
    ScriptDefinitionDto left =
        request(
            null,
            name,
            "{\"value\":1}",
            List.of(
                binding("binding-left-a", "scope-left-a"),
                binding("binding-left-b", "scope-left-b")));
    ScriptDefinitionDto right =
        request(
            null,
            name,
            "{\"value\":2}",
            List.of(
                binding("binding-right-a", "scope-right-a"),
                binding("binding-right-b", "scope-right-b")));

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<UpdateOutcome> first = executor.submit(() -> updateAfterBarrier(left, ready, start));
      Future<UpdateOutcome> second = executor.submit(() -> updateAfterBarrier(right, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      UpdateOutcome firstOutcome = first.get(30, TimeUnit.SECONDS);
      UpdateOutcome secondOutcome = second.get(30, TimeUnit.SECONDS);
      assertThat(firstOutcome.succeeded()).isTrue();
      assertThat(secondOutcome.succeeded()).isTrue();
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    var retained =
        definitionRepository
            .findByTenantIdAndScriptVersionAndName(TENANT_ID, VERSION, name)
            .orElseThrow();
    assertThat(retained.getId()).isEqualTo(initial.id());
    assertThat(retained.getRowVersion()).isEqualTo(2);
    assertThat(dsl.fetchCount(SCRIPTS)).isEqualTo(1);
    Set<String> expectedBindingIds =
        retained.getDefinition().equals(left.definition())
            ? Set.of("binding-left-a", "binding-left-b")
            : Set.of("binding-right-a", "binding-right-b");
    assertThat(retained.getDefinition()).isIn(left.definition(), right.definition());
    assertThat(bindingIds(name)).containsExactlyInAnyOrderElementsOf(expectedBindingIds);
  }

  @Test
  void bindingWriteFailureRollsBackDefinitionAndBindingsTogether() {
    String name = "rollback-" + UUID.randomUUID();
    ScriptDefinitionDto initialRequest =
        request(
            null,
            name,
            "{\"original\":true}",
            List.of(binding("binding-original", "scope-original")));
    ScriptDefinitionDto initial = updateInTransaction(initialRequest);
    int originalRowVersion =
        definitionRepository.findById(initial.id()).orElseThrow().getRowVersion();

    String tooLongBindingId = "x".repeat(129);
    ScriptDefinitionDto failingRequest =
        request(
            null,
            name,
            "{\"replacement\":true}",
            List.of(binding(tooLongBindingId, "scope-replacement")));

    assertThatThrownBy(() -> updateInTransaction(failingRequest))
        .isInstanceOf(UpdateFailedException.class);

    var retained = definitionRepository.findById(initial.id()).orElseThrow();
    assertThat(retained.getDefinition()).isEqualTo(initial.definition());
    assertThat(retained.getRowVersion()).isEqualTo(originalRowVersion);
    assertThat(bindingIds(name)).containsExactly("binding-original");
  }

  private ScriptDefinitionDto updateInTransaction(ScriptDefinitionDto request) {
    return transactionTemplate.execute(
        status -> {
          try {
            return service.updateScript(request);
          } catch (SagaException exception) {
            throw new UpdateFailedException(exception);
          }
        });
  }

  private UpdateOutcome updateAfterBarrier(
      ScriptDefinitionDto request, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("definition concurrency test did not start");
      }
      updateInTransaction(request);
      return new UpdateOutcome(true);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("definition concurrency test interrupted", exception);
    } catch (RuntimeException exception) {
      return new UpdateOutcome(false);
    }
  }

  private List<String> bindingIds(String scriptName) {
    return bindingRepository
        .findByTenantIdAndScriptPatchVersionAndScriptIdOrderByEventTypeAscEventSchemaVersionAscPriorityAscBindingIdAscIdAsc(
            TENANT_ID, VERSION, scriptName)
        .stream()
        .map(ScriptEventBinding::getBindingId)
        .toList();
  }

  private static ScriptDefinitionDto request(
      Long id, String name, String definition, List<ScriptDefinitionDto.EventBindingDto> bindings) {
    return new ScriptDefinitionDto(id, TENANT_ID, name, VERSION, definition, bindings);
  }

  private static ScriptDefinitionDto.EventBindingDto binding(
      String bindingId, String targetScopeId) {
    return new ScriptDefinitionDto.EventBindingDto(
        "onCommand", "v1", "ACTION_TAG", targetScopeId, 0, "normal", false, bindingId);
  }

  private DriverManagerDataSource dataSource(String schemaName) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    String baseUrl = postgres.getJdbcUrl();
    dataSource.setUrl(
        schemaName == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schemaName);
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    return dataSource;
  }

  private record UpdateOutcome(boolean succeeded) {}

  private static final class UpdateFailedException extends RuntimeException {
    private UpdateFailedException(SagaException cause) {
      super(cause);
    }
  }
}
