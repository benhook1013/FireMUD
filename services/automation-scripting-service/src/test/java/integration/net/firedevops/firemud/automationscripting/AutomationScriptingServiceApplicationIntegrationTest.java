package net.firedevops.firemud.automationscripting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.dto.ScriptDefinitionDto;
import net.firedevops.firemud.automationscripting.entity.ScriptDefinition;
import net.firedevops.firemud.automationscripting.entity.ScriptEventBinding;
import net.firedevops.firemud.automationscripting.entity.ScriptEventIngressAudit;
import net.firedevops.firemud.automationscripting.entity.ScriptHandoffEvent;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.mapper.ScriptDefinitionMapper;
import net.firedevops.firemud.automationscripting.repository.ScriptDefinitionRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventBindingRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptEventIngressAuditRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptHandoffEventRepository;
import net.firedevops.firemud.automationscripting.repository.ScriptWorkItemRepository;
import net.firedevops.firemud.automationscripting.service.impl.BuiltInScriptEventRegistryService;
import net.firedevops.firemud.automationscripting.service.impl.ScriptDefinitionServiceImpl;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.test.GatewayTestProperties;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AutomationScriptingServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      GatewayTestProperties.SPRING_GRPC_SERVER_SSL_DISABLED,
      GatewayTestProperties.FIREMUD_GRPC_CERT_CHAIN_PATH,
      GatewayTestProperties.FIREMUD_GRPC_PRIVATE_KEY_PATH,
      GatewayTestProperties.FIREMUD_GRPC_CA_CERT_PATH
    })
