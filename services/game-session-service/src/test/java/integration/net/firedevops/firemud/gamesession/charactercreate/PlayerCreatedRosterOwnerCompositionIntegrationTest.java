package net.firedevops.firemud.gamesession.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.charactercreate.ControlledPlayerCreatedOwnerStoreFixture;
import net.firedevops.firemud.entitymanagement.charactercreate.EntityPlayerCreatedRosterSourceClient;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionPolicy;
import net.firedevops.firemud.entitymanagement.service.CanonicalGameplayRosterRetentionService;
import net.firedevops.firemud.entitymanagement.testing.ControlledPlayerCreatedRosterSourceFixture;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EntityCharacterCreateOutcome;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterRecoveryClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferencePolicy;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterSelectionService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayRosterOwnerReadGrpcAdapter;
import net.firedevops.firemud.gamesession.testing.ControlledPlayerCreatedOriginalOwnerFixture;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CharacterCreatePublicSourceServiceGrpc;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import net.firedevops.firemud.test.TestContainerImages;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real owner stores and authenticated transports with explicitly controlled upstream authority.
 * This does not establish genuine Account ingress, production PKI, registration or player
 * admission, nor production latency qualification.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class PlayerCreatedRosterOwnerCompositionIntegrationTest {
  private static final String NAMESPACE = "test";
  private static final Duration RETENTION = Duration.ofSeconds(20);
  private static final String LIST_RPC =
      CanonicalGameplayRosterServiceGrpc.SERVICE_NAME + "/ListPlayerCreatedRoster";
  private static final String SELECTED_RPC =
      CanonicalGameplayRosterServiceGrpc.SERVICE_NAME + "/ReadSelectedPlayerCreatedCharacter";
  private static final String SOURCE_RPC =
      CharacterCreatePublicSourceServiceGrpc.SERVICE_NAME + "/ReadPlayerCreatedRosterSource";
  private static final String ORIGINAL_RPC =
      CanonicalGameplayRosterOwnerReadServiceGrpc.SERVICE_NAME
          + "/GetCanonicalGameplayRosterReference";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @TempDir Path directory;

  @Test
  void actualCreatedTerminalCrossesAuthenticatedOwnersAndReplaysTheImmutableOriginal()
      throws Exception {
    try (var owners = new Owners(directory)) {
      UUID account = UUID.randomUUID();
      UUID creation = UUID.randomUUID();
      String displayName =
          new net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection
                  .AuthoredCore(
                  owners
                      .gs
                      .wireFixture()
                      .authored()
                      .projections()
                      .getFirst()
                      .core()
                      .canonicalJson())
              .displayName();
      var terminal = owners.entity.createActor(account, creation, displayName);
      assertThat(terminal.getOutcome())
          .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
      var source =
          owners
              .gs
              .sourceReader()
              .resolvePlayerCreatedRosterSource(owners.gs.wireFixture().request());
      var context = owners.context(account);
      owners.roster.reserveSelectionInput(source, context, null, null, null);
      int sourceReadsBeforeList = owners.gs.controlledWorldLifecycleReadCount();
      int sourceRpcBeforeList = owners.rpcCount(SOURCE_RPC);
      int originalRpcBeforeList = owners.rpcCount(ORIGINAL_RPC);
      long listStarted = System.nanoTime();
      var menu = owners.roster.listPlayerCreatedRoster(source, context);
      long listMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - listStarted);
      assertThat(listMillis).isLessThan(TimeUnit.SECONDS.toMillis(20));
      // The reserved original is requalified locally, on Entity's original-owner read,
      // and on Entity's independent current-source read.
      assertThat(owners.gs.controlledWorldLifecycleReadCount() - sourceReadsBeforeList)
          .isEqualTo(3);
      assertThat(owners.rpcCount(SOURCE_RPC) - sourceRpcBeforeList).isEqualTo(1);
      assertThat(owners.rpcCount(ORIGINAL_RPC) - originalRpcBeforeList).isEqualTo(1);
      owners.requireDeadline(LIST_RPC, 20);
      owners.requireDeadline(SOURCE_RPC, 5);
      owners.requireDeadline(ORIGINAL_RPC, 10);
      assertThat(menu.actors()).hasSize(1);
      assertThat(menu.actors().getFirst().getDisplayName()).isEqualTo(displayName);
      assertThat(menu.actors().getFirst().getCharacterUuid()).isEqualTo(terminal.getCharacterId());
      var selectedContext = context.toBuilder().setCharacterId(terminal.getCharacterId()).build();
      int sourceReadsBeforeSelected = owners.gs.controlledWorldLifecycleReadCount();
      int sourceRpcBeforeSelected = owners.rpcCount(SOURCE_RPC);
      int originalRpcBeforeSelected = owners.rpcCount(ORIGINAL_RPC);
      long selectedStarted = System.nanoTime();
      var selected = owners.roster.readSelectedPlayerCreatedCharacter(menu, selectedContext);
      long selectedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - selectedStarted);
      assertThat(selectedMillis).isLessThan(TimeUnit.SECONDS.toMillis(20));
      // Selection repeats the Entity current-source read; the completed original replays.
      assertThat(owners.gs.controlledWorldLifecycleReadCount() - sourceReadsBeforeSelected)
          .isEqualTo(1);
      assertThat(owners.rpcCount(SOURCE_RPC) - sourceRpcBeforeSelected).isEqualTo(1);
      assertThat(owners.rpcCount(ORIGINAL_RPC) - originalRpcBeforeSelected).isEqualTo(1);
      owners.requireDeadline(SELECTED_RPC, 20);
      owners.requireDeadline(SOURCE_RPC, 5);
      owners.requireDeadline(ORIGINAL_RPC, 10);
      assertThat(selected.creationOperationUuid()).isEqualTo(creation);
      assertThat(selected.terminal()).isEqualTo(terminal);
      owners.roster.completeSelection(source, context, SelectionOutcome.SELECTED);

      var retained = owners.gs.referenceOwner().read(menu.originalSelection());
      assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(retained))
          .isEqualTo(menu.response());
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  retained))
          .isEqualTo(selected.response());
      var originalBytes = retained.toByteArray();
      // Actual current source is still the issued target: retirement must refuse.
      assertThat(owners.gs.referenceOwner().retireTarget(menu.originalSelection())).isFalse();
      var replay = owners.roster.listPlayerCreatedRoster(source, context);
      assertThat(replay.response()).isEqualTo(menu.response());
      assertThat(
              owners.roster.readSelectedPlayerCreatedCharacter(replay, selectedContext).response())
          .isEqualTo(selected.response());
      assertThat(owners.gs.referenceOwner().read(menu.originalSelection()).toByteArray())
          .containsExactly(originalBytes);

      // A caller-selected actor absent from the actual frozen menu cannot become a receipt.
      assertThatThrownBy(
              () ->
                  owners.roster.readSelectedPlayerCreatedCharacter(
                      replay,
                      context.toBuilder().setCharacterId(UUID.randomUUID().toString()).build()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(owners.gs.referenceOwner().read(menu.originalSelection()).toByteArray())
          .containsExactly(originalBytes);
      var foreignContext =
          context.toBuilder()
              .setRequestId(UUID.randomUUID().toString())
              .setRealmId(UUID.randomUUID().toString())
              .build();
      assertThatThrownBy(() -> owners.roster.listPlayerCreatedRoster(source, foreignContext))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(owners.gs.retainedOriginalReferenceCount()).isEqualTo(1);
      assertThat(
              owners.entityDsl.fetchCount(DSL.table("entity_canonical_roster_original_selections")))
          .isEqualTo(1);
    }
  }

  @Test
  void completeOneActorSelectionMeasuresActualCreatedOwnerEntrypoint() throws Exception {
    try (var owners = new Owners(directory)) {
      UUID account = UUID.randomUUID();
      UUID creation = UUID.randomUUID();
      String displayName =
          new net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection
                  .AuthoredCore(
                  owners
                      .gs
                      .wireFixture()
                      .authored()
                      .projections()
                      .getFirst()
                      .core()
                      .canonicalJson())
              .displayName();
      boolean maximum = Boolean.getBoolean("firemud.player-created.maximum-known-fields");
      var terminal = owners.entity.createActor(account, creation, displayName, maximum);
      var preseeded = mock(CanonicalGameplayRosterClient.class);
      var selection =
          new CanonicalGameplayRosterSelectionService(
              owners.gs.routeReader(),
              owners.gs.publishedRouteReader(),
              preseeded,
              owners.gs.sourceReader(),
              owners.roster);
      var context = owners.context(account);
      int sourceReadsBefore = owners.gs.controlledWorldLifecycleReadCount();
      int sourceRpcsBefore = owners.rpcCount(SOURCE_RPC);
      int originalRpcsBefore = owners.rpcCount(ORIGINAL_RPC);
      int listRpcsBefore = owners.rpcCount(LIST_RPC);
      int selectedRpcsBefore = owners.rpcCount(SELECTED_RPC);
      long started = System.nanoTime();
      var result = selection.select(context, null, null);
      long elapsedNanos = System.nanoTime() - started;
      assertThat(result)
          .isInstanceOf(
              CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference.class);
      var selected =
          (CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference) result;
      assertThat(selected.ordinal()).isEqualTo(1);
      assertThat(selected.displayName()).isEqualTo(displayName);
      assertThat(selected.ownerReference().selectedCharacterUuid())
          .isEqualTo(UUID.fromString(terminal.getCharacterId()));
      assertThat(selected.ownerReference().creationOperationUuid()).isEqualTo(creation);
      assertThat(selected.ownerReference().terminal()).isEqualTo(terminal);
      var originalRequest = selected.ownerReference().roster().originalSelection();
      var retained = owners.gs.referenceOwner().read(originalRequest);
      assertThat(retained.getTerminalOutcome()).isEqualTo("SELECTED");
      assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(retained))
          .isEqualTo(selected.ownerReference().roster().response());
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  retained))
          .isEqualTo(selected.ownerReference().response());
      byte[] originalBytes = retained.toByteArray();
      // Initial source, reserve, list recheck, original-owner read, Entity list source,
      // post-list freshness, Entity selected source, and post-selected freshness are independent.
      int fullSourceReads = owners.gs.controlledWorldLifecycleReadCount() - sourceReadsBefore;
      assertThat(fullSourceReads).isEqualTo(8);
      assertThat(owners.rpcCount(SOURCE_RPC) - sourceRpcsBefore).isEqualTo(2);
      assertThat(owners.rpcCount(ORIGINAL_RPC) - originalRpcsBefore).isEqualTo(2);
      assertThat(owners.rpcCount(LIST_RPC) - listRpcsBefore).isEqualTo(1);
      assertThat(owners.rpcCount(SELECTED_RPC) - selectedRpcsBefore).isEqualTo(1);
      assertThat(elapsedNanos).isPositive();
      System.out.printf(
          "Created full selection: elapsedSeconds=%.3f completeSourceReads=%d sourceRpc=%d originalRpc=%d listRpc=%d selectedRpc=%d%n",
          elapsedNanos / 1_000_000_000.0,
          fullSourceReads,
          owners.rpcCount(SOURCE_RPC) - sourceRpcsBefore,
          owners.rpcCount(ORIGINAL_RPC) - originalRpcsBefore,
          owners.rpcCount(LIST_RPC) - listRpcsBefore,
          owners.rpcCount(SELECTED_RPC) - selectedRpcsBefore);
      verifyNoInteractions(preseeded);

      var replay = selection.select(context, null, null);
      assertThat(replay)
          .isInstanceOf(
              CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference.class);
      var replayed =
          (CanonicalGameplayRosterSelectionService.SelectedPlayerCreatedOwnerReference) replay;
      assertThat(replayed.ownerReference().terminal()).isEqualTo(terminal);
      assertThat(owners.gs.referenceOwner().read(originalRequest).toByteArray())
          .containsExactly(originalBytes);
      verifyNoInteractions(preseeded);

      // Current production source still names the issued target, so retirement first refuses.
      assertThat(owners.gs.referenceOwner().retireTarget(originalRequest)).isFalse();
      Awaitility.await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(
              () ->
                  assertThat(
                          owners
                              .gsDsl
                              .fetchSingle(
                                  "SELECT s.terminal_at + f.minimum_retention_millis * interval '1 millisecond' <= clock_timestamp() FROM game_session_canonical_roster_selection s JOIN game_session_canonical_roster_target_fences f USING (target_fence_uuid) WHERE s.operation_uuid = ?",
                                  UUID.fromString(originalRequest.getOperationUuid()))
                              .get(0, Boolean.class))
                      .isTrue());
      // Only the later successor reader is controlled. The original/fence/transfer SQL is real.
      assertThat(owners.retireWithControlledSuccessor(originalRequest)).isTrue();
      var retired = owners.gs.referenceOwner().read(originalRequest);
      CanonicalGameplayRosterReferenceEvidence.requireRetirement(retired);
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  retired))
          .isEqualTo(selected.ownerReference().response());

      var entityTransactions =
          new TransactionTemplate(new DataSourceTransactionManager(owners.entityData));
      var retention =
          new CanonicalGameplayRosterRetentionService(
              owners.entity.retentionRepository(),
              owners.originalClient,
              entityTransactions,
              new CanonicalGameplayRosterRetentionPolicy(
                  RETENTION, RETENTION, RETENTION, Duration.ofSeconds(1), 20),
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
      retention.sweep(); // Authenticated retired-original read and local observation.
      assertThat(
              owners
                  .entityDsl
                  .fetchSingle(
                      "SELECT owner_retirement_proof FROM entity_canonical_roster_retention_lifecycle WHERE snapshot_uuid = ?",
                      selected.ownerReference().roster().snapshotUuid())
                  .get(0, byte[].class))
          .containsExactly(retired.toByteArray());
      // The bounded service cursor may require another page pass before preparing this snapshot.
      List<CanonicalGameplayRosterRetentionRepository.RetirementTransfer> transfers = List.of();
      for (int pass = 0; pass < 5; pass++) {
        retention.sweep();
        transfers =
            entityTransactions.execute(
                ignored -> owners.entity.retentionRepository().retirementTransfers(null, 20));
        if (transfers.size() == 1 && "ACKNOWLEDGED".equals(transfers.getFirst().state())) break;
      }
      assertThat(transfers).hasSize(1);
      var transfer = transfers.getFirst();
      assertThat(transfer.valid()).isTrue();
      assertThat(transfer.state()).isEqualTo("ACKNOWLEDGED");
      var acknowledgementRequest = transfer.acknowledgementRequest();
      assertThat(acknowledgementRequest.getOriginalSelection()).isEqualTo(originalRequest);
      assertThat(acknowledgementRequest.getObservedRetirement()).isEqualTo(retired);
      assertThat(acknowledgementRequest.getSnapshotUuid())
          .isEqualTo(selected.ownerReference().roster().snapshotUuid().toString());
      assertThat(acknowledgementRequest.getSnapshotDigest())
          .isEqualTo(selected.ownerReference().roster().snapshotDigest());
      var admission =
          owners.entityDsl.fetchSingle(
              "SELECT admission_uuid FROM entity_canonical_roster_admissions WHERE operation_uuid = ?",
              UUID.fromString(originalRequest.getOperationUuid()));
      assertThat(acknowledgementRequest.getAdmissionUuid())
          .isEqualTo(admission.get(0, UUID.class).toString());
      assertThat(acknowledgementRequest.getObservationUuid())
          .isEqualTo(transfer.observationUuid().toString());
      assertThat(transfer.acknowledgement().getRequestDigest())
          .isEqualTo(
              CanonicalGameplayRosterRetirementEvidence.requestDigest(acknowledgementRequest));
      var gsTransfer =
          owners.gsDsl.fetchSingle(
              "SELECT observation_uuid, admission_uuid, snapshot_uuid, snapshot_digest, request_digest, acknowledgement_uuid, entity_principal FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
              UUID.fromString(originalRequest.getOperationUuid()));
      assertThat(gsTransfer.get("observation_uuid", UUID.class))
          .isEqualTo(transfer.observationUuid());
      assertThat(gsTransfer.get("admission_uuid", UUID.class).toString())
          .isEqualTo(acknowledgementRequest.getAdmissionUuid());
      assertThat(gsTransfer.get("snapshot_uuid", UUID.class).toString())
          .isEqualTo(acknowledgementRequest.getSnapshotUuid());
      assertThat(gsTransfer.get("snapshot_digest", String.class))
          .isEqualTo(acknowledgementRequest.getSnapshotDigest());
      assertThat(gsTransfer.get("request_digest", String.class))
          .isEqualTo(transfer.acknowledgement().getRequestDigest());
      assertThat(gsTransfer.get("acknowledgement_uuid", UUID.class).toString())
          .isEqualTo(transfer.acknowledgement().getAcknowledgementUuid());
      assertThat(gsTransfer.get("entity_principal", String.class))
          .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/entity-management-service");
      var confirmationRequest =
          CanonicalGameplayRosterRetirementEvidence.confirmation(
              acknowledgementRequest, transfer.acknowledgement());
      // These trusted-CA peers reach the real GS listener but lack the exact Entity identity.
      // Neither method may mutate the durable transfer before the valid confirmation.
      if (!maximum) {
        for (var deniedClient :
            List.of(owners.wrongRoleOriginalClient, owners.wrongNamespaceOriginalClient)) {
          assertThatThrownBy(
                  () ->
                      deniedClient.acknowledgeCanonicalGameplayRosterRetirement(
                          acknowledgementRequest))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  failure ->
                      assertThat(failure.getStatus().getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
          assertThatThrownBy(
                  () ->
                      deniedClient.confirmCanonicalGameplayRosterRetirementAcknowledgement(
                          confirmationRequest))
              .isInstanceOfSatisfying(
                  StatusRuntimeException.class,
                  failure ->
                      assertThat(failure.getStatus().getCode())
                          .isEqualTo(Status.Code.PERMISSION_DENIED));
          var unchangedTransfer =
              owners.gsDsl.fetchSingle(
                  "SELECT observation_uuid, admission_uuid, snapshot_uuid, snapshot_digest, request_digest, acknowledgement_uuid, entity_principal, confirmed_at FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                  UUID.fromString(originalRequest.getOperationUuid()));
          for (String field :
              List.of(
                  "observation_uuid",
                  "admission_uuid",
                  "snapshot_uuid",
                  "snapshot_digest",
                  "request_digest",
                  "acknowledgement_uuid",
                  "entity_principal")) {
            assertThat(unchangedTransfer.get(field)).isEqualTo(gsTransfer.get(field));
          }
          assertThat(unchangedTransfer.get("confirmed_at")).isNull();
        }
      }
      var confirmation =
          owners.originalClient.confirmCanonicalGameplayRosterRetirementAcknowledgement(
              confirmationRequest);
      assertThat(confirmation)
          .isEqualTo(
              CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmationRequest));
      Boolean recordedConfirmation =
          entityTransactions.execute(
              ignored ->
                  owners
                      .entity
                      .retentionRepository()
                      .recordRetirementConfirmation(transfer, confirmation));
      assertThat(recordedConfirmation).isTrue();
      assertThat(
              owners
                  .entityDsl
                  .fetchSingle(
                      "SELECT transfer_state, confirmation_response FROM entity_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                      UUID.fromString(originalRequest.getOperationUuid()))
                  .get("transfer_state", String.class))
          .isEqualTo("CONFIRMED");
      assertThat(
              owners
                  .gsDsl
                  .fetchSingle(
                      "SELECT confirmed_at FROM game_session_canonical_roster_retirement_transfers WHERE operation_uuid = ?",
                      UUID.fromString(originalRequest.getOperationUuid()))
                  .get(0))
          .isNotNull();
      assertThat(owners.gs.referenceOwner().read(originalRequest)).isEqualTo(retired);
      System.out.printf(
          "Created actual-writer transfer: maximumKnownFields=%s terminalBytes=%d ackBytes=%d confirmationBytes=%d%n",
          maximum,
          terminal.getSerializedSize(),
          acknowledgementRequest.getSerializedSize(),
          confirmationRequest.getSerializedSize());
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "firemud.player-created.maximum-known-fields", matches = "true")
  void maximumKnownOpaqueFieldsCrossTheActualWriterAndBothOwners() throws Exception {
    try (var owners = new Owners(directory)) {
      UUID account = UUID.randomUUID();
      UUID creation = UUID.randomUUID();
      String displayName =
          new net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreProjection
                  .AuthoredCore(
                  owners
                      .gs
                      .wireFixture()
                      .authored()
                      .projections()
                      .getFirst()
                      .core()
                      .canonicalJson())
              .displayName();
      // The Account and World protections retain their exact structured owner response types.
      // Only their legal opaque source/snapshot fields and GS readback reach their bounds.
      var terminal = owners.entity.createActor(account, creation, displayName, true);
      assertThat(terminal.getOutcome())
          .isEqualTo(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED);
      assertThat(terminal.getAccountOriginal().getOriginalSourceReadback().size())
          .isEqualTo(32 * 1024 * 1024);
      var accountProof =
          net.firedevops.firemud.account.v1.ReadOriginalCharacterCreateProtectionResponse.parseFrom(
              terminal.getOriginalAccountProtection());
      assertThat(accountProof.getOriginal()).isEqualTo(terminal.getAccountOriginal());
      assertThat(terminal.getOriginalAccountProtection().size()).isGreaterThan(34 * 1024 * 1024);
      assertThat(accountProof.getOriginalMembershipSnapshot().size()).isEqualTo(1024 * 1024);
      assertThat(accountProof.getOriginalEntitlementSnapshot().size()).isEqualTo(1024 * 1024);
      assertThat(terminal.getOriginalGameSessionProtection().size()).isEqualTo(48 * 1024 * 1024);
      var worldProof =
          net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection
              .parseFrom(terminal.getOriginalWorldProtection());
      assertThat(worldProof.getAccountProtection()).isEqualTo(accountProof);
      assertThat(terminal.getOriginalWorldProtection().size()).isGreaterThan(34 * 1024 * 1024);
      assertThat(terminal.getSerializedSize()).isGreaterThan(140 * 1024 * 1024);
      var source =
          owners
              .gs
              .sourceReader()
              .resolvePlayerCreatedRosterSource(owners.gs.wireFixture().request());
      var context = owners.context(account);
      owners.roster.reserveSelectionInput(source, context, null, null, null);
      long listStarted = System.nanoTime();
      var menu = owners.roster.listPlayerCreatedRoster(source, context);
      long listNanos = System.nanoTime() - listStarted;
      assertThat(menu.actors()).hasSize(1);
      assertThat(menu.actors().getFirst().getCharacterUuid()).isEqualTo(terminal.getCharacterId());
      var selectedContext = context.toBuilder().setCharacterId(terminal.getCharacterId()).build();
      long selectedStarted = System.nanoTime();
      var selected = owners.roster.readSelectedPlayerCreatedCharacter(menu, selectedContext);
      long selectedNanos = System.nanoTime() - selectedStarted;
      System.out.printf(
          "Created actual-writer bounded vector: terminalBytes=%d rosterBytes=%d selectedBytes=%d listApiSeconds=%.3f selectedApiSeconds=%.3f%n",
          terminal.getSerializedSize(),
          menu.response().getSerializedSize(),
          selected.response().getSerializedSize(),
          listNanos / 1_000_000_000.0,
          selectedNanos / 1_000_000_000.0);
      owners.requireDeadline(LIST_RPC, 20);
      owners.requireDeadline(SELECTED_RPC, 20);
      owners.requireDeadline(SOURCE_RPC, 5);
      owners.requireDeadline(ORIGINAL_RPC, 10);
      assertThat(selected.creationOperationUuid()).isEqualTo(creation);
      assertThat(selected.terminal()).isEqualTo(terminal);
      owners.roster.completeSelection(source, context, SelectionOutcome.SELECTED);
      var retained = owners.gs.referenceOwner().read(menu.originalSelection());
      assertThat(CanonicalGameplayRosterReferenceEvidence.playerCreatedReceipt(retained))
          .isEqualTo(menu.response());
      assertThat(
              CanonicalGameplayRosterReferenceEvidence.selectedPlayerCreatedCharacterReceipt(
                  retained))
          .isEqualTo(selected.response());
      assertThat(owners.gs.referenceOwner().retireTarget(menu.originalSelection())).isFalse();
      var replay = owners.roster.listPlayerCreatedRoster(source, context);
      assertThat(replay.response()).isEqualTo(menu.response());
      assertThat(
              owners.roster.readSelectedPlayerCreatedCharacter(replay, selectedContext).response())
          .isEqualTo(selected.response());
      assertThat(owners.gs.referenceOwner().read(menu.originalSelection())).isEqualTo(retained);
    }
  }

  private static final class Owners implements AutoCloseable {
    private final ArrayDeque<AutoCloseable> resources = new ArrayDeque<>();
    private final List<RpcObservation> rpcObservations = new CopyOnWriteArrayList<>();
    private final DriverManagerDataSource gsData;
    private final DriverManagerDataSource entityData;
    private final org.jooq.DSLContext entityDsl;
    private final org.jooq.DSLContext gsDsl;
    private final GameSessionCanonicalGameplayRosterOwnerReadClient originalClient;
    private final GameSessionCanonicalGameplayRosterOwnerReadClient wrongRoleOriginalClient;
    private final GameSessionCanonicalGameplayRosterOwnerReadClient wrongNamespaceOriginalClient;
    private final ControlledPlayerCreatedOriginalOwnerFixture gs;
    private final ControlledPlayerCreatedOwnerStoreFixture entity;
    private final CanonicalPlayerCreatedRosterClient roster;

    private Owners(Path directory) throws Exception {
      gsData = dataSource("created_owner_gs_" + UUID.randomUUID().toString().replace("-", ""));
      entityData =
          dataSource("created_owner_entity_" + UUID.randomUUID().toString().replace("-", ""));
      try {
        var pki =
            LoopbackMtlsTestPki.create(
                Files.createDirectories(directory.resolve("peers")),
                "Created owner composition test CA",
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gs-client", uri("game-session-service"), false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "gs-server", uri("game-session-service"), true),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "entity-client", uri("entity-management-service"), false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "wrong-role-client", uri("world-management-service"), false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "wrong-namespace-client",
                    "spiffe://firemud/ns/other/sa/entity-management-service",
                    false),
                new LoopbackMtlsTestPki.IdentitySpec(
                    "entity-server", uri("entity-management-service"), true));
        var endpoints = new ServiceEndpointsProperties();
        var gsTls = pki.clientProperties(directory, pki.identity("gs-client"));
        var entityTls = pki.clientProperties(directory, pki.identity("entity-client"));
        var recovery =
            own(
                new CanonicalGameplayRosterRecoveryClient(
                    endpoints, gsTls, new GrpcChannelFactory(), NAMESPACE));
        var initial =
            new ControlledPlayerCreatedRosterSourceFixture(
                    Files.createDirectories(directory.resolve("upstream")))
                .wireFixture();
        gs =
            new ControlledPlayerCreatedOriginalOwnerFixture(
                gsData,
                initial,
                recovery,
                Clock.systemUTC(),
                new CanonicalGameplayRosterReferencePolicy(
                    RETENTION, RETENTION, RETENTION, Duration.ofSeconds(1), 20));
        gsDsl = DSL.using(new TransactionAwareDataSourceProxy(gsData), SQLDialect.POSTGRES);
        var beans = new StaticListableBeanFactory();
        beans.addBean("source", gs.publishedRouteReader());
        beans.addBean("reference", gs.referenceOwner());
        var gsServer =
            server(
                pki,
                "gs-server",
                new GameSessionCharacterCreatePublicSourceGrpcService(gs.sourceReader(), NAMESPACE),
                new CanonicalGameplayRosterOwnerReadGrpcAdapter(
                    beans.getBeanProvider(
                        net.firedevops.firemud.gamesession.service
                            .CanonicalPublishedPlayerRouteReadService.class),
                    NAMESPACE,
                    beans.getBeanProvider(
                        net.firedevops.firemud.gamesession.service
                            .CanonicalGameplayRosterReferenceOwner.class)));
        endpoints.setGameSessionService("127.0.0.1:" + gsServer.getPort());
        var sourceClient =
            own(
                new EntityPlayerCreatedRosterSourceClient(
                    endpoints, entityTls, new GrpcChannelFactory(), NAMESPACE));
        sourceClient.init();
        originalClient =
            own(
                new GameSessionCanonicalGameplayRosterOwnerReadClient(
                    endpoints, entityTls, new GrpcChannelFactory(), NAMESPACE));
        originalClient.init();
        wrongRoleOriginalClient =
            own(
                new GameSessionCanonicalGameplayRosterOwnerReadClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("wrong-role-client")),
                    new GrpcChannelFactory(),
                    NAMESPACE));
        wrongRoleOriginalClient.init();
        wrongNamespaceOriginalClient =
            own(
                new GameSessionCanonicalGameplayRosterOwnerReadClient(
                    endpoints,
                    pki.clientProperties(directory, pki.identity("wrong-namespace-client")),
                    new GrpcChannelFactory(),
                    NAMESPACE));
        wrongNamespaceOriginalClient.init();
        var migration =
            Flyway.configure()
                .dataSource(entityData)
                .locations(
                    "filesystem:"
                        + Path.of("../entity-management-service/src/main/resources/db/migration")
                            .toAbsolutePath()
                            .normalize(),
                    "classpath:db/migration/saga")
                .schemas(entityData.getSchema())
                .defaultSchema(entityData.getSchema())
                .placeholders(Map.of("serviceSchema", entityData.getSchema()));
        migration
            .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        migration.load().migrate();
        entityDsl = DSL.using(new TransactionAwareDataSourceProxy(entityData), SQLDialect.POSTGRES);
        entity =
            new ControlledPlayerCreatedOwnerStoreFixture(
                entityDsl,
                new DataSourceTransactionManager(entityData),
                NAMESPACE,
                gs.wireFixture(),
                sourceClient,
                originalClient,
                Clock.systemUTC(),
                new CanonicalGameplayRosterRetentionPolicy(
                    RETENTION, RETENTION, RETENTION, Duration.ofSeconds(1), 20));
        var entityServer =
            server(pki, "entity-server", entity.rosterAdapter(), entity.recoveryAdapter());
        endpoints.setEntityManagementService("127.0.0.1:" + entityServer.getPort());
        recovery.init();
        roster =
            own(
                new CanonicalPlayerCreatedRosterClient(
                    endpoints, gsTls, new GrpcChannelFactory(), NAMESPACE, gs.referenceOwner()));
        roster.init();
      } catch (Exception failure) {
        try {
          close();
        } catch (Exception cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    private boolean retireWithControlledSuccessor(
        net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original) {
      var snapshot = controlledSuccessorSnapshot(original);
      var current = controlledSuccessorRoute(original, snapshot);
      var routes = mock(CanonicalPlayerRouteReadService.class);
      when(routes.readCurrentTarget(current.canonicalTenantId())).thenReturn(current);
      var admissions = mock(CanonicalInitialAdmissionRepository.class);
      when(admissions.readCurrentOpenSnapshotForRealm(
              original.getTargetNamespace(), current.canonicalTenantId(), current.realmId()))
          .thenReturn(snapshot);
      when(admissions.lockExactCurrentOpenSnapshot(snapshot)).thenReturn(snapshot);
      var successorOwner =
          new CanonicalGameplayRosterReferenceOwner(
              gs.referenceRepository(),
              new TransactionTemplate(new DataSourceTransactionManager(gsData)),
              gs.publishedRouteReader(),
              routes,
              admissions,
              mock(CanonicalGameplayRosterRecoveryClient.class),
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
      return successorOwner.retireTarget(original);
    }

    private int rpcCount(String method) {
      return (int) rpcObservations.stream().filter(call -> call.method().equals(method)).count();
    }

    private void requireDeadline(String method, long seconds) {
      assertThat(rpcObservations.stream().filter(call -> call.method().equals(method)).toList())
          .isNotEmpty()
          .allSatisfy(
              call -> {
                assertThat(call.remainingMillis()).isPositive();
                assertThat(call.remainingMillis())
                    .isLessThanOrEqualTo(TimeUnit.SECONDS.toMillis(seconds));
                if (seconds == 20) {
                  // The outer call reaches Entity with room for both nested owner RPCs.
                  assertThat(call.remainingMillis()).isGreaterThan(10_000L);
                }
              });
    }

    private PlayerExecutionContext context(UUID account) {
      var route = gs.routeReader().readCurrentTarget(gs.route().canonicalTenantId());
      return PlayerExecutionContext.newBuilder()
          .setAccountId(account.toString())
          .setTenantId(route.canonicalTenantId().toString())
          .setPlayableStateNamespaceId(route.playableStateNamespaceId().toString())
          .setGameInstanceId(route.canonicalGameInstanceId().toString())
          .setSessionId(UUID.randomUUID().toString())
          .setRealmId(route.realmId().toString())
          .setRequestId(UUID.randomUUID().toString())
          .setPlayableStateScope(route.playableStateScope())
          .build();
    }

    private <T extends AutoCloseable> T own(T resource) {
      resources.push(resource);
      return resource;
    }

    private Server server(LoopbackMtlsTestPki.Pki pki, String alias, BindableService... services)
        throws Exception {
      var identity = pki.identity(alias);
      var bytePolicy =
          switch (alias) {
            case "gs-server" ->
                new net.firedevops.firemud.gamesession.config
                        .GameSessionGrpcRequestByteConfiguration()
                    .gameSessionGrpcRequestBytePolicy();
            case "entity-server" ->
                net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy.ordinary();
            default -> throw new IllegalArgumentException("Unknown controlled owner server role");
          };
      var transportProperties =
          new org.springframework.boot.context.properties.bind.Binder(
                  new org.springframework.boot.context.properties.source
                      .MapConfigurationPropertySource(
                      java.util.Map.of(
                          "spring.grpc.server.max-inbound-message-size",
                          bytePolicy.transportMaximumBytes() + "B")))
              .bind(
                  "spring.grpc.server",
                  org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties.class)
              .get();
      var factory =
          new net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory(
              "127.0.0.1:0",
              List.of(),
              pki.keyManagerFactory(identity),
              pki.trustManagerFactory(),
              io.grpc.TlsServerCredentials.ClientAuth.REQUIRE,
              bytePolicy,
              Math.toIntExact(transportProperties.getMaxInboundMessageSize().toBytes()));
      for (var service : services)
        factory.addService(
            ServerInterceptors.intercept(
                service,
                new net.firedevops.firemud.entitymanagement.security
                    .CanonicalGameplayRosterPeerInterceptor(NAMESPACE),
                new GrpcPeerIdentityInterceptor(),
                new ServerInterceptor() {
                  @Override
                  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                      ServerCall<ReqT, RespT> call,
                      Metadata headers,
                      ServerCallHandler<ReqT, RespT> next) {
                    var deadline = Context.current().getDeadline();
                    rpcObservations.add(
                        new RpcObservation(
                            call.getMethodDescriptor().getFullMethodName(),
                            deadline == null
                                ? -1L
                                : deadline.timeRemaining(TimeUnit.MILLISECONDS)));
                    return next.startCall(call, headers);
                  }
                }));
      var server = factory.createServer().start();
      own(() -> server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS));
      return server;
    }

    @Override
    public void close() throws Exception {
      Exception failure = null;
      while (!resources.isEmpty()) {
        try {
          resources.pop().close();
        } catch (Exception cleanup) {
          if (failure == null) failure = cleanup;
          else failure.addSuppressed(cleanup);
        }
      }
      var admin = DSL.using(dataSource(null), SQLDialect.POSTGRES);
      admin.execute("DROP SCHEMA IF EXISTS \"" + gsData.getSchema() + "\" CASCADE");
      admin.execute("DROP SCHEMA IF EXISTS \"" + entityData.getSchema() + "\" CASCADE");
      if (failure != null) throw failure;
    }
  }

  private static CanonicalInitialAdmissionRepository.CurrentOpenSnapshot
      controlledSuccessorSnapshot(
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest
              original) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(original);
    var proof =
        new net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof(
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome
                .COMMITTED,
            "controlled-successor-owner",
            "1".repeat(64),
            original.getTargetNamespace(),
            UUID.fromString(target.getTenantUuid()),
            target.getWorldSlug(),
            UUID.fromString(target.getRealmUuid()),
            UUID.fromString(target.getPlayableStateNamespaceUuid()),
            "SHARED",
            UUID.fromString(target.getGameInstanceUuid()),
            UUID.fromString(target.getCanonicalVersionUuid()),
            target.getActiveWorldEpoch() + 1L,
            target.getCatalogRevision() + 1L,
            net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind
                .EXPECT_CLOSED,
            target.getPointerVersion(),
            UUID.fromString(original.getOperationUuid()),
            UUID.fromString(original.getSessionUuid()),
            "sha256:" + "2".repeat(64),
            target.getPointerVersion() + 1L,
            1L,
            "sha256:" + "3".repeat(64),
            false,
            Instant.now());
    return new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, "f".repeat(64));
  }

  private static net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget
      controlledSuccessorRoute(
          net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest original,
          CanonicalInitialAdmissionRepository.CurrentOpenSnapshot snapshot) {
    var target = CanonicalGameplayRosterReferenceEvidence.target(original);
    var proof = snapshot.ownerProof();
    return new net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget(
        proof.targetNamespace(),
        "controlled-tenant",
        proof.canonicalTenantId(),
        proof.worldSlug(),
        "Controlled World",
        proof.realmId(),
        target.getRealmSlug(),
        "Controlled Realm",
        1L,
        1L,
        proof.playableStateNamespaceId(),
        proof.playableStateScope(),
        proof.canonicalGameInstanceId(),
        proof.canonicalVersionId(),
        1L,
        proof.expectedCatalogRevision(),
        proof.committedPointerVersion(),
        snapshot.admissionPointerSnapshotDigest(),
        proof.activeLifecycleEpoch(),
        proof.initialAdmissionRequestId(),
        proof.requestDigest(),
        proof.originKind(),
        proof.expectedPriorPointerVersion(),
        proof.holdId(),
        proof.holdFence(),
        proof.holdBindingDigest(),
        proof.auditEventId(),
        proof.proofDigest(),
        proof.outcome(),
        proof.positiveDurableAbort(),
        proof.terminalAt(),
        "PLAYER_CREATED");
  }

  private record RpcObservation(String method, long remainingMillis) {}

  private static DriverManagerDataSource dataSource(String schema) {
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema(schema);
    return data;
  }

  private static String uri(String service) {
    return "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service;
  }
}
