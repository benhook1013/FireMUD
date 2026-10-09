package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Account signer/issuance/Redis/current-actor and PostgreSQL ordering. World APPLIED and
 * synchronized selection bytes below are explicitly test-only upstream stipulations. This does not
 * prove authenticated World transport, publication terminalization or runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountPublicationAuthorizationPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("publication-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  static final GenericContainer<?> replica =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(redis)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "publication-primary",
              "6379");

  @TempDir Path temporary;

  /**
   * Both owner readbacks here are upstream authenticated-owner stipulations, not transport proof.
   */
  @Test
  void exactTwoOwnerSettlementRollsBackAtomicallyThenReplaysAfterLostAcknowledgement()
      throws Exception {
    for (var outcome : GameDesignPublicationTerminalEvidence.Outcome.values()) {
      try (var fixture = fixture()) {
        var issued = fixture.issueCreator();
        var f = issued.sources();
        var proof = proof(f, issued.environment());
        var repository = new AccountPublicationAuthorizationRepository(f.dsl);
        var service =
            new AccountPublicationAuthorizationService(issued.actors(), f.fences, repository);
        var order = service.authorize(issued.compact(), proof.selection(), issued.environment());
        var operation = operation(order, proof.world());
        var terminal = terminal(operation, outcome);
        String phase =
            outcome == GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED
                ? "PUBLISHED"
                : "ABORTED";
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          repository.settle(operation, terminal, phase, terminal.canonicalBytes());
                          throw new IllegalStateException("rollback before owner commit");
                        }))
            .isInstanceOf(IllegalStateException.class);
        assertThat(
                f.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_selected_publication_settlements")))
            .isZero();
        f.tx(
            () -> {
              repository.readHeld(order);
              return null;
            });
        assertThatThrownBy(() -> f.tx(f::changeRoleToAdmin))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("selected-publication");

        byte[] originalBinding = order.canonicalBytes();
        byte[] receipt =
            f.tx(() -> repository.settle(operation, terminal, phase, terminal.canonicalBytes()));
        // A committed response may be lost. Fresh outside transport correlations never enter
        // settlement identity; historical replay does not authenticate a new creator credential.
        UUID firstTransportRead = UUID.randomUUID(), retryTransportRead = UUID.randomUUID();
        assertThat(firstTransportRead).isNotEqualTo(retryTransportRead);
        assertThat(
                f.tx(
                    () -> repository.settle(operation, terminal, phase, terminal.canonicalBytes())))
            .isEqualTo(receipt);
        assertThat(
                f.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_selected_publication_settlements")))
            .isEqualTo(1);
        assertThat(
                f.dsl
                    .fetchSingle(
                        "SELECT binding FROM account_selected_publication_authorizations WHERE operation_id = ?",
                        order.operationId())
                    .get("binding", byte[].class))
            .isEqualTo(originalBinding);
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          repository.readHeld(order);
                          return null;
                        }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already settled");
        var readOwner =
            new AccountPublicationAuthorizationReadService(repository, f.manager, "test");
        assertThatThrownBy(
                () ->
                    asGameDesign(
                        () ->
                            readOwner.requireHeld(
                                net.firedevops.firemud.common.publication
                                    .AccountPublicationAuthorizationReadEvidence.Request.create(
                                    "test", order))))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
        assertThat(f.tx(f::changeRoleToAdmin).getRole()).isEqualTo("admin");
        // Even after the source changes, the original committed issuance and receipt replay.
        assertThat(
                f.tx(
                    () -> repository.settle(operation, terminal, phase, terminal.canonicalBytes())))
            .isEqualTo(receipt);
        for (String mutation :
            List.of(
                "UPDATE account_selected_publication_settlements SET world_outcome = 'ABORTED'",
                "DELETE FROM account_selected_publication_settlements",
                "TRUNCATE account_selected_publication_settlements")) {
          assertThatThrownBy(() -> f.tx(() -> f.dsl.execute(mutation)))
              .isInstanceOf(org.jooq.exception.DataAccessException.class);
        }
      }
    }
  }

  @Test
  void missingAmbiguousSubstitutedAndDisagreeingOwnersKeepOriginalSourcesHeld() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var repository = new AccountPublicationAuthorizationRepository(f.dsl);
      var service =
          new AccountPublicationAuthorizationService(issued.actors(), f.fences, repository);
      var order = service.authorize(issued.compact(), proof.selection(), issued.environment());
      var operation = operation(order, proof.world());
      var noPublication =
          terminal(operation, GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION);
      var published = terminal(operation, GameDesignPublicationTerminalEvidence.Outcome.PUBLISHED);
      var wrongFence =
          new net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding(
              order.operationId(), UUID.randomUUID(), order.input(), order.sources());
      var wrongOperation = operation(wrongFence, proof.world());
      for (Runnable rejected :
          List.<Runnable>of(
              () ->
                  repository.settle(
                      operation, noPublication, "PUBLISHED", noPublication.canonicalBytes()),
              () ->
                  repository.settle(
                      operation, noPublication, "ABORTED", published.canonicalBytes()),
              () ->
                  repository.settle(
                      operation,
                      noPublication,
                      "RECONCILIATION_REQUIRED",
                      noPublication.canonicalBytes()),
              () -> repository.settle(operation, noPublication, "ABORTED", new byte[0]),
              () -> repository.settle(operation, noPublication, "ABORTED", null),
              () ->
                  repository.settle(
                      wrongOperation,
                      terminal(
                          wrongOperation,
                          GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION),
                      "ABORTED",
                      terminal(
                              wrongOperation,
                              GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION)
                          .canonicalBytes()))) {
        assertThatThrownBy(
                () ->
                    f.tx(
                        () -> {
                          rejected.run();
                          return null;
                        }))
            .isInstanceOf(RuntimeException.class);
      }
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });
      assertThat(
              f.dsl.fetchCount(org.jooq.impl.DSL.table("account_selected_publication_settlements")))
          .isZero();
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE accounts SET role = 'admin' WHERE id = ?",
                              f.account.getId())))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);

      byte[] receipt =
          f.tx(
              () ->
                  repository.settle(
                      operation, noPublication, "ABORTED", noPublication.canonicalBytes()));
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          repository.settle(
                              operation, published, "PUBLISHED", published.canonicalBytes())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Changed original");
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT receipt FROM account_selected_publication_settlements WHERE operation_id = ?",
                      order.operationId())
                  .get("receipt", byte[].class))
          .isEqualTo(receipt);
    }
  }

  @Test
  void settlingOneOrderLeavesEveryOtherOriginalSourceParticipationPending() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var repository = new AccountPublicationAuthorizationRepository(f.dsl);
      var service =
          new AccountPublicationAuthorizationService(issued.actors(), f.fences, repository);
      var first = service.authorize(issued.compact(), proof.selection(), issued.environment());
      var secondSelection =
          selection(proof.selection().selectedCommit(), "other order", "another-publication");
      var second = service.authorize(issued.compact(), secondSelection, issued.environment());
      var operation = operation(first, proof.world());
      var terminal =
          terminal(operation, GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION);
      f.tx(() -> repository.settle(operation, terminal, "ABORTED", terminal.canonicalBytes()));
      f.tx(
          () -> {
            repository.readHeld(second);
            return null;
          });
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE accounts SET role = 'admin' WHERE id = ?",
                              f.account.getId())))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("selected-publication");
      var captured =
          f.tx(
              () ->
                  f.authority.capture(f.account.getAccountUuid(), f.tenant, issued.environment()));
      var change =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), captured.sources(), new byte[] {1});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
    }
  }

  static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation,
      GameDesignPublicationTerminalEvidence.Outcome outcome) {
    if (outcome == GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
    var request = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(
                            operation
                                .account()
                                .input()
                                .selection()
                                .target()
                                .gameDesignVersionRowId()),
                        null,
                        request.appliedCommitId(),
                        request.contentDigest(),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "a".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            operation.account().tenantId(),
            request.canonicalVersionId(),
            "release-exact",
            1,
            "v2",
            request.publishWorkflowId(),
            "sha256:" + "c".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of("look"),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(),
        outcome,
        release,
        Math.addExact(request.versionStateEpoch(), 1));
  }

  static GameDesignPublicationOperationBinding operation(
      net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding account,
      WorldPublishedStartLocationEvidence world) {
    return new GameDesignPublicationOperationBinding(
        account, world, IsolatedWorldPublicationInventoryFixtures.stipulated(account, world));
  }

  private static byte[] rawOperation(
      String schema, byte[] accountBytes, byte[] worldBytes, byte[] inventoryBytes, String digest) {
    var out = new java.io.ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, schema);
    DraftAuthorizationFenceBinding.frame(out, accountBytes);
    DraftAuthorizationFenceBinding.frame(out, worldBytes);
    if (schema.equals(GameDesignPublicationOperationBinding.SCHEMA)) {
      DraftAuthorizationFenceBinding.frame(out, inventoryBytes);
      DraftAuthorizationFenceBinding.frame(out, digest);
    }
    return out.toByteArray();
  }

  private static void assertSqlState(Throwable failure, String expectedState) {
    String sqlState = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) {
        sqlState = sqlException.getSQLState();
      }
    }
    assertThat(sqlState).isEqualTo(expectedState);
  }

  @Test
  void directMalformedOrSubstitutedReceiptRowsDoNotRemovePendingProtection() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var repository = new AccountPublicationAuthorizationRepository(f.dsl);
      var order =
          new AccountPublicationAuthorizationService(issued.actors(), f.fences, repository)
              .authorize(issued.compact(), proof.selection(), issued.environment());
      var operation = operation(order, proof.world());
      Integer selectedSourceKeyWidth =
          f.dsl
              .fetchSingle(
                  "SELECT character_maximum_length FROM information_schema.columns"
                      + " WHERE table_schema = current_schema()"
                      + " AND table_name = 'account_selected_publication_sources'"
                      + " AND column_name = 'source_key'")
              .get(0, Integer.class);
      assertThat(selectedSourceKeyWidth).isEqualTo(2048);
      String inventoryGuard =
          f.dsl
              .fetchSingle(
                  "SELECT pg_get_functiondef('require_selected_inventory_operation_v2(bytea)'::regprocedure)")
              .get(0, String.class);
      assertThat(inventoryGuard)
          .contains("coalesce(jsonb_agg(entry ORDER BY entry->>'regionTemplateId'), '[]'::JSONB)")
          .contains("coalesce(jsonb_agg(entry ORDER BY entry->>'bindingTemplateId'), '[]'::JSONB)")
          .contains(
              "OR selector->'request'->'worldAffectedTuples' IS DISTINCT FROM expected_tuples")
          .contains(
              "OR model->'appliedEpochs' IS DISTINCT FROM expected_epochs OR expected_epochs = '[]'::JSONB")
          .contains(
              "Inventory differs from exact original selected Account, APPLIED graph or freeze");
      var terminal =
          terminal(operation, GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION);
      var out = new java.io.ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, "account-selected-publication-settlement/v1");
      DraftAuthorizationFenceBinding.frame(out, order.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, operation.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, "ABORTED");
      DraftAuthorizationFenceBinding.frame(out, terminal.canonicalBytes());
      byte[] receipt = out.toByteArray();
      String insert =
          "INSERT INTO account_selected_publication_settlements"
              + " (operation_id, fence_id, account_binding, publication_operation, game_design_outcome,"
              + " game_design_terminal, world_outcome, world_terminal, receipt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
      List<Object[]> malformed =
          List.of(
              new Object[] {
                order.operationId(),
                UUID.randomUUID(),
                order.canonicalBytes(),
                operation.canonicalBytes(),
                "NO_PUBLICATION",
                terminal.canonicalBytes(),
                "ABORTED",
                terminal.canonicalBytes(),
                receipt
              },
              new Object[] {
                order.operationId(),
                order.fenceId(),
                new byte[] {0},
                operation.canonicalBytes(),
                "NO_PUBLICATION",
                terminal.canonicalBytes(),
                "ABORTED",
                terminal.canonicalBytes(),
                receipt
              },
              new Object[] {
                order.operationId(),
                order.fenceId(),
                order.canonicalBytes(),
                new byte[] {0},
                "NO_PUBLICATION",
                terminal.canonicalBytes(),
                "ABORTED",
                terminal.canonicalBytes(),
                receipt
              },
              new Object[] {
                order.operationId(),
                order.fenceId(),
                order.canonicalBytes(),
                operation.canonicalBytes(),
                "NO_PUBLICATION",
                new byte[] {0},
                "ABORTED",
                new byte[] {0},
                receipt
              },
              new Object[] {
                order.operationId(),
                order.fenceId(),
                order.canonicalBytes(),
                operation.canonicalBytes(),
                "NO_PUBLICATION",
                terminal.canonicalBytes(),
                "PUBLISHED",
                terminal.canonicalBytes(),
                receipt
              },
              new Object[] {
                order.operationId(),
                order.fenceId(),
                order.canonicalBytes(),
                operation.canonicalBytes(),
                "NO_PUBLICATION",
                terminal.canonicalBytes(),
                "ABORTED",
                terminal.canonicalBytes(),
                new byte[] {0}
              });
      for (Object[] values : malformed) {
        assertThatThrownBy(() -> f.tx(() -> f.dsl.execute(insert, values)))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
      }
      String publicInventoryJson =
          new String(
              operation.inventory().canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8);
      String graphDigest = operation.inventory().publicEvidence().sourceModel().graphDigest();
      byte[] substitutedInventory =
          publicInventoryJson
              .replace(
                  "\"graphDigest\":\"" + graphDigest + "\"",
                  "\"graphDigest\":\"sha256:" + "0".repeat(64) + "\"")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] validOperation = operation.canonicalBytes();
      String tenantId = proof.world().request().canonicalTenantId().toString();
      String versionId = proof.world().request().canonicalVersionId().toString();
      String originalRegionTemplateId =
          operation
              .inventory()
              .publicEvidence()
              .sourceModel()
              .regionGeneratorInputs()
              .get(0)
              .regionTemplateId()
              .toString();
      String originalRegionInputs =
          "\"regionGeneratorInputs\":["
              + inventoryRegionGeneratorInput(originalRegionTemplateId)
              + "]";
      String unsortedRegionInputs =
          "["
              + inventoryRegionGeneratorInput("00000000-0000-0000-0000-000000000002")
              + ","
              + inventoryRegionGeneratorInput("00000000-0000-0000-0000-000000000001")
              + "]";
      assertThat(publicInventoryJson)
          .contains("\"family\":\"REGION\",\"rowCount\":1")
          .contains(originalRegionInputs);
      byte[] unsortedRegionInventory =
          publicInventoryJson
              .replace(
                  "\"family\":\"REGION\",\"rowCount\":1", "\"family\":\"REGION\",\"rowCount\":2")
              .replace(originalRegionInputs, "\"regionGeneratorInputs\":" + unsortedRegionInputs)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] unsortedRegionOperation =
          rawOperation(
              GameDesignPublicationOperationBinding.SCHEMA,
              order.canonicalBytes(),
              proof.world().canonicalBytes(),
              unsortedRegionInventory,
              DraftAuthorizationFenceBinding.digest(unsortedRegionInventory));
      Object[] unsortedRegionSettlement = {
        order.operationId(),
        order.fenceId(),
        order.canonicalBytes(),
        unsortedRegionOperation,
        "NO_PUBLICATION",
        terminal.canonicalBytes(),
        "ABORTED",
        terminal.canonicalBytes(),
        receipt
      };
      assertThatThrownBy(() -> f.tx(() -> f.dsl.execute(insert, unsortedRegionSettlement)))
          .satisfies(
              failure ->
                  assertSqlFailure(
                      failure, "23514", "Unsupported or unordered region generator input"));
      String unsortedSpawnInputs =
          "["
              + inventorySpawnBindingInput(
                  "00000000-0000-0000-0000-000000000002",
                  "00000000-0000-0000-0000-000000000003",
                  "00000000-0000-0000-0000-000000000004",
                  tenantId,
                  versionId)
              + ","
              + inventorySpawnBindingInput(
                  "00000000-0000-0000-0000-000000000001",
                  "00000000-0000-0000-0000-000000000005",
                  "00000000-0000-0000-0000-000000000006",
                  tenantId,
                  versionId)
              + "]";
      assertThat(publicInventoryJson)
          .contains("\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":0")
          .contains("\"spawnBindingCount\":0")
          .contains("\"spawnBindingInputs\":[]");
      byte[] unsortedSpawnInventory =
          publicInventoryJson
              .replace(
                  "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":0",
                  "\"family\":\"WORLD_ENTITY_SPAWN_BINDING\",\"rowCount\":2")
              .replace("\"spawnBindingCount\":0", "\"spawnBindingCount\":2")
              .replace("\"spawnBindingInputs\":[]", "\"spawnBindingInputs\":" + unsortedSpawnInputs)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] unsortedSpawnOperation =
          rawOperation(
              GameDesignPublicationOperationBinding.SCHEMA,
              order.canonicalBytes(),
              proof.world().canonicalBytes(),
              unsortedSpawnInventory,
              DraftAuthorizationFenceBinding.digest(unsortedSpawnInventory));
      Object[] unsortedSpawnSettlement = {
        order.operationId(),
        order.fenceId(),
        order.canonicalBytes(),
        unsortedSpawnOperation,
        "NO_PUBLICATION",
        terminal.canonicalBytes(),
        "ABORTED",
        terminal.canonicalBytes(),
        receipt
      };
      assertThatThrownBy(() -> f.tx(() -> f.dsl.execute(insert, unsortedSpawnSettlement)))
          .satisfies(
              failure ->
                  assertSqlFailure(
                      failure, "23514", "Unsupported or unordered scoped spawn input"));
      List<byte[]> invalidOperations =
          List.of(
              rawOperation(
                  GameDesignPublicationOperationBinding.SCHEMA,
                  order.canonicalBytes(),
                  proof.world().canonicalBytes(),
                  new byte[0],
                  DraftAuthorizationFenceBinding.digest(new byte[0])),
              rawOperation(
                  GameDesignPublicationOperationBinding.SCHEMA,
                  order.canonicalBytes(),
                  proof.world().canonicalBytes(),
                  operation.inventory().canonicalBytes(),
                  "sha256:" + "0".repeat(64)),
              rawOperation(
                  GameDesignPublicationOperationBinding.SCHEMA,
                  order.canonicalBytes(),
                  proof.world().canonicalBytes(),
                  substitutedInventory,
                  DraftAuthorizationFenceBinding.digest(substitutedInventory)),
              rawOperation(
                  "game-design-publication-operation/v1",
                  order.canonicalBytes(),
                  proof.world().canonicalBytes(),
                  null,
                  null),
              java.util.Arrays.copyOf(validOperation, validOperation.length + 1));
      for (byte[] invalidOperation : invalidOperations) {
        assertThatThrownBy(
                () ->
                    f.tx(
                        () ->
                            f.dsl.execute(
                                insert,
                                order.operationId(),
                                order.fenceId(),
                                order.canonicalBytes(),
                                invalidOperation,
                                "NO_PUBLICATION",
                                terminal.canonicalBytes(),
                                "ABORTED",
                                terminal.canonicalBytes(),
                                receipt)))
            .satisfies(failure -> assertSqlState(failure, "23514"));
      }
      assertThat(
              f.dsl.fetchCount(org.jooq.impl.DSL.table("account_selected_publication_settlements")))
          .isZero();
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE accounts SET role = 'admin' WHERE id = ?",
                              f.account.getId())))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
  }

  @Test
  void sourceWriterCannotPassUncommittedSettlementAndCanRetryAfterItsCommit() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var repository = new AccountPublicationAuthorizationRepository(f.dsl);
      var order =
          new AccountPublicationAuthorizationService(issued.actors(), f.fences, repository)
              .authorize(issued.compact(), proof.selection(), issued.environment());
      var operation = operation(order, proof.world());
      var terminal =
          terminal(operation, GameDesignPublicationTerminalEvidence.Outcome.NO_PUBLICATION);
      var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
      try {
        f.tx(
            () -> {
              repository.settle(operation, terminal, "ABORTED", terminal.canonicalBytes());
              int settlementPid =
                  f.dsl.fetchSingle("SELECT pg_backend_pid() AS pid").get("pid", Integer.class);
              var writer =
                  executor.submit(
                      () ->
                          f.tx(
                              () -> {
                                int writerPid =
                                    f.dsl
                                        .fetchSingle("SELECT pg_backend_pid() AS pid")
                                        .get("pid", Integer.class);
                                assertThat(writerPid).isNotEqualTo(settlementPid);
                                // The actual source trigger takes the same source locks NOWAIT. A
                                // pending
                                // transaction cannot release its protection to a different writer
                                // connection.
                                return f.changeRoleToAdmin();
                              }));
              assertThatThrownBy(() -> writer.get(10, java.util.concurrent.TimeUnit.SECONDS))
                  .isInstanceOf(java.util.concurrent.ExecutionException.class)
                  .hasCauseInstanceOf(org.jooq.exception.DataAccessException.class);
              return null;
            });
        assertThat(f.tx(f::changeRoleToAdmin).getRole()).isEqualTo("admin");
      } finally {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void authenticatesPreFreezeDistinctOrderReplaysExactlyAndBlocksSourceWritersAndDisclosure()
      throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var service =
          new AccountPublicationAuthorizationService(
              issued.actors(), f.fences, new AccountPublicationAuthorizationRepository(f.dsl));
      var original = service.authorize(issued.compact(), proof.selection(), issued.environment());
      var replay = service.authorize(issued.compact(), proof.selection(), issued.environment());
      assertThat(replay.canonicalBytes()).isEqualTo(original.canonicalBytes());
      assertThat(original.operationId()).isNotEqualTo(proof.original().operationId());
      assertThat(original.fenceId()).isNotEqualTo(proof.original().fenceId());
      assertThat(f.dsl.fetchCount(org.jooq.impl.DSL.table("account_draft_authorization_fences")))
          .isZero();
      assertThat(
              f.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_selected_publication_authorizations")))
          .isEqualTo(1);
      assertThat(
              java.util.Objects.requireNonNull(
                      f.dsl.fetchOne(
                          "SELECT world_evidence FROM account_selected_publication_authorizations"
                              + " WHERE operation_id = ?",
                          original.operationId()),
                      "Expected exact pre-freeze Account order")
                  .get("world_evidence", byte[].class))
          .isNull();
      // The immutable Account order is available before the separate World capture is correlated.
      operation(original, proof.world());
      assertThat(original.sources()).hasSize(8);
      var readOwner =
          new AccountPublicationAuthorizationReadService(
              new AccountPublicationAuthorizationRepository(f.dsl), f.manager, "test");
      var readRequest =
          net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadEvidence
              .Request.create("test", original);
      asGameDesign(() -> readOwner.requireHeld(readRequest));
      var mismatched =
          new net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding(
              original.operationId(), UUID.randomUUID(), original.input(), original.sources());
      var unknown =
          new net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding(
              UUID.randomUUID(), original.fenceId(), original.input(), original.sources());
      for (var rejected : List.of(mismatched, unknown)) {
        assertThatThrownBy(
                () ->
                    asGameDesign(
                        () ->
                            readOwner.requireHeld(
                                net.firedevops.firemud.common.publication
                                    .AccountPublicationAuthorizationReadEvidence.Request.create(
                                    "test", rejected))))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
      }
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.fences.requireDisclosurePreparation(original.sources());
                        return null;
                      }))
          .hasMessageContaining("selected-publication");
      var change =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), original.sources(), new byte[] {1});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
      // An exact retained replay remains possible while a later source change waits.
      assertThat(
              service
                  .authorize(issued.compact(), proof.selection(), issued.environment())
                  .canonicalBytes())
          .isEqualTo(original.canonicalBytes());
      asGameDesign(() -> readOwner.requireHeld(readRequest));
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE accounts SET role = 'admin' WHERE id = ?",
                              f.account.getId())))
          .isInstanceOf(org.jooq.exception.DataAccessException.class)
          .hasMessageContaining("selected-publication");
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE account_selected_publication_authorizations SET fence_id = ?",
                              UUID.randomUUID())))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThatThrownBy(
              () -> f.tx(() -> f.dsl.execute("DELETE FROM account_selected_publication_sources")))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      var changed = selection(proof.selection().selectedCommit(), "changed notes");
      assertThatThrownBy(() -> service.authorize(issued.compact(), changed, issued.environment()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  service.authorize(
                      issued.compact() + "changed", proof.selection(), issued.environment()))
          .isInstanceOf(RuntimeException.class);
      // Original source/issuance identity is immutable even if a privileged SQL caller tries it.
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE account_selected_publication_authorizations SET issuance_fence = issuance_fence + 1")))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThatThrownBy(
              () ->
                  f.tx(
                      () ->
                          f.dsl.execute(
                              "UPDATE account_selected_publication_sources SET source_evidence = ?",
                              new byte[] {9})))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
  }

  @Test
  void earlierWaitingSourceChangeWinsAndDeniedAuthenticationCreatesNoOperation() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var source =
          f.tx(
              () ->
                  f.authority.capture(f.account.getAccountUuid(), f.tenant, issued.environment()));
      var change =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), source.sources(), new byte[] {2});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isTrue();
      var service =
          new AccountPublicationAuthorizationService(
              issued.actors(), f.fences, new AccountPublicationAuthorizationRepository(f.dsl));
      assertThatThrownBy(
              () -> service.authorize(issued.compact(), proof.selection(), issued.environment()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("source change is waiting");
      assertThatThrownBy(
              () ->
                  service.authorize(
                      "unsigned-caller-claim", proof.selection(), issued.environment()))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              f.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_selected_publication_authorizations")))
          .isZero();
      assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isTrue();
    }
  }

  @Test
  void actualSourceWriterBlocksCreatorCaptureThenWinsBeforePublication() throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var service =
          new AccountPublicationAuthorizationService(
              issued.actors(), f.fences, new AccountPublicationAuthorizationRepository(f.dsl));
      var accounts = new net.firedevops.firemud.accountservice.repository.AccountRepository(f.dsl);
      var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
      var attempt =
          new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
      try {
        f.tx(
            () -> {
              int blockerPid =
                  java.util.Objects.requireNonNull(
                          f.dsl.fetchOne("SELECT pg_backend_pid() AS pid"),
                          "Expected PostgreSQL source-writer connection")
                      .get("pid", Integer.class);
              var account =
                  java.util.Objects.requireNonNull(
                      accounts.findByIdForUpdate(f.account.getId()).orElseThrow(),
                      "Expected locked Account source before canonical role mutation");
              assertThat(account.getRole()).isNotEqualTo("player");
              attempt.set(
                  executor.submit(
                      () ->
                          service.authorize(
                              issued.compact(), proof.selection(), issued.environment())));
              long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
              Integer blockedPid = null;
              while (System.nanoTime() < deadline && blockedPid == null) {
                if (attempt.get().isDone()) {
                  try {
                    attempt.get().get();
                  } catch (java.util.concurrent.ExecutionException failure) {
                    throw new AssertionError(
                        "Creator authorization failed before the Account-row wait was observed",
                        failure.getCause());
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "Interrupted while diagnosing completed creator authorization",
                        interrupted);
                  }
                  throw new AssertionError(
                      "Creator authorization succeeded before the source writer committed");
                }
                // Activity snapshots are otherwise frozen for this blocker transaction.
                f.dsl.fetch("SELECT pg_stat_clear_snapshot()");
                var blocked =
                    f.dsl.fetchOne(
                        "SELECT pid FROM pg_stat_activity WHERE datname = current_database()"
                            + " AND ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock' LIMIT 1",
                        blockerPid);
                if (blocked != null) blockedPid = blocked.get("pid", Integer.class);
                else Thread.onSpinWait();
              }
              assertThat(blockedPid)
                  .as("Distinct creator connection physically waits on source-writer Account row")
                  .isNotNull();
              assertThat(blockedPid).isNotEqualTo(blockerPid);
              assertThat(attempt.get().isDone()).isFalse();
              // Actual guarded source write, held until this transaction commits. The old signed
              // credential cannot authorize a publication from the newly changed Account state.
              account.setRole("player");
              assertThat(accounts.save(account).getRole()).isEqualTo("player");
              return null;
            });
        assertThatThrownBy(() -> attempt.get().get(10, java.util.concurrent.TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(RuntimeException.class);
        assertThat(
                f.dsl.fetchCount(
                    org.jooq.impl.DSL.table("account_selected_publication_authorizations")))
            .isZero();
        assertThat(accounts.findById(f.account.getId()).orElseThrow().getRole())
            .isEqualTo("player");
      } finally {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private AccountControlUiOriginalOrderFixture fixture() throws Exception {
    return new AccountControlUiOriginalOrderFixture(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword(),
        redis.getHost(),
        redis.getMappedPort(6379),
        temporary);
  }

  private static void asGameDesign(Runnable action) {
    io.grpc.Context.current()
        .withValue(
            net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY,
            net.firedevops.firemud.common.grpc.GrpcPeerIdentity.parseUri(
                    "spiffe://firemud/ns/test/sa/game-design-service")
                .orElseThrow())
        .run(action);
  }

  record Proof(
      AuthoredDraftPublishSelectionBinding selection,
      WorldPublishedStartLocationEvidence world,
      DraftAuthorizationFenceBinding original) {}

  /** Closed World codec bytes copied in spirit from the existing isolated selector fixtures. */
  static Proof proof(
      AccountControlUiOwnerSourcesFixture f,
      net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService
              .CapturedEnvironmentBoundary
          environment)
      throws Exception {
    UUID version = UUID.randomUUID(), request = UUID.randomUUID(), commit = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            f.tenant,
            version,
            17,
            "test-private-tenant",
            11,
            "test-private-tenant",
            "NEW_GAME_ROW");
    List<String> families = List.of("REGION", "ZONE", "ROOM");
    List<UUID> templates = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    List<DraftCommitBinding.RevisionPayload> revisions = new ArrayList<>();
    List<DraftCommitBinding.AffectedUnit> affected = new ArrayList<>();
    for (int i = 0; i < families.size(); i++) {
      UUID revision = UUID.randomUUID();
      var mutation = new LinkedHashMap<String, Object>();
      mutation.put("logicalRevisionId", revision.toString());
      mutation.put("commitId", commit.toString());
      mutation.put("aggregateType", "WORLD_DESIGN_AGGREGATE_TYPE_" + families.get(i));
      mutation.put("aggregateId", templates.get(i).toString());
      if (i == 0)
        mutation.put(
            "freshGraphDeclaration",
            Map.of(
                "tenantId",
                f.tenant.toString(),
                "versionId",
                version.toString(),
                "startLocation",
                Map.of(
                    "tenantId",
                    f.tenant.toString(),
                    "versionId",
                    version.toString(),
                    "roomTemplateId",
                    templates.get(2).toString()),
                "familyCounts",
                List.of(
                        "REGION",
                        "ZONE",
                        "ROOM",
                        "ROOM_EXIT",
                        "GENERATION_RULE",
                        "WORLD_ENTITY_SPAWN_BINDING")
                    .stream()
                    .map(
                        family ->
                            Map.of(
                                "family",
                                "WORLD_DESIGN_AGGREGATE_TYPE_" + family,
                                "count",
                                families.contains(family) ? 1 : 0))
                    .toList()));
      revisions.add(
          new DraftCommitBinding.RevisionPayload(
              Integer.toString(i),
              revision,
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              new tools.jackson.databind.ObjectMapper().writeValueAsString(mutation)));
      affected.add(
          new DraftCommitBinding.AffectedUnit(
              DraftCommitBinding.Owner.WORLD_MANAGEMENT,
              families.get(i),
              templates.get(i).toString(),
              "AGGREGATE",
              templates.get(i).toString(),
              "0"));
    }
    var draft = DraftCommitBinding.create(target, request, commit, "base-1", revisions, affected);
    var selection = selection(draft, "selected publication proof");
    var original =
        new DraftAuthorizationFenceBinding(
            UUID.randomUUID(),
            request,
            commit,
            UUID.randomUUID(),
            f.account.getAccountUuid(),
            f.tenant,
            version,
            "base-1",
            "0",
            draft.canonicalBytes(),
            draft.canonicalBytes(),
            draft.digest(),
            f.tx(() -> f.authority.capture(f.account.getAccountUuid(), f.tenant, environment))
                .sources());
    UUID intake = UUID.randomUUID();
    var operation = new java.io.ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(operation, "world-draft-terminal-operation/v1");
    for (UUID id :
        List.of(original.operationId(), request, commit, original.fenceId(), f.tenant, version))
      DraftAuthorizationFenceBinding.frame(operation, id.toString());
    DraftAuthorizationFenceBinding.frame(operation, draft.canonicalBytes());
    for (String value :
        List.of(
            "test",
            f.tenant.toString(),
            version.toString(),
            original.operationId().toString(),
            "17",
            intake.toString(),
            UUID.randomUUID().toString(),
            "a".repeat(64),
            UUID.randomUUID().toString(),
            "b".repeat(64),
            "c".repeat(64),
            DraftAuthorizationFenceBinding.digest(original.canonicalBytes())))
      DraftAuthorizationFenceBinding.frame(operation, value);
    DraftAuthorizationFenceBinding.frame(operation, original.canonicalBytes());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < revisions.size(); i++) {
      var mapping = new LinkedHashMap<String, Object>();
      mapping.put("id", i + 1);
      mapping.put("target_namespace", "test");
      mapping.put("canonical_tenant_id", f.tenant.toString());
      mapping.put("canonical_version_id", version.toString());
      mapping.put("family", families.get(i));
      mapping.put("template_id", templates.get(i).toString());
      mapping.put("private_row_key", 101 + i);
      mapping.put("tenant_id", 11);
      mapping.put("version_id", 19);
      mapping.put("version_identity_operation_id", original.operationId().toString());
      mapping.put("request_id", request.toString());
      mapping.put("commit_id", commit.toString());
      mapping.put("revision_id", revisions.get(i).revisionId().toString());
      mapping.put("revision_order", Integer.toString(i));
      rows.add(Map.of("mapping", mapping, "content", Map.of()));
    }
    byte[] graph =
        AccountControlUiAuthority.canonical(
            Map.of(
                "schemaVersion",
                "2",
                "canonicalTenantId",
                f.tenant.toString(),
                "canonicalVersionId",
                version.toString(),
                "rows",
                rows));
    String graphDigest = DraftAuthorizationFenceBinding.digest(graph);
    var receipt =
        WorldDraftStartLocationEvidence.create(
            "test",
            original.operationId(),
            request,
            commit,
            original.fenceId(),
            DraftAuthorizationFenceBinding.digest(original.canonicalBytes()),
            draft.digest(),
            new RoomTemplateRef(f.tenant, version, templates.get(2)),
            graphDigest);
    var applied = new LinkedHashMap<String, Object>();
    applied.put("schema", "world-draft-graph-applied/v2");
    applied.put("status", "APPLIED");
    applied.put("operationBytesBase64", b64(operation.toByteArray()));
    applied.put("graphBytesBase64", b64(graph));
    applied.put("graphDigest", graphDigest);
    applied.put("startLocationReceiptBase64", b64(receipt.canonicalBytes()));
    applied.put("startLocationReceiptDigest", receipt.receiptDigest());
    applied.put(
        "appliedEpochs",
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                u ->
                    Map.of(
                        "aggregateType",
                        u.aggregateType(),
                        "aggregateId",
                        u.aggregateId(),
                        "scopeType",
                        u.scopeType(),
                        "scopeId",
                        u.scopeId(),
                        "expectedEpoch",
                        "0",
                        "resultingEpoch",
                        "1"))
            .toList());
    var worldRequest =
        new WorldPublishedStartLocationEvidence.Request(
            "test",
            f.tenant,
            version,
            intake,
            UUID.randomUUID(),
            selection.intent().publishRequestId(),
            selection.digest().substring(7),
            5,
            PublicationDigestRequestBinding.full(
                    f.tenant.toString(), "17", selection.intent().publishRequestId())
                .derivedWorkflowIdentity(),
            commit.toString(),
            "b".repeat(64),
            3,
            draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
                .map(
                    u ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            u.owner().name(),
                            u.aggregateType(),
                            u.aggregateId(),
                            u.scopeType(),
                            u.scopeId(),
                            u.expectedEpoch()))
                .toList());
    return new Proof(
        selection,
        new WorldPublishedStartLocationEvidence(
            worldRequest,
            receipt.canonicalBytes(),
            original.canonicalBytes(),
            AccountControlUiAuthority.canonical(applied)),
        original);
  }

  private static String b64(byte[] value) {
    return Base64.getEncoder().encodeToString(value);
  }

  private static String inventorySpawnBindingInput(
      String bindingTemplateId,
      String roomTemplateId,
      String entityTemplateId,
      String tenantId,
      String versionId) {
    return "{\"bindingTemplateId\":\""
        + bindingTemplateId
        + "\",\"entityReferenceKind\":\"ENTITY_TEMPLATE_REFERENCE_TYPE_CANONICAL_UUID\",\"entityTemplateId\":\""
        + entityTemplateId
        + "\",\"entityTemplateType\":\"ITEM\",\"entityTenantId\":\""
        + tenantId
        + "\",\"entityVersionId\":\""
        + versionId
        + "\",\"respawnDelaySeconds\":0,\"roomTemplateId\":\""
        + roomTemplateId
        + "\",\"spawnCount\":1}";
  }

  private static String inventoryRegionGeneratorInput(String regionTemplateId) {
    return "{\"generatorParams\":\"\",\"generatorType\":\"\",\"regionTemplateId\":\""
        + regionTemplateId
        + "\"}";
  }

  private static void assertSqlFailure(
      Throwable failure, String expectedState, String expectedMessage) {
    java.sql.SQLException sqlFailure = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sqlException) sqlFailure = sqlException;
    }
    assertThat((Throwable) sqlFailure).isNotNull();
    var exactSqlFailure =
        java.util.Objects.requireNonNull(sqlFailure, "Expected SQL failure from direct insert");
    assertThat(exactSqlFailure.getSQLState()).isEqualTo(expectedState);
    assertThat(exactSqlFailure.getMessage()).contains(expectedMessage);
  }

  private static AuthoredDraftPublishSelectionBinding selection(
      DraftCommitBinding draft, String notes) {
    return selection(draft, notes, "publication-request");
  }

  private static AuthoredDraftPublishSelectionBinding selection(
      DraftCommitBinding draft, String notes, String publishRequestId) {
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            publishRequestId,
            "5",
            notes,
            draft.requestId(),
            draft.commitId(),
            draft.digest()),
        draft.target(),
        draft,
        new AuthoredDraftPublishSelectionBinding.VisibilityFence(
            draft.target(),
            draft.requestId(),
            draft.commitId(),
            draft.digest(),
            "[]",
            OffsetDateTime.parse("2026-10-01T00:00:00Z")));
  }
}
