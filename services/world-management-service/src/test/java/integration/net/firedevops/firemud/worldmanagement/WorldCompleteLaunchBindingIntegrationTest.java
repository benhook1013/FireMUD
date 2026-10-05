package net.firedevops.firemud.worldmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.client.GameSessionClient;
import net.firedevops.firemud.worldmanagement.client.GrpcGameSessionInitialAdmissionBindProofClient;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository.RegistrationConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@SpringBootTest(
    classes = WorldManagementServiceApplication.class,
    properties = "spring.grpc.server.port=0")
class WorldCompleteLaunchBindingIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID VERSION = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID OTHER_CANONICAL_VERSION =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "world_management_service");
    PostgresBackedServiceTestSupport.registerRedisService(registry, redis);
  }

  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private WorldAuthoredSourceIntakeRepository sourceRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoBean private GrpcServerLifecycle grpcServerLifecycle;

  @MockitoBean(enforceOverride = true)
  private GrpcGameSessionInitialAdmissionBindProofClient bindProofClient;

  @MockitoBean private GameDesignClient gameDesignClient;
  @MockitoBean private GameSessionClient gameSessionClient;
  @MockitoBean private EntityManagementClient entityManagementClient;

  @Test
  void persistsCompletePairReadsItBackAndLeavesUnmappedRetainedRowsUntouched() {
    long retainedLegacyKey =
        7_900_000_000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000L);
    String retainedName = "unmapped-retained-" + UUID.randomUUID();
    Long retainedRegionId =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "INSERT INTO region (tenant_id, name) VALUES (?, ?) RETURNING id",
                    retainedLegacyKey,
                    retainedName),
                "retained region insert returned no row")
            .get(0, Long.class);
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt sourceReceipt = acceptSource(source);
    CompleteLaunchBindingEvidence evidence = evidence(source, controlRequest(), 41L);
    GetRequest request = request(evidence);
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(client.getComplete(any())).thenReturn(evidence);
    WorldCompleteLaunchBindingService service = service(client, transactionManager);

    WorldCompleteLaunchBindingReceipt first = withGameSession(() -> service.bind(request));
    WorldCompleteLaunchBindingReceipt retry =
        withGameSession(() -> service.bind(request(evidence)));

    assertThat(first).isEqualTo(retry);
    assertThat(first.sourceIntakeReceipt()).isEqualTo(sourceReceipt);
    assertThat(first.evidence()).isEqualTo(evidence);
    assertThat(first.operationId()).isNotEqualTo(sourceReceipt.operationId());
    verify(client).getComplete(any());
    Record bindingRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT binding_operation_id, intake_operation_id, intake_request_id, "
                    + "local_tenant_key, source_operation_id, source_evidence_digest, "
                    + "intake_receipt_digest, descriptor_request_digest, descriptor_result_digest, "
                    + "release_attestation_digest, descriptor_json, release_attestation_json "
                    + "FROM world_complete_launch_binding WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ? AND control_plane_request_id = ?",
                NAMESPACE,
                source.canonicalTenantId(),
                evidence.descriptor().controlPlaneRequestId()),
            "complete launch binding row was not persisted");
    assertThat(bindingRow.get("binding_operation_id", UUID.class)).isEqualTo(first.operationId());
    assertThat(bindingRow.get("intake_operation_id", UUID.class))
        .isEqualTo(sourceReceipt.operationId());
    assertThat(bindingRow.get("intake_request_id", UUID.class))
        .isEqualTo(sourceReceipt.intakeRequestId());
    assertThat(bindingRow.get("local_tenant_key", Long.class))
        .isEqualTo(sourceReceipt.localTenantKey());
    assertThat(bindingRow.get("source_operation_id", UUID.class)).isEqualTo(source.operationId());
    assertThat(bindingRow.get("source_evidence_digest", String.class))
        .isEqualTo(source.evidenceDigest());
    assertThat(bindingRow.get("intake_receipt_digest", String.class))
        .isEqualTo(sourceReceipt.receiptDigest());
    assertThat(bindingRow.get("descriptor_request_digest", String.class))
        .isEqualTo(evidence.descriptor().requestDigest());
    assertThat(bindingRow.get("descriptor_result_digest", String.class))
        .isEqualTo(evidence.descriptor().resultDigest());
    assertThat(bindingRow.get("release_attestation_digest", String.class))
        .isEqualTo(evidence.releaseAttestation().evidenceDigest());
    assertThat(bindingRow.get("descriptor_json", String.class))
        .contains(evidence.descriptor().resultDigest());
    assertThat(bindingRow.get("release_attestation_json", String.class))
        .contains(evidence.releaseAttestation().evidenceDigest());
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT tenant_id, name FROM region WHERE id = ?", retainedRegionId),
                    "retained region readback returned no row")
                .get("tenant_id", Long.class))
        .isEqualTo(retainedLegacyKey);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT name FROM region WHERE id = ?", retainedRegionId),
                    "retained region name readback returned no row")
                .get("name", String.class))
        .isEqualTo(retainedName);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE world_complete_launch_binding SET world_slug = ? "
                        + "WHERE binding_operation_id = ?",
                    "changed-world",
                    first.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "DELETE FROM world_complete_launch_binding WHERE binding_operation_id = ?",
                    first.operationId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> dsl.execute("TRUNCATE world_complete_launch_binding"))
        .isInstanceOf(DataAccessException.class);
    assertThat(countBindings(source.canonicalTenantId())).isEqualTo(1L);
  }

  @Test
  void forwardV26MigrationPreservesUnmappedRetainedWorldRows() throws SQLException {
    String schema =
        "world_complete_launch_binding_" + UUID.randomUUID().toString().replace("-", "");
    long retainedLegacyKey =
        8_700_000_000L + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000L);
    String retainedName = "retained-before-v26-" + UUID.randomUUID();
    try {
      migrateFixture(schema, MigrationVersion.fromVersion("25"));
      try (Connection connection = fixtureConnection(schema);
          PreparedStatement statement =
              connection.prepareStatement("INSERT INTO region (tenant_id, name) VALUES (?, ?)")) {
        statement.setLong(1, retainedLegacyKey);
        statement.setString(2, retainedName);
        statement.executeUpdate();
      }
      migrateFixture(schema, null);

      try (Connection connection = fixtureConnection(schema);
          Statement statement = connection.createStatement()) {
        try (PreparedStatement retainedQuery =
            connection.prepareStatement("SELECT tenant_id, name FROM region WHERE tenant_id = ?")) {
          retainedQuery.setLong(1, retainedLegacyKey);
          try (ResultSet retained = retainedQuery.executeQuery()) {
            assertThat(retained.next()).isTrue();
            assertThat(retained.getLong("tenant_id")).isEqualTo(retainedLegacyKey);
            assertThat(retained.getString("name")).isEqualTo(retainedName);
            assertThat(retained.next()).isFalse();
          }
        }
        try (ResultSet bindingTable =
            statement.executeQuery("SELECT COUNT(*) FROM world_complete_launch_binding")) {
          assertThat(bindingTable.next()).isTrue();
          assertThat(bindingTable.getLong(1)).isZero();
        }
      }
    } finally {
      dropFixtureSchema(schema);
    }
  }

  @Test
  void deniesMissingOrSubstitutedSourceBeforeAnyWorldOwnerWrite() {
    AuthoredWorldSourceEvidence retainedSource = source(UUID.randomUUID(), randomWorld());
    acceptSource(retainedSource);
    AuthoredWorldSourceEvidence substitutedSource = source(UUID.randomUUID(), randomWorld());
    CompleteLaunchBindingEvidence substituted = evidence(substitutedSource, controlRequest(), 43L);
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(client.getComplete(any())).thenReturn(substituted);
    WorldCompleteLaunchBindingService service = service(client, transactionManager);

    assertThatThrownBy(() -> withGameSession(() -> service.bind(request(substituted))))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class);
    assertThat(countBindings(substitutedSource.canonicalTenantId())).isZero();
    verify(client).getComplete(any());
  }

  @Test
  void simultaneousExactRetriesReturnTheSameDurableReceiptAndOwnerOperation() throws Exception {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    CompleteLaunchBindingEvidence evidence = evidence(source, controlRequest(), 50L);
    CountDownLatch bothFetched = new CountDownLatch(2);
    AuthoredWorldLaunchDescriptorClient firstClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    AuthoredWorldLaunchDescriptorClient secondClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    when(firstClient.getComplete(any()))
        .thenAnswer(
            invocation -> {
              awaitFetchBarrier(bothFetched);
              return evidence;
            });
    when(secondClient.getComplete(any()))
        .thenAnswer(
            invocation -> {
              awaitFetchBarrier(bothFetched);
              return evidence;
            });
    WorldCompleteLaunchBindingService firstService = service(firstClient, transactionManager);
    WorldCompleteLaunchBindingService secondService = service(secondClient, transactionManager);
    GetRequest firstRequest = request(evidence);
    GetRequest secondRequest = request(evidence);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<WorldCompleteLaunchBindingReceipt> first =
          executor.submit(() -> withGameSession(() -> firstService.bind(firstRequest)));
      Future<WorldCompleteLaunchBindingReceipt> second =
          executor.submit(() -> withGameSession(() -> secondService.bind(secondRequest)));
      WorldCompleteLaunchBindingReceipt firstReceipt = first.get(20, TimeUnit.SECONDS);
      WorldCompleteLaunchBindingReceipt secondReceipt = second.get(20, TimeUnit.SECONDS);

      assertThat(firstReceipt).isEqualTo(secondReceipt);
      assertThat(firstReceipt.operationId()).isEqualTo(secondReceipt.operationId());
      assertThat(firstReceipt.evidence()).isEqualTo(evidence);
      assertThat(countBindings(source.canonicalTenantId())).isEqualTo(1L);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT binding_operation_id FROM world_complete_launch_binding "
                              + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                              + "AND control_plane_request_id = ?",
                          NAMESPACE,
                          source.canonicalTenantId(),
                          evidence.descriptor().controlPlaneRequestId()),
                      "complete launch binding operation readback returned no row")
                  .get("binding_operation_id", UUID.class))
          .isEqualTo(firstReceipt.operationId());
    }
    verify(firstClient).getComplete(any());
    verify(secondClient).getComplete(any());
  }

  @Test
  void concurrentExactAndChangedClaimsKeepOneImmutableBinding() throws Exception {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    String stableControlRequest = controlRequest();
    CompleteLaunchBindingEvidence original = evidence(source, stableControlRequest, 51L);
    CompleteLaunchBindingEvidence changed = evidence(source, stableControlRequest, 52L);
    CountDownLatch bothFetched = new CountDownLatch(2);
    AuthoredWorldLaunchDescriptorClient originalClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    AuthoredWorldLaunchDescriptorClient changedClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    when(originalClient.getComplete(any()))
        .thenAnswer(
            invocation -> {
              awaitFetchBarrier(bothFetched);
              return original;
            });
    when(changedClient.getComplete(any()))
        .thenAnswer(
            invocation -> {
              awaitFetchBarrier(bothFetched);
              return changed;
            });
    WorldCompleteLaunchBindingService originalService = service(originalClient, transactionManager);
    WorldCompleteLaunchBindingService changedService = service(changedClient, transactionManager);
    GetRequest originalRequest = request(original);
    GetRequest changedRequest = request(changed);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Object> first = executor.submit(() -> attempt(originalService, originalRequest));
      Future<Object> second = executor.submit(() -> attempt(changedService, changedRequest));
      Object firstResult = first.get(20, TimeUnit.SECONDS);
      Object secondResult = second.get(20, TimeUnit.SECONDS);
      WorldCompleteLaunchBindingReceipt receipt;
      if (firstResult instanceof WorldCompleteLaunchBindingReceipt firstReceipt) {
        receipt = firstReceipt;
        assertThat(secondResult).isInstanceOf(RegistrationConflictException.class);
      } else {
        assertThat(firstResult).isInstanceOf(RegistrationConflictException.class);
        assertThat(secondResult).isInstanceOf(WorldCompleteLaunchBindingReceipt.class);
        receipt = (WorldCompleteLaunchBindingReceipt) secondResult;
      }
      assertThat(countBindings(source.canonicalTenantId())).isEqualTo(1L);
      assertThat(
              Objects.requireNonNull(
                      dsl.fetchOne(
                          "SELECT binding_operation_id FROM world_complete_launch_binding "
                              + "WHERE target_namespace = ? AND canonical_tenant_id = ? "
                              + "AND control_plane_request_id = ?",
                          NAMESPACE,
                          source.canonicalTenantId(),
                          stableControlRequest),
                      "complete launch binding operation readback returned no row")
                  .get("binding_operation_id", UUID.class))
          .isEqualTo(receipt.operationId());
    }
  }

  @Test
  void recoversCommittedBindingWhenOwnerCommitAcknowledgmentIsLost() {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    acceptSource(source);
    CompleteLaunchBindingEvidence evidence = evidence(source, controlRequest(), 61L);
    AuthoredWorldLaunchDescriptorClient client = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(client.getComplete(any())).thenReturn(evidence);
    LostCommitAcknowledgmentTransactionManager manager =
        new LostCommitAcknowledgmentTransactionManager(transactionManager);
    WorldCompleteLaunchBindingService service = service(client, manager);

    WorldCompleteLaunchBindingReceipt recovered =
        withGameSession(() -> service.bind(request(evidence)));

    assertThat(recovered.evidence()).isEqualTo(evidence);
    assertThat(countBindings(source.canonicalTenantId())).isEqualTo(1L);
    verify(client).getComplete(any());
    LostCommitAcknowledgmentTransactionManager retryManager =
        new LostCommitAcknowledgmentTransactionManager(transactionManager);
    AuthoredWorldLaunchDescriptorClient retryClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    WorldCompleteLaunchBindingReceipt retry =
        withGameSession(() -> service(retryClient, retryManager).bind(request(evidence)));
    assertThat(retry.operationId()).isEqualTo(recovered.operationId());
    verify(retryClient, never()).getComplete(any());
  }

  @Test
  void readsCurrentVersionForExactCommittedBindingAndPreservesOriginalReceipt()
      throws SQLException {
    AuthoredWorldSourceEvidence source = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt sourceReceipt = acceptSource(source);
    CompleteLaunchBindingEvidence evidence = evidence(source, controlRequest(), 71L);
    GetRequest bindingRequest = request(evidence);
    AuthoredWorldLaunchDescriptorClient descriptorClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    when(descriptorClient.getComplete(any())).thenReturn(evidence);
    AuthoredWorldVersionStateClient versionStateClient =
        mock(AuthoredWorldVersionStateClient.class);
    UUID firstReadId = UUID.randomUUID();
    UUID retiredReadId = UUID.randomUUID();
    when(versionStateClient.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request currentRequest = invocation.getArgument(0);
              if (currentRequest.readRequestId().equals(firstReadId)) {
                return currentVersionState(
                    currentRequest,
                    source,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    evidence.descriptor().versionStateEpoch());
              }
              if (currentRequest.readRequestId().equals(retiredReadId)) {
                return currentVersionState(
                    currentRequest,
                    source,
                    VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE,
                    evidence.descriptor().versionStateEpoch() + 1);
              }
              throw new IllegalArgumentException("Unexpected synthetic current-read request");
            });
    WorldCompleteLaunchBindingService service =
        service(descriptorClient, versionStateClient, transactionManager);

    WorldCompleteLaunchBindingReceipt original =
        withGameSession(() -> service.bind(bindingRequest));
    Map<String, List<Map<String, Object>>> ownerRowsBeforeRead =
        worldOwnerRows(
            source.canonicalTenantId(),
            sourceReceipt.operationId(),
            sourceReceipt.localTenantKey(),
            evidence.descriptor().controlPlaneRequestId());

    AuthoredWorldVersionStateEvidence eligible =
        withGameSession(() -> service.readCurrentVersionState(bindingRequest, firstReadId));

    assertThat(eligible.versionState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(eligible.versionStateEpoch()).isEqualTo(evidence.descriptor().versionStateEpoch());
    assertThat(eligible.request().versionId()).isEqualTo(evidence.descriptor().versionId());
    assertThat(
            worldOwnerRows(
                source.canonicalTenantId(),
                sourceReceipt.operationId(),
                sourceReceipt.localTenantKey(),
                evidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(ownerRowsBeforeRead);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(bindingRequest, retiredReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("epoch differs");
    assertThat(
            worldOwnerRows(
                source.canonicalTenantId(),
                sourceReceipt.operationId(),
                sourceReceipt.localTenantKey(),
                evidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(ownerRowsBeforeRead);

    WorldCompleteLaunchBindingReceipt retry = withGameSession(() -> service.bind(bindingRequest));
    assertThat(retry).isEqualTo(original);
    assertThat(retry.operationId()).isEqualTo(original.operationId());
    assertThat(retry.sourceIntakeReceipt()).isEqualTo(sourceReceipt);
    assertThat(retry.evidence()).isEqualTo(evidence);
    assertThat(
            worldOwnerRows(
                source.canonicalTenantId(),
                sourceReceipt.operationId(),
                sourceReceipt.localTenantKey(),
                evidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(ownerRowsBeforeRead);
    verify(descriptorClient).getComplete(any());
    verify(versionStateClient, times(2)).read(any());
  }

  @Test
  void deniesMixedCurrentVersionEvidenceWithoutChangingCommittedBinding() throws SQLException {
    AuthoredWorldSourceEvidence boundSource = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt boundSourceReceipt = acceptSource(boundSource);
    CompleteLaunchBindingEvidence bindingEvidence = evidence(boundSource, controlRequest(), 73L);
    GetRequest bindingRequest = request(bindingEvidence);
    AuthoredWorldLaunchDescriptorClient descriptorClient =
        mock(AuthoredWorldLaunchDescriptorClient.class);
    when(descriptorClient.getComplete(any())).thenReturn(bindingEvidence);
    AuthoredWorldVersionStateClient versionStateClient =
        mock(AuthoredWorldVersionStateClient.class);

    AuthoredWorldSourceEvidence otherSource = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt otherSourceReceipt = acceptSource(otherSource);
    AuthoredWorldSourceEvidence missingBindingSource = source(UUID.randomUUID(), randomWorld());
    WorldAuthoredSourceIntakeReceipt missingBindingReceipt = acceptSource(missingBindingSource);
    String otherControlPlaneRequestId = controlRequest();
    UUID mixedReadId = UUID.randomUUID();
    UUID changedSelectorReadId = UUID.randomUUID();
    UUID changedCanonicalVersionReadId = UUID.randomUUID();
    UUID unavailableReadId = UUID.randomUUID();
    UUID missingBindingReadId = UUID.randomUUID();
    AuthoredWorldVersionStateEvidence mixedEvidence =
        currentVersionState(
            otherSource,
            mixedReadId,
            bindingEvidence.descriptor().versionId() + 1,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            bindingEvidence.descriptor().versionStateEpoch());
    AuthoredWorldVersionStateEvidence changedSelectorEvidence =
        currentVersionState(
            boundSource,
            changedSelectorReadId,
            bindingEvidence.descriptor().versionId() + 1,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            bindingEvidence.descriptor().versionStateEpoch());
    AuthoredWorldVersionStateEvidence expectedCanonicalVersionEvidence =
        currentVersionState(
            boundSource,
            changedCanonicalVersionReadId,
            bindingEvidence.descriptor().versionId(),
            bindingEvidence.releaseAttestation().canonicalVersionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            bindingEvidence.descriptor().versionStateEpoch());
    AuthoredWorldVersionStateEvidence changedCanonicalVersionEvidence =
        currentVersionState(
            boundSource,
            changedCanonicalVersionReadId,
            bindingEvidence.descriptor().versionId(),
            OTHER_CANONICAL_VERSION,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            bindingEvidence.descriptor().versionStateEpoch());
    mixedEvidence.requireValid();
    changedSelectorEvidence.requireValid();
    changedCanonicalVersionEvidence.requireValid();
    when(versionStateClient.read(any()))
        .thenAnswer(
            invocation -> {
              AuthoredWorldVersionStateEvidence.Request currentRequest = invocation.getArgument(0);
              if (currentRequest.readRequestId().equals(mixedReadId)) {
                return mixedEvidence;
              }
              if (currentRequest.readRequestId().equals(changedSelectorReadId)) {
                return changedSelectorEvidence;
              }
              if (currentRequest.readRequestId().equals(changedCanonicalVersionReadId)) {
                return changedCanonicalVersionEvidence;
              }
              if (currentRequest.readRequestId().equals(unavailableReadId)) {
                throw new IllegalStateException("Synthetic current-version source is unavailable");
              }
              throw new IllegalArgumentException("Unexpected synthetic current-read request");
            });
    WorldCompleteLaunchBindingService service =
        service(descriptorClient, versionStateClient, transactionManager);
    WorldCompleteLaunchBindingReceipt committed =
        withGameSession(() -> service.bind(bindingRequest));
    GetRequest missingBindingRequest =
        request(evidence(missingBindingSource, controlRequest(), 74L));
    Map<String, List<Map<String, Object>>> committedRowsBeforeRead =
        worldOwnerRows(
            boundSource.canonicalTenantId(),
            boundSourceReceipt.operationId(),
            boundSourceReceipt.localTenantKey(),
            bindingEvidence.descriptor().controlPlaneRequestId());
    Map<String, List<Map<String, Object>>> otherSourceRowsBeforeRead =
        worldOwnerRows(
            otherSource.canonicalTenantId(),
            otherSourceReceipt.operationId(),
            otherSourceReceipt.localTenantKey(),
            otherControlPlaneRequestId);
    Map<String, List<Map<String, Object>>> missingBindingRowsBeforeRead =
        worldOwnerRows(
            missingBindingSource.canonicalTenantId(),
            missingBindingReceipt.operationId(),
            missingBindingReceipt.localTenantKey(),
            missingBindingRequest.expectedRequest().controlPlaneRequestId());
    assertThat(mixedEvidence.request().sourceOperationId()).isEqualTo(otherSource.operationId());
    assertThat(mixedEvidence.request().versionId())
        .isNotEqualTo(bindingEvidence.descriptor().versionId());
    assertThat(changedSelectorEvidence.sourceEvidence()).isEqualTo(boundSource);
    assertThat(changedSelectorEvidence.request().versionId())
        .isNotEqualTo(bindingEvidence.descriptor().versionId());
    assertThat(changedCanonicalVersionEvidence.request())
        .isEqualTo(expectedCanonicalVersionEvidence.request());
    assertThat(changedCanonicalVersionEvidence.sourceEvidence())
        .isEqualTo(expectedCanonicalVersionEvidence.sourceEvidence());
    assertThat(changedCanonicalVersionEvidence.versionState())
        .isEqualTo(expectedCanonicalVersionEvidence.versionState());
    assertThat(changedCanonicalVersionEvidence.versionStateEpoch())
        .isEqualTo(expectedCanonicalVersionEvidence.versionStateEpoch());
    assertThat(changedCanonicalVersionEvidence.canonicalVersionId())
        .isNotEqualTo(bindingEvidence.releaseAttestation().canonicalVersionId());

    assertThatThrownBy(
            () ->
                withGameSession(() -> service.readCurrentVersionState(bindingRequest, mixedReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("exact committed World source");
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);
    assertThat(
            worldOwnerRows(
                otherSource.canonicalTenantId(),
                otherSourceReceipt.operationId(),
                otherSourceReceipt.localTenantKey(),
                otherControlPlaneRequestId))
        .isEqualTo(otherSourceRowsBeforeRead);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(bindingRequest, changedSelectorReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("exact committed World source");
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.readCurrentVersionState(
                            bindingRequest, changedCanonicalVersionReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("canonical version UUID differs");
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);
    assertThat(
            worldOwnerRows(
                otherSource.canonicalTenantId(),
                otherSourceReceipt.operationId(),
                otherSourceReceipt.localTenantKey(),
                otherControlPlaneRequestId))
        .isEqualTo(otherSourceRowsBeforeRead);
    assertThat(
            worldOwnerRows(
                missingBindingSource.canonicalTenantId(),
                missingBindingReceipt.operationId(),
                missingBindingReceipt.localTenantKey(),
                missingBindingRequest.expectedRequest().controlPlaneRequestId()))
        .isEqualTo(missingBindingRowsBeforeRead);
    assertThat(committed.evidence()).isEqualTo(bindingEvidence);
    assertThat(committed.sourceIntakeReceipt()).isEqualTo(boundSourceReceipt);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(bindingRequest, unavailableReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("unavailable or invalid");
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.readCurrentVersionState(
                            missingBindingRequest, missingBindingReadId)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("requires a committed launch binding");
    assertThat(
            worldOwnerRows(
                missingBindingSource.canonicalTenantId(),
                missingBindingReceipt.operationId(),
                missingBindingReceipt.localTenantKey(),
                missingBindingRequest.expectedRequest().controlPlaneRequestId()))
        .isEqualTo(missingBindingRowsBeforeRead);
    assertThat(missingBindingRowsBeforeRead.get("binding")).isEmpty();
    assertThat(committed.operationId())
        .isEqualTo(committedRowsBeforeRead.get("binding").get(0).get("binding_operation_id"));
    assertThat(
            worldOwnerRows(
                otherSource.canonicalTenantId(),
                otherSourceReceipt.operationId(),
                otherSourceReceipt.localTenantKey(),
                otherControlPlaneRequestId))
        .isEqualTo(otherSourceRowsBeforeRead);
    assertThat(
            worldOwnerRows(
                missingBindingSource.canonicalTenantId(),
                missingBindingReceipt.operationId(),
                missingBindingReceipt.localTenantKey(),
                missingBindingRequest.expectedRequest().controlPlaneRequestId()))
        .isEqualTo(missingBindingRowsBeforeRead);
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);
    WorldCompleteLaunchBindingReceipt retried = withGameSession(() -> service.bind(bindingRequest));
    assertThat(retried).isEqualTo(committed);
    assertThat(retried.evidence()).isEqualTo(bindingEvidence);
    assertThat(retried.sourceIntakeReceipt()).isEqualTo(boundSourceReceipt);
    assertThat(
            worldOwnerRows(
                boundSource.canonicalTenantId(),
                boundSourceReceipt.operationId(),
                boundSourceReceipt.localTenantKey(),
                bindingEvidence.descriptor().controlPlaneRequestId()))
        .isEqualTo(committedRowsBeforeRead);
    verify(descriptorClient).getComplete(any());
    verify(versionStateClient, times(4)).read(any());
  }

  private WorldAuthoredSourceIntakeReceipt acceptSource(AuthoredWorldSourceEvidence source) {
    UUID intakeRequestId = UUID.randomUUID();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    return Objects.requireNonNull(
        transaction.execute(
            status -> sourceRepository.acceptFresh(NAMESPACE, intakeRequestId, source)),
        "World source intake returned no receipt");
  }

  private WorldCompleteLaunchBindingService service(
      AuthoredWorldLaunchDescriptorClient client, PlatformTransactionManager manager) {
    return new WorldCompleteLaunchBindingService(
        client,
        new WorldCompleteLaunchBindingRepository(dsl),
        sourceRepository,
        manager,
        NAMESPACE);
  }

  private WorldCompleteLaunchBindingService service(
      AuthoredWorldLaunchDescriptorClient descriptorClient,
      AuthoredWorldVersionStateClient versionStateClient,
      PlatformTransactionManager manager) {
    return new WorldCompleteLaunchBindingService(
        descriptorClient,
        new WorldCompleteLaunchBindingRepository(dsl),
        sourceRepository,
        manager,
        NAMESPACE,
        versionStateClient);
  }

  private Map<String, List<Map<String, Object>>> worldOwnerRows(
      UUID canonicalTenantId,
      UUID sourceIntakeOperationId,
      long localTenantKey,
      String controlPlaneRequestId)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
      rows.put(
          "association",
          readOwnerRows(
              connection,
              "SELECT * FROM world_authored_source_tenant_association "
                  + "WHERE target_namespace = ? AND canonical_tenant_id = ?",
              NAMESPACE,
              canonicalTenantId));
      rows.put(
          "reservation",
          readOwnerRows(
              connection,
              "SELECT * FROM world_authored_source_tenant_key_reservation WHERE tenant_key = ?",
              localTenantKey));
      rows.put(
          "sourceIntake",
          readOwnerRows(
              connection,
              "SELECT * FROM world_authored_source_intake WHERE target_namespace = ? "
                  + "AND canonical_tenant_id = ? AND operation_id = ?",
              NAMESPACE,
              canonicalTenantId,
              sourceIntakeOperationId));
      rows.put(
          "binding",
          readOwnerRows(
              connection,
              "SELECT * FROM world_complete_launch_binding WHERE target_namespace = ? "
                  + "AND canonical_tenant_id = ? AND control_plane_request_id = ?",
              NAMESPACE,
              canonicalTenantId,
              controlPlaneRequestId));
      return Collections.unmodifiableMap(rows);
    }
  }

  private static List<Map<String, Object>> readOwnerRows(
      Connection connection, String query, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(query)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int index = 1; index <= metadata.getColumnCount(); index++) {
            row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
          }
          rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
      }
    }
  }

  private long countBindings(UUID tenant) {
    return Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT COUNT(*) FROM world_complete_launch_binding WHERE target_namespace = ? "
                    + "AND canonical_tenant_id = ?",
                NAMESPACE,
                tenant),
            "complete launch binding count query returned no row")
        .get(0, Long.class);
  }

  private static AuthoredWorldSourceEvidence source(UUID tenant, String worldSlug) {
    UUID registrationRequest = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    String tenantSlug = "tenant-" + tenant.toString().replace("-", "");
    String displayName = "Source " + tenant.toString().substring(0, 8);
    long sourceGameRowId = Math.max(1L, tenant.getLeastSignificantBits() & Long.MAX_VALUE);
    String sourceGameTenantKey = "source-" + tenant.toString().replace("-", "").substring(0, 24);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequest, tenant, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequest,
            sourceOperationId,
            requestDigest,
            tenant,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequest,
        sourceOperationId,
        requestDigest,
        tenant,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static CompleteLaunchBindingEvidence evidence(
      AuthoredWorldSourceEvidence source, String controlPlaneRequestId, long gameTemplateId) {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            controlPlaneRequestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            gameTemplateId,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-" + gameTemplateId,
            42L,
            false,
            null,
            "{}",
            "generation-config-42",
            17L,
            51L,
            "release-bundle-51",
            false,
            null);
    String commit = "commit-launch-binding";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        List.of(
            participant("WORLD_MANAGEMENT", 2, "a", false, commit),
            participant("ENTITY_MANAGEMENT", 2, "b", false, commit),
            participant("GAME_LOGIC", 1, "c", true, commit),
            participant("AUTOMATION_SCRIPTING", 5, "d", false, commit),
            participant("GAME_DESIGN_CONTROL_PLANE", 1, "e", false, commit));
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            source.canonicalTenantId(),
            VERSION,
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:owner:launch-binding",
            commit,
            participants,
            digest('f'),
            1,
            List.of(),
            List.of(),
            List.of("look"),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldVersionStateEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      VersionLifecycleState versionState,
      long versionStateEpoch) {
    return currentVersionState(request, source, VERSION, versionState, versionStateEpoch);
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldVersionStateEvidence.Request request,
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      VersionLifecycleState versionState,
      long versionStateEpoch) {
    return AuthoredWorldVersionStateEvidence.create(
        request, source, canonicalVersionId, versionState, versionStateEpoch);
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldSourceEvidence source,
      UUID readRequestId,
      long versionId,
      VersionLifecycleState versionState,
      long versionStateEpoch) {
    return currentVersionState(
        source, readRequestId, versionId, VERSION, versionState, versionStateEpoch);
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldSourceEvidence source,
      UUID readRequestId,
      long versionId,
      UUID canonicalVersionId,
      VersionLifecycleState versionState,
      long versionStateEpoch) {
    AuthoredWorldVersionStateEvidence.Request request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            source.targetNamespace(),
            readRequestId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            versionId);
    return currentVersionState(
        request, source, canonicalVersionId, versionState, versionStateEpoch);
  }

  private static AuthoredWorldReleaseAttestationEvidence.Participant participant(
      String owner, int schema, String contentHex, boolean hasAbilityDigest, String commit) {
    return new AuthoredWorldReleaseAttestationEvidence.Participant(
        owner,
        "42",
        false,
        null,
        commit,
        contentHex.repeat(64),
        schema,
        hasAbilityDigest,
        hasAbilityDigest ? digest('c') : null);
  }

  private static GetRequest request(CompleteLaunchBindingEvidence evidence) {
    UUID requestId;
    do {
      requestId = UUID.randomUUID();
    } while (requestId.equals(evidence.descriptor().authoredWorldSourceOperationId()));
    return new GetRequest(
        requestId, evidence.descriptor().request(), evidence.descriptor().resultDigest());
  }

  private static String randomWorld() {
    return "world-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
  }

  private static String controlRequest() {
    return "world-control-" + UUID.randomUUID();
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static GrpcPeerIdentity peer() {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
        NAMESPACE,
        "game-session-service");
  }

  private static <T> T withGameSession(java.util.function.Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer());
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void awaitFetchBarrier(CountDownLatch latch) {
    latch.countDown();
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent launch-binding fetch barrier timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent launch-binding fetch was interrupted", exception);
    }
  }

  private static Object attempt(WorldCompleteLaunchBindingService service, GetRequest request) {
    return withGameSession(
        () -> {
          try {
            return service.bind(request);
          } catch (RegistrationConflictException exception) {
            return exception;
          }
        });
  }

  private static void migrateFixture(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private static Connection fixtureConnection(String schema) throws SQLException {
    Connection connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    connection.setSchema(schema);
    return connection;
  }

  private static void dropFixtureSchema(String schema) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static final class LostCommitAcknowledgmentTransactionManager
      implements PlatformTransactionManager {
    private final PlatformTransactionManager delegate;
    private boolean firstCommit = true;

    private LostCommitAcknowledgmentTransactionManager(PlatformTransactionManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public TransactionStatus getTransaction(
        org.springframework.transaction.TransactionDefinition definition) {
      return delegate.getTransaction(definition);
    }

    @Override
    public void commit(TransactionStatus status) {
      delegate.commit(status);
      if (firstCommit) {
        firstCommit = false;
        throw new IllegalStateException("simulated lost owner commit acknowledgment");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      if (!status.isCompleted()) {
        delegate.rollback(status);
      }
    }
  }
}
