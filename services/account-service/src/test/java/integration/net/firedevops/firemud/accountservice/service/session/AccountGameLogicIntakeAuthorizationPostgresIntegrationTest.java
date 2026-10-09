package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.grpc.Context;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceRevision;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real creator issuance/currentness, Account transactions, V109 and source guards. GD source
 * transport and the original author's terminal are explicit upstream fixtures, not composed
 * production or mTLS proof. No GL terminal, settlement, digest or activation is claimed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountGameLogicIntakeAuthorizationPostgresIntegrationTest {
  private static final Network NETWORK = Network.newNetwork();

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("intake-primary")
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
              "intake-primary",
              "6379");

  @TempDir Path temporary;

  @Test
  void distinctPendingIntakeRequiresSettledAuthorAndBlocksRealSourceWritersAndDisclosure()
      throws Exception {
    try (var fixture =
        new AccountControlUiOriginalOrderFixture(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            redis.getHost(),
            redis.getMappedPort(6379),
            temporary)) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var selected = selected(f.tenant);
      var captured = f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()));
      var original =
          new DraftAuthorizationFenceBinding(
                  UUID.randomUUID(),
                  selected.requestId(),
                  selected.commitId(),
                  UUID.randomUUID(),
                  f.account.getAccountUuid(),
                  f.tenant,
                  selected.target().canonicalVersionId(),
                  selected.baseCommitId(),
                  "0",
                  selected.canonicalBytes(),
                  selected.canonicalBytes(),
                  selected.digest(),
                  captured.sources())
              .withRequiredOwners();
      issued.actors().claimOriginalDraft(issued.compact(), original, issued.environment());
      var source = source(selected);
      var gd = mock(GameplayRuleSourceReadClient.class);
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source))
          .when(gd)
          .read(any());
      var repository = new AccountGameLogicIntakeAuthorizationRepository(f.dsl);
      var service =
          new AccountGameLogicIntakeAuthorizationService(
              issued.actors(), f.fences, repository, gd, "test");
      var requestId = UUID.randomUUID();

      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              f.dsl.fetchCount(org.jooq.impl.DSL.table("account_game_logic_intake_authorizations")))
          .isZero();
      f.tx(
          () -> {
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    DraftAuthorizationFenceBinding.Owner.GAME_DESIGN,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
            return null;
          });
      var order =
          asGameDesign(
              () -> service.authorize(issued.compact(), requestId, selected, issued.environment()));
      assertThat(order.source()).isEqualTo(source);
      assertThat(order.sources()).hasSize(captured.sources().size());
      assertThat(
              asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment()))
                  .canonicalBytes())
          .isEqualTo(order.canonicalBytes());
      String genesis =
          GameplayRuleManifest.tree(source.snapshotJson()).path("genesisReceiptId").textValue();
      var substituted =
          new GameplayRuleSelectedSource(
              source.snapshotJson().replace(genesis, UUID.randomUUID().toString()));
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), substituted))
          .when(gd)
          .read(any());
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              issued.compact(), requestId, selected, issued.environment())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("conflicts");
      doAnswer(call -> new GameplayRuleSourceReadEvidence(call.getArgument(0), source))
          .when(gd)
          .read(any());

      UUID rolledBackRequest = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  issued
                      .actors()
                      .withCurrent(
                          issued.compact(),
                          f.tenant,
                          issued.environment(),
                          current -> {
                            f.fences.lockProducerSourcesNowait(current.source().sources());
                            repository.authorize(
                                rolledBackRequest,
                                source,
                                current,
                                () ->
                                    f.fences.requireGameLogicIntakeAdmission(
                                        current.source().sources(), selected));
                            throw new IllegalStateException("rollback before owner commit");
                          }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(
              f.dsl.fetchOne(
                  "SELECT 1 FROM account_game_logic_intake_authorizations WHERE intake_request_id = ?",
                  rolledBackRequest))
          .isNull();
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.fences.requireDisclosurePreparation(order.sources());
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("intake");

      // Only the distinct intake is pending; original Draft settlement is already COMMITTED.
      assertThat(f.tx(() -> f.fences.readSettlement(original)))
          .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "UPDATE accounts SET role = 'admin' WHERE id = ?", f.account.getId());
                        return null;
                      }))
          .hasStackTraceContaining("Distinct Game Logic intake remains pending");

      var change =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), order.sources(), new byte[] {2});
      assertThat(f.tx(() -> f.fences.requestSourceChange(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceMutationPermitted(change))).isFalse();
      assertThat(f.tx(() -> f.fences.sourceAbortPermitted(change))).isFalse();
      f.tx(
          () -> {
            repository.readHeld(order);
            return null;
          });

      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        f.dsl.execute(
                            "UPDATE account_game_logic_intake_authorizations SET source_digest = ? WHERE operation_id = ?",
                            "sha256:" + "0".repeat(64),
                            order.operationId());
                        return null;
                      }))
          .hasStackTraceContaining("immutable");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          service.authorize(
                              "invalid-credential",
                              UUID.randomUUID(),
                              selected,
                              issued.environment())))
          .isInstanceOf(RuntimeException.class);
    }
  }

  private static DraftCommitBinding selected(UUID tenant) {
    var target =
        new DraftCommitBinding.TargetProof(
            tenant, UUID.randomUUID(), 17, "private", 11, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                UUID.randomUUID(),
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                GameplayRuleSourceRevision.upsertPayload(
                    new GameplayRuleManifest.AdmissionTag("gameplay")))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding selected) {
    var definition = new GameplayRuleManifest.AdmissionTag("gameplay");
    Map<GameplayRuleManifest.Family, List<GameplayRuleManifest.Definition>> families =
        new EnumMap<>(GameplayRuleManifest.Family.class);
    for (var family : GameplayRuleManifest.Family.values()) families.put(family, new ArrayList<>());
    families.get(definition.family()).add(definition);
    var manifest = new GameplayRuleManifest(families);
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "sourceEpoch",
                "1",
                "inheritedCommitId",
                "",
                "genesisReceiptId",
                UUID.randomUUID().toString(),
                "manifestJson",
                manifest.canonicalJson(),
                "entries",
                List.of(
                    Map.of(
                        "family",
                        definition.family().name(),
                        "definitionJson",
                        GameplayRuleManifest.canonical(definition),
                        "sourceBindingJson",
                        selected.canonicalJson(),
                        "sourceBindingDigest",
                        selected.digest(),
                        "revisionOrder",
                        "0",
                        "revisionId",
                        selected.revisions().getFirst().revisionId().toString())))));
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }
}