@Import(NoGrpcServerTestConfiguration.class)
class AutomationScriptingServiceApplicationIntegrationTest {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final JwtUtil JWT_UTIL =
      new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 3600000L);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "automation_scripting_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @LocalServerPort private int port;

  private final List<ExecutorService> barrierExecutors = new ArrayList<>();

  @AfterEach
  void shutdownBarrierExecutors() {
    barrierExecutors.forEach(ExecutorService::shutdownNow);
    barrierExecutors.clear();
  }

  private ExecutorService barrierExecutor() {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    barrierExecutors.add(executor);
    return executor;
  }

  @Autowired private ScriptWorkItemRepository scriptWorkItemRepository;

  @Autowired private ScriptEventIngressAuditRepository scriptEventIngressAuditRepository;

  @Autowired private ScriptDefinitionRepository scriptDefinitionRepository;

  @Autowired private ScriptEventBindingRepository scriptEventBindingRepository;

  @Autowired private DSLContext dsl;

  @Autowired private SagaRunner sagaRunner;

  @Autowired private ScriptHandoffEventRepository scriptHandoffEventRepository;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void unsupportedFormationRestRoutesAreNotReachable() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/formations"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void unsupportedFormationRestRoutesRejectCrossTenantSelectorsByBeingAbsent() throws Exception {
    String token =
        JWT_UTIL.generateToken(
            "automation-test", Map.of("scopedRoles", Map.of("1", List.of("tenantAdmin"))));
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/formations/7/members?tenantId=2"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void concurrentPostgresTriggerIdentityInsertReturnsOneWinnerAndOneExistingRow() throws Exception {
    String scriptEventId = "event-concurrent-" + UUID.randomUUID();
    ScriptWorkItem first = concurrentIdentityWorkItem(scriptEventId);
    ScriptWorkItem second = concurrentIdentityWorkItem(scriptEventId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = barrierExecutor();
    CompletableFuture<ScriptWorkItemRepository.IdempotentInsertResult> left =
        CompletableFuture.supplyAsync(() -> insertAfterBarrier(first, ready, start), executor);
    CompletableFuture<ScriptWorkItemRepository.IdempotentInsertResult> right =
        CompletableFuture.supplyAsync(() -> insertAfterBarrier(second, ready, start), executor);
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    ScriptWorkItemRepository.IdempotentInsertResult leftResult = left.get(30, TimeUnit.SECONDS);
    ScriptWorkItemRepository.IdempotentInsertResult rightResult = right.get(30, TimeUnit.SECONDS);

    assertThat(
            List.of(leftResult, rightResult).stream()
                .filter(ScriptWorkItemRepository.IdempotentInsertResult::inserted))
        .hasSize(1);
    assertThat(leftResult.workItem().getId()).isEqualTo(rightResult.workItem().getId());
  }

  @Test
  void concurrentPostgresNullableIngressIdentityInsertReturnsOneWinnerAndOneExistingRow()
      throws Exception {
    String scriptEventId = "event-nullable-" + UUID.randomUUID();
    ScriptEventIngressAudit first = concurrentNullableIngressAudit(scriptEventId);
    ScriptEventIngressAudit second = concurrentNullableIngressAudit(scriptEventId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = barrierExecutor();
    CompletableFuture<ScriptEventIngressAuditRepository.IdempotentInsertResult> left =
        CompletableFuture.supplyAsync(
            () -> insertIngressAfterBarrier(first, ready, start), executor);
    CompletableFuture<ScriptEventIngressAuditRepository.IdempotentInsertResult> right =
        CompletableFuture.supplyAsync(
            () -> insertIngressAfterBarrier(second, ready, start), executor);
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    var leftResult = left.get(30, TimeUnit.SECONDS);
    var rightResult = right.get(30, TimeUnit.SECONDS);

    assertThat(List.of(leftResult, rightResult).stream().filter(result -> result.inserted()))
        .hasSize(1);
    assertThat(leftResult.audit().getId()).isEqualTo(rightResult.audit().getId());
  }

  @Test
  void updateScriptRetryAndChangedInputReuseOneStableDefinitionRow() {
    String name = "script-definition-" + UUID.randomUUID();
    ScriptDefinition initial = scriptDefinition(name, "{}");
    ScriptDefinition first = scriptDefinitionRepository.save(initial);

    ScriptDefinition exactRetry = scriptDefinition(name, "{}");
    ScriptDefinition retried = scriptDefinitionRepository.save(exactRetry);
    assertThat(retried.getId()).isEqualTo(first.getId());
    assertThat(first.getRowVersion()).isZero();
    assertThat(retried.getRowVersion()).isEqualTo(first.getRowVersion());

    ScriptDefinition changed = scriptDefinitionRepository.save(scriptDefinition(name, "{\"v\":2}"));
    assertThat(changed.getId()).isEqualTo(first.getId());
    assertThat(changed.getRowVersion()).isEqualTo(first.getRowVersion() + 1);
    assertThat(
            scriptDefinitionRepository
                .findByTenantIdAndScriptVersionAndName(1L, "patch-definition", name)
                .orElseThrow()
                .getDefinition())
        .isEqualTo("{\"v\":2}");
  }

  @Test
  void concurrentPostgresDefinitionRetriesReturnOneStableIdentity() throws Exception {
    String name = "script-definition-concurrent-" + UUID.randomUUID();
    ScriptDefinition left = scriptDefinition(name, "{\"retry\":true}");
    ScriptDefinition right = scriptDefinition(name, "{\"retry\":true}");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = barrierExecutor();
    CompletableFuture<ScriptDefinition> leftResult =
        CompletableFuture.supplyAsync(
            () -> saveDefinitionAfterBarrier(left, ready, start), executor);
    CompletableFuture<ScriptDefinition> rightResult =
        CompletableFuture.supplyAsync(
            () -> saveDefinitionAfterBarrier(right, ready, start), executor);
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    assertThat(leftResult.get(30, TimeUnit.SECONDS).getId())
        .isEqualTo(rightResult.get(30, TimeUnit.SECONDS).getId());
    assertThat(
            scriptDefinitionRepository.findByTenantIdAndScriptVersionAndName(
                1L, "patch-definition", name))
        .isPresent();
  }

  @Test
  void postgresBindingCompensationRestoresFixedIdsAndRejectsConflicts() {
    String name = "script-binding-compensation-" + UUID.randomUUID();
    ScriptDefinition originalDefinition = scriptDefinition(name, "{\"original\":true}");
    ScriptDefinition savedDefinition = scriptDefinitionRepository.save(originalDefinition);
    ScriptEventBinding originalBinding = scriptBinding(name, "original-scope");
    ScriptEventBinding savedBinding = scriptEventBindingRepository.save(originalBinding);
    int originalDefinitionRowVersion = savedDefinition.getRowVersion();
    AtomicReference<List<ScriptEventBinding>> bindingsObservedAfterDelete = new AtomicReference<>();

    ScriptEventBindingRepository failingBindingRepository =
        new ScriptEventBindingRepository(dsl) {
          @Override
          public List<ScriptEventBinding> saveAll(Collection<ScriptEventBinding> entities) {
            bindingsObservedAfterDelete.set(
                findByTenantIdAndScriptPatchVersionAndScriptId(1L, "patch-definition", name));
            throw new IllegalStateException("binding replacement failed");
          }
        };
    ScriptDefinitionServiceImpl service =
        new ScriptDefinitionServiceImpl(
            scriptDefinitionRepository,
            failingBindingRepository,
            Mappers.getMapper(ScriptDefinitionMapper.class),
            sagaRunner,
            new BuiltInScriptEventRegistryService());

    ScriptDefinitionDto update =
        new ScriptDefinitionDto(
            null,
            1L,
            name,
            "patch-definition",
            "{\"replacement\":true}",
            List.of(
                new ScriptDefinitionDto.EventBindingDto(
                    "onCommand", "v1", "ACTION_TAG", "replacement-scope", 0, "normal", false)));

    assertThatThrownBy(() -> service.updateScript(update)).isInstanceOf(SagaException.class);
    assertThat(bindingsObservedAfterDelete.get()).isNotNull().isEmpty();

    List<ScriptEventBinding> restoredBindings =
        scriptEventBindingRepository
            .findByTenantIdAndScriptPatchVersionOrderByEventTypeAscEventSchemaVersionAscPriorityAscScriptIdAsc(
                1L, "patch-definition")
            .stream()
            .filter(binding -> name.equals(binding.getScriptId()))
            .toList();
    assertThat(restoredBindings).hasSize(1);
    ScriptEventBinding restoredBinding = restoredBindings.get(0);
    assertThat(restoredBinding.getId()).isEqualTo(savedBinding.getId());
    assertThat(restoredBinding).usingRecursiveComparison().isEqualTo(savedBinding);

    ScriptDefinition restoredDefinition =
        scriptDefinitionRepository
            .findByTenantIdAndScriptVersionAndName(1L, "patch-definition", name)
            .orElseThrow();
    assertThat(restoredDefinition.getId()).isEqualTo(savedDefinition.getId());
    assertThat(restoredDefinition.getDefinition()).isEqualTo("{\"original\":true}");
    assertThat(restoredDefinition.getRowVersion()).isGreaterThan(originalDefinitionRowVersion);

    ScriptDefinition staleDefinition = scriptDefinition(name, "{\"stale\":true}");
    staleDefinition.setId(restoredDefinition.getId());
    staleDefinition.setRowVersion(originalDefinitionRowVersion);
    assertThatThrownBy(() -> scriptDefinitionRepository.save(staleDefinition))
        .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

    ScriptEventBinding conflictingId = copyBinding(restoredBinding);
    assertThatThrownBy(() -> scriptEventBindingRepository.restoreWithId(conflictingId))
        .isInstanceOf(DataAccessException.class);
    ScriptEventBinding conflictingNaturalKey = copyBinding(restoredBinding);
    conflictingNaturalKey.setId(restoredBinding.getId() + 1_000_000L);
    assertThatThrownBy(() -> scriptEventBindingRepository.restoreWithId(conflictingNaturalKey))
        .isInstanceOf(DataAccessException.class);

    ScriptEventBinding generated = copyBinding(restoredBinding);
    generated.setId(null);
    generated.setTargetScopeId("generated-scope");
    ScriptEventBinding generatedBinding = scriptEventBindingRepository.save(generated);
    assertThat(generatedBinding.getId()).isNotEqualTo(restoredBinding.getId());
  }

  @Test
  void concurrentPostgresHandoffCreationReturnsOneStableLogicalChild() throws Exception {
    ScriptWorkItem workItem = concurrentIdentityWorkItem();
    workItem.setScriptPatchVersion("patch-handoff-" + UUID.randomUUID());
    workItem.setScriptEventId("event-handoff-" + UUID.randomUUID());
    ScriptWorkItem savedWorkItem = scriptWorkItemRepository.save(workItem);
    ScriptHandoffEvent first = handoffEvent(savedWorkItem);
    ScriptHandoffEvent second = handoffEvent(savedWorkItem);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = barrierExecutor();
    CompletableFuture<ScriptHandoffEvent> left =
        CompletableFuture.supplyAsync(() -> saveHandoffAfterBarrier(first, ready, start), executor);
    CompletableFuture<ScriptHandoffEvent> right =
        CompletableFuture.supplyAsync(
            () -> saveHandoffAfterBarrier(second, ready, start), executor);
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    ScriptHandoffEvent leftResult = left.get(30, TimeUnit.SECONDS);
    ScriptHandoffEvent rightResult = right.get(30, TimeUnit.SECONDS);
    assertThat(leftResult.getId()).isEqualTo(rightResult.getId());
    Optional<ScriptHandoffEvent> persisted =
        scriptHandoffEventRepository.findByTenantIdAndWorkItemIdAndCommandOrdinal(
            savedWorkItem.getTenantId(), savedWorkItem.getId(), 0);
    assertThat(persisted).isPresent();
    assertThat(persisted.orElseThrow().getId()).isEqualTo(leftResult.getId());
  }

  private ScriptDefinition saveDefinitionAfterBarrier(
      ScriptDefinition definition, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("definition concurrency test did not start");
      }
      return scriptDefinitionRepository.save(definition);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("definition concurrency test interrupted", e);
    }
  }

  private static ScriptDefinition scriptDefinition(String name, String definition) {
    ScriptDefinition result = new ScriptDefinition();
    result.setTenantId(1L);
    result.setName(name);
    result.setScriptVersion("patch-definition");
    result.setDefinition(definition);
    return result;
  }

  private static ScriptEventBinding scriptBinding(String scriptId, String targetScopeId) {
    ScriptEventBinding binding = new ScriptEventBinding();
    binding.setTenantId(1L);
    binding.setScriptPatchVersion("patch-definition");
    binding.setEventType("onCommand");
    binding.setEventSchemaVersion("v1");
    binding.setScriptId(scriptId);
    binding.setTargetScopeType("ACTION_TAG");
    binding.setTargetScopeId(targetScopeId);
    binding.setPriority(0);
    binding.setPriorityTag("normal");
    binding.setEnabled(true);
    return binding;
  }

  private static ScriptEventBinding copyBinding(ScriptEventBinding source) {
    ScriptEventBinding copy = new ScriptEventBinding();
    copy.setId(source.getId());
    copy.setTenantId(source.getTenantId());
    copy.setScriptPatchVersion(source.getScriptPatchVersion());
    copy.setEventType(source.getEventType());
    copy.setEventSchemaVersion(source.getEventSchemaVersion());
    copy.setScriptId(source.getScriptId());
    copy.setTargetScopeType(source.getTargetScopeType());
    copy.setTargetScopeId(source.getTargetScopeId());
    copy.setPriority(source.getPriority());
    copy.setPriorityTag(source.getPriorityTag());
    copy.setRequiresExclusiveEvent(source.isRequiresExclusiveEvent());
    copy.setEnabled(source.isEnabled());
    copy.setRowVersion(source.getRowVersion());
    return copy;
  }

  private ScriptHandoffEvent saveHandoffAfterBarrier(
      ScriptHandoffEvent event, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("handoff concurrency test did not start");
      }
      return scriptHandoffEventRepository.save(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("handoff concurrency test interrupted", e);
    }
  }

  private static ScriptHandoffEvent handoffEvent(ScriptWorkItem workItem) {
    ScriptHandoffEvent event = new ScriptHandoffEvent();
    event.setEventId("handoff-event-" + workItem.getId());
    event.setTenantId(workItem.getTenantId());
    event.setGameInstanceId(workItem.getGameInstanceId());
    event.setScriptPatchVersion(workItem.getScriptPatchVersion());
    event.setScriptId(workItem.getScriptId());
    event.setWorkItemId(workItem.getId());
    event.setCommandOrdinal(0);
    event.setAutomationDispatchId("dispatch-" + workItem.getId());
    event.setTargetEntityId("entity-1");
    event.setEmittedCommandText("look");
    event.setHandoffOutcome("enqueued");
    event.setHandoffReason("game_session_accepted");
    event.setObservedAt(Instant.now());
    return event;
  }

  private ScriptEventIngressAuditRepository.IdempotentInsertResult insertIngressAfterBarrier(
      ScriptEventIngressAudit audit, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("nullable ingress concurrency test did not start");
      }
      return scriptEventIngressAuditRepository.insertIfAbsentByIdentity(audit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("nullable ingress concurrency test interrupted", e);
    }
  }

  private static ScriptEventIngressAudit concurrentNullableIngressAudit(String scriptEventId) {
    ScriptEventIngressAudit audit = new ScriptEventIngressAudit();
    audit.setTenantId("1");
    audit.setGameInstanceId(null);
    audit.setRegionId(null);
    audit.setRegionEpoch(null);
    audit.setEntityId(null);
    audit.setPlayableStateScope("");
    audit.setWorldSlug("");
    audit.setRealmSlug("");
    audit.setPointerVersion("");
    audit.setEventType("onCommand");
    audit.setEventSchemaVersion("v1");
    audit.setScriptPatchVersion("patch-nullable-ingress");
    audit.setScriptEventId(scriptEventId);
    audit.setSourceService("integration-test");
    audit.setTriggerMode("EVENT");
    audit.setSourceKind("GAMEPLAY_EVENT");
    audit.setSourceState("TRIGGER_ADMITTED");
    audit.setAdmitted(true);
    audit.setAdmissionOutcome("ADMITTED");
    audit.setAdmissionReason("test");
    audit.setCreatedAt(Instant.now());
    return audit;
  }

  @Test
  void concurrentPostgresDeadLetterReplayClaimHasOneWinnerAndOneRecoveryInProgress()
      throws Exception {
    ScriptWorkItem item = concurrentReplayWorkItem();
    ScriptWorkItem saved = scriptWorkItemRepository.save(item);
    int expectedRowVersion = saved.getRowVersion();
    long expectedFailureGeneration = saved.getFailureGeneration();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = barrierExecutor();
    CompletableFuture<Optional<ScriptWorkItem>> left =
        CompletableFuture.supplyAsync(
            () ->
                claimAfterBarrier(
                    saved, expectedRowVersion, expectedFailureGeneration, ready, start),
            executor);
    CompletableFuture<Optional<ScriptWorkItem>> right =
        CompletableFuture.supplyAsync(
            () ->
                claimAfterBarrier(
                    saved, expectedRowVersion, expectedFailureGeneration, ready, start),
            executor);
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    Optional<ScriptWorkItem> leftResult = left.get(30, TimeUnit.SECONDS);
    Optional<ScriptWorkItem> rightResult = right.get(30, TimeUnit.SECONDS);

    assertThat(List.of(leftResult, rightResult).stream().filter(Optional::isPresent)).hasSize(1);
    Optional<ScriptWorkItem> winner = leftResult.isPresent() ? leftResult : rightResult;
    assertThat(winner).isPresent();
    assertThat(winner.orElseThrow().getStatus()).isEqualTo("PENDING_EVALUATION");
  }

  private Optional<ScriptWorkItem> claimAfterBarrier(
      ScriptWorkItem item,
      int expectedRowVersion,
      long expectedFailureGeneration,
      CountDownLatch ready,
      CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("replay concurrency test did not start");
      }
      return scriptWorkItemRepository.claimDeadLetterForReplay(
          item.getId(),
          item.getTenantId(),
          expectedRowVersion,
          expectedFailureGeneration,
          Instant.now());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("replay concurrency test interrupted", e);
    }
  }

  private static ScriptWorkItem concurrentReplayWorkItem() {
    ScriptWorkItem item = concurrentIdentityWorkItem();
    item.setGameInstanceId("concurrent-replay-game");
    item.setScriptEventId("event-replay-" + UUID.randomUUID());
    item.setStatus("DEAD_LETTERED");
    item.setFailureGeneration(7L);
    item.setScriptPinEpoch(1L);
    item.setCreatedAt(Instant.now());
    item.setUpdatedAt(Instant.now());
    return item;
  }

  private ScriptWorkItemRepository.IdempotentInsertResult insertAfterBarrier(
      ScriptWorkItem item, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrency test did not start");
      }
      return scriptWorkItemRepository.insertIfAbsentByTriggerIdentity(item);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrency test interrupted", e);
    }
  }

  private static ScriptWorkItem concurrentIdentityWorkItem() {
    return concurrentIdentityWorkItem("event-concurrent-" + UUID.randomUUID());
  }

  private static ScriptWorkItem concurrentIdentityWorkItem(String scriptEventId) {
    ScriptWorkItem item = new ScriptWorkItem();
    item.setTenantId("1");
    item.setGameInstanceId("concurrent-game");
    item.setRegionId("region-1");
    item.setRegionEpoch(1L);
    item.setEntityId("");
    item.setPlayableStateScope("");
    item.setWorldSlug("");
    item.setRealmSlug("");
    item.setPointerVersion("");
    item.setScriptId("script-concurrent");
    item.setEventType("onCommand");
    item.setEventSchemaVersion("v1");
    item.setScriptPatchVersion("patch-concurrent");
    item.setScriptEventId(scriptEventId);
    item.setDryRun(false);
    item.setSourceService("integration-test");
    item.setTriggerMode("EVENT");
    item.setPayloadJson("{}");
    item.setCreatedAt(Instant.now());
    item.setUpdatedAt(Instant.now());
    return item;
  }
}
