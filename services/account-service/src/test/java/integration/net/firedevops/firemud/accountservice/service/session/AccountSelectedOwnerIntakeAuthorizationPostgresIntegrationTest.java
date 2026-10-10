package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedOwnerIntakeSourceReservationRepository.State;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
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
 * Real Account creator/currentness, PostgreSQL finalization, immutable source participation and
 * pending source guards. Six-family content and the Game Design caller are stipulated test inputs;
 * this is not producer transport, authenticated Entity/Automation retention, terminal settlement,
 * activation, or runtime proof.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class AccountSelectedOwnerIntakeAuthorizationPostgresIntegrationTest {
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
  void finalizesBothOwnersExactlyAndKeepsTheirFullSourceVectorsPending() throws Exception {
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
      settleOriginal(issued, selected);
      var expectedSources =
          f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()).sources());

      var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl);
      var reservationService =
          new AccountSelectedOwnerIntakeSourceReservationService(
              issued.actors(), f.fences, repository, f.manager, "test");
      var finalized = new ArrayList<SelectedOwnerIntakeAuthorizationBinding>();
      var scopes = new ArrayList<SelectedOwnerIntakeSourceReadScope>();

      for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
        UUID requestId = UUID.randomUUID();
        var scope =
            asGameDesign(
                () ->
                    reservationService.reserveSourceRead(
                        issued.compact(), requestId, owner, selected, issued.environment()));
        var content = content(scope, "stipulated-original");
        var authorization =
            asGameDesign(
                () ->
                    issued
                        .actors()
                        .withCurrent(
                            issued.compact(),
                            f.tenant,
                            issued.environment(),
                            current ->
                                repository.finalizeSourceRead(
                                    scope,
                                    content,
                                    current,
                                    () ->
                                        f.fences.requireSelectedOwnerIntakeAdmission(
                                            current.source().sources(), selected))));

        assertThat(authorization.owner()).isEqualTo(owner);
        assertThat(authorization.content().canonicalBytes()).isEqualTo(content.canonicalBytes());
        assertThat(authorization.sources()).hasSize(expectedSources.size());
        for (int index = 0; index < expectedSources.size(); index++) {
          assertThat(authorization.sources().get(index).key())
              .isEqualTo(expectedSources.get(index).key());
          assertThat(authorization.sources().get(index).canonicalBytes())
              .isEqualTo(expectedSources.get(index).canonicalBytes());
        }
        assertThat(f.tx(() -> repository.recoverSourceRead(scope)).state())
            .isEqualTo(State.FINALIZED);
        var readback = f.tx(() -> repository.findFinalAuthorization(scope)).orElseThrow();
        assertThat(readback.canonicalBytes()).isEqualTo(authorization.canonicalBytes());
        assertThat(readback.digest()).isEqualTo(authorization.digest());
        var exactRetry =
            asGameDesign(
                () ->
                    issued
                        .actors()
                        .withCurrent(
                            issued.compact(),
                            f.tenant,
                            issued.environment(),
                            current ->
                                repository.finalizeSourceRead(
                                    scope,
                                    content,
                                    current,
                                    () -> {
                                      throw new AssertionError(
                                          "Exact finalization retry must not re-admit");
                                    })));
        assertThat(exactRetry.canonicalBytes()).isEqualTo(authorization.canonicalBytes());
        f.tx(
            () -> {
              repository.readFinalAuthorization(authorization);
              return null;
            });
        assertThatThrownBy(() -> f.tx(() -> repository.sourceReadCurrentness(scope)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("preliminary source read");
        assertThatThrownBy(() -> f.tx(() -> repository.abortSourceRead(scope)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be preliminarily aborted");

        var changedContent = content(scope, "stipulated-changed");
        assertThatThrownBy(
                () ->
                    asGameDesign(
                        () ->
                            issued
                                .actors()
                                .withCurrent(
                                    issued.compact(),
                                    f.tenant,
                                    issued.environment(),
                                    current ->
                                        repository.finalizeSourceRead(
                                            scope, changedContent, current, () -> {}))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicts with original selected content");

        scopes.add(scope);
        finalized.add(authorization);
      }

      UUID abortedRequest = UUID.randomUUID();
      var abortedScope =
          asGameDesign(
              () ->
                  reservationService.reserveSourceRead(
                      issued.compact(),
                      abortedRequest,
                      Owner.ENTITY_MANAGEMENT,
                      selected,
                      issued.environment()));
      assertThat(f.tx(() -> repository.abortSourceRead(abortedScope).state()))
          .isEqualTo(State.ABORTED);
      var abortedContent = content(abortedScope, "stipulated-aborted");
      assertThatThrownBy(
              () ->
                  asGameDesign(
                      () ->
                          issued
                              .actors()
                              .withCurrent(
                                  issued.compact(),
                                  f.tenant,
                                  issued.environment(),
                                  current ->
                                      repository.finalizeSourceRead(
                                          abortedScope, abortedContent, current, () -> {}))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("scope aborted");
      assertThat(
              f.dsl.fetchOne(
                  "SELECT operation_id FROM account_selected_owner_intake_authorizations WHERE operation_id = ?",
                  abortedScope.operationId()))
          .isNull();

      var sourceChange =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), finalized.getFirst().sources(), new byte[] {2});
      assertThat(f.<Boolean>tx(() -> f.fences.requestSourceChange(sourceChange))).isFalse();
      assertThat(f.<Boolean>tx(() -> f.fences.sourceMutationPermitted(sourceChange))).isFalse();
      assertThat(scopes).hasSize(2);
    }
  }

  @Test
  void postgresRejectsChangedNamespaceUnknownFamilyAndSelectedScopeEvidence() throws Exception {
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
      settleOriginal(issued, selected);
      var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl);
      var reservationService =
          new AccountSelectedOwnerIntakeSourceReservationService(
              issued.actors(), f.fences, repository, f.manager, "test");
      var scopes = new ArrayList<SelectedOwnerIntakeSourceReadScope>();
      for (int index = 0; index < 3; index++) {
        scopes.add(
            asGameDesign(
                () ->
                    reservationService.reserveSourceRead(
                        issued.compact(),
                        UUID.randomUUID(),
                        Owner.ENTITY_MANAGEMENT,
                        selected,
                        issued.environment())));
      }

      var original = scopes.get(0);
      var changedNamespace =
          new SelectedOwnerIntakeSourceReadScope(
              original.owner(),
              "different-namespace",
              original.operationId(),
              original.fenceId(),
              original.intakeRequestId(),
              original.actorAccountId(),
              original.selected());
      var namespaceContent = content(changedNamespace, "stipulated-namespace-mismatch");
      var namespaceBinding =
          new SelectedOwnerIntakeAuthorizationBinding(
              namespaceContent,
              f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()).sources()));
      assertRejectedByPostgres(
          issued,
          original,
          namespaceContent.canonicalBytes(),
          namespaceBinding.canonicalBytes(),
          "Selected content scope differs from original reservation");

      var familyScope = scopes.get(1);
      var familyContent = content(familyScope, "stipulated-family-mismatch");
      byte[] unknownFamilyContent = replaceFirstFamily(familyContent.canonicalBytes(), "UNKNOWN");
      var familySources =
          f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()).sources());
      assertRejectedByPostgres(
          issued,
          familyScope,
          unknownFamilyContent,
          authorizationBytes(familyScope, unknownFamilyContent, familySources),
          "Selected owner content family is missing, unknown, or changed");

      var selectedScope = scopes.get(2);
      var changedSelectedScope =
          new SelectedOwnerIntakeSourceReadScope(
              selectedScope.owner(),
              selectedScope.targetNamespace(),
              selectedScope.operationId(),
              selectedScope.fenceId(),
              selectedScope.intakeRequestId(),
              selectedScope.actorAccountId(),
              selected(f.tenant));
      var changedSelectedContent = content(changedSelectedScope, "stipulated-scope-mismatch");
      var changedSelectedBinding =
          new SelectedOwnerIntakeAuthorizationBinding(
              changedSelectedContent,
              f.tx(() -> f.authority.captureInitial(f.tenant, issued.environment()).sources()));
      assertRejectedByPostgres(
          issued,
          selectedScope,
          changedSelectedContent.canonicalBytes(),
          changedSelectedBinding.canonicalBytes(),
          "Selected content scope differs from original reservation");
    }
  }

  private static void assertRejectedByPostgres(
      AccountControlUiOriginalOrderFixture.IssuedCreator issued,
      SelectedOwnerIntakeSourceReadScope reservationScope,
      byte[] contentBytes,
      byte[] bindingBytes,
      String expectedDiagnostic) {
    var f = issued.sources();
    assertThatThrownBy(
            () ->
                asGameDesign(
                    () ->
                        issued
                            .actors()
                            .withCurrent(
                                issued.compact(),
                                f.tenant,
                                issued.environment(),
                                current -> {
                                  insertRawAuthorization(
                                      f.dsl, reservationScope, contentBytes, bindingBytes, current);
                                  return null;
                                })))
        .isInstanceOf(RuntimeException.class)
        .satisfies(
            failure -> {
              Throwable root = failure;
              while (root.getCause() != null) root = root.getCause();
              assertThat(root)
                  .isInstanceOfSatisfying(
                      java.sql.SQLException.class,
                      sql -> {
                        assertThat(sql.getSQLState()).isEqualTo("23514");
                        assertThat(sql.getMessage()).contains(expectedDiagnostic);
                      });
            });
    assertThat(
            f.dsl.fetchOne(
                "SELECT operation_id FROM account_selected_owner_intake_authorizations "
                    + "WHERE operation_id = ?",
                reservationScope.operationId()))
        .isNull();
  }

  private static void insertRawAuthorization(
      org.jooq.DSLContext dsl,
      SelectedOwnerIntakeSourceReadScope reservationScope,
      byte[] contentBytes,
      byte[] bindingBytes,
      AccountControlUiActorService.Current current) {
    dsl.execute(
        "INSERT INTO account_selected_owner_intake_authorizations "
            + "(operation_id, fence_id, intake_request_id, owner, target_namespace, "
            + "actor_account_uuid, tenant_uuid, version_uuid, content_bytes, content_digest, "
            + "binding_bytes, binding_digest, issuance_operation_id, issuance_fence, source_payload, "
            + "issuance_bundle, outbox_checkpoints) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        reservationScope.operationId(),
        reservationScope.fenceId(),
        reservationScope.intakeRequestId(),
        reservationScope.owner().name(),
        reservationScope.targetNamespace(),
        reservationScope.actorAccountId(),
        reservationScope.selected().target().canonicalTenantId(),
        reservationScope.selected().target().canonicalVersionId(),
        contentBytes,
        DraftAuthorizationFenceBinding.digest(contentBytes),
        bindingBytes,
        DraftAuthorizationFenceBinding.digest(bindingBytes),
        current.stored().operationId,
        current.source().issuanceFence(),
        current.stored().sources,
        current.stored().bundle,
        AccountControlUiAuthority.canonical(current.source().outboxCheckpoints()));
    var sources =
        current.source().sources().stream()
            .sorted(Comparator.comparing(DraftAuthorizationFenceBinding.SourceEvidence::key))
            .toList();
    for (var source : sources) {
      dsl.execute(
          "INSERT INTO account_selected_owner_intake_sources "
              + "(operation_id, source_key, source_evidence) VALUES (?, ?, ?)",
          reservationScope.operationId(),
          source.key(),
          source.canonicalBytes());
    }
  }

  private static byte[] authorizationBytes(
      SelectedOwnerIntakeSourceReadScope scope,
      byte[] contentBytes,
      List<DraftAuthorizationFenceBinding.SourceEvidence> sources) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, authorizationSchema(scope.owner()));
    DraftAuthorizationFenceBinding.frame(out, contentBytes);
    DraftAuthorizationFenceBinding.frame(out, DraftAuthorizationFenceBinding.digest(contentBytes));
    DraftAuthorizationFenceBinding.frame(
        out,
        scope.owner() == Owner.ENTITY_MANAGEMENT
            ? "spiffe://firemud/ns/" + scope.targetNamespace() + "/sa/entity-management-service"
            : "spiffe://firemud/ns/"
                + scope.targetNamespace()
                + "/sa/automation-scripting-service");
    DraftAuthorizationFenceBinding.frame(
        out,
        scope.owner() == Owner.ENTITY_MANAGEMENT
            ? "ENTITY_INTAKE_RETENTION"
            : "AUTOMATION_INTAKE_RETENTION");
    var sorted =
        sources.stream()
            .sorted(Comparator.comparing(DraftAuthorizationFenceBinding.SourceEvidence::key))
            .toList();
    DraftAuthorizationFenceBinding.frame(out, Integer.toString(sorted.size()));
    for (var source : sorted) DraftAuthorizationFenceBinding.frame(out, source.canonicalBytes());
    return out.toByteArray();
  }

  private static String authorizationSchema(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "account-entity-intake-authorization/v1"
        : "account-automation-intake-authorization/v1";
  }

  private static byte[] replaceFirstFamily(byte[] contentBytes, String replacement) {
    var reader = new DraftAuthorizationFenceBinding.FrameReader(contentBytes);
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, reader.text());
    DraftAuthorizationFenceBinding.frame(out, reader.bytes());
    DraftAuthorizationFenceBinding.frame(out, reader.text());
    for (int index = 0; index < 6; index++) {
      String family = reader.text();
      DraftAuthorizationFenceBinding.frame(out, index == 0 ? replacement : family);
      DraftAuthorizationFenceBinding.frame(out, reader.bytes());
      DraftAuthorizationFenceBinding.frame(out, reader.text());
    }
    reader.requireEnd();
    return out.toByteArray();
  }

  private static void settleOriginal(
      AccountControlUiOriginalOrderFixture.IssuedCreator issued, DraftCommitBinding selected) {
    var f = issued.sources();
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
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope, String marker) {
    var selected = scope.selected();
    UUID genesis = UUID.nameUUIDFromBytes(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String markerEpoch = Integer.toUnsignedString(marker.hashCode());
    var gameplay =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", selected.canonicalJson(),
                    "bindingDigest", selected.digest(),
                    "sourceEpoch", "0",
                    "inheritedCommitId", "",
                    "genesisReceiptId", genesis.toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var template = new TemplateConfigSourceSnapshot(selected, "0", null, genesis, List.of());
    var snapshots =
        Map.of(
            "COMMAND",
            new CommandSnapshot(
                    selected,
                    "0",
                    null,
                    DraftAuthorizationFenceBinding.digest(
                        marker.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    List.of())
                .canonicalBytes(),
            "REALM_POLICY",
            new RealmPolicySnapshot(selected, markerEpoch, List.of()).canonicalBytes(),
            "ASSET",
            new AssetSnapshot(selected, "0", null, genesis, List.of()).canonicalBytes(),
            "BRANDING",
            new BrandingSourceSnapshot(selected, "0", null, genesis, List.of()).canonicalBytes());
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    DraftAuthorizationFenceBinding.frame(out, scope.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot =
          switch (family) {
            case "GAMEPLAY_RULE" -> gameplay.canonicalBytes();
            case "TEMPLATE_CONFIG" -> template.canonicalBytes();
            default -> snapshots.get(family);
          };
      DraftAuthorizationFenceBinding.frame(out, family);
      DraftAuthorizationFenceBinding.frame(out, snapshot);
      DraftAuthorizationFenceBinding.frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
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
                Owner.GAME_DESIGN_CONTROL_PLANE,
                CommandSource.deletePayload("synthetic-fixture-command"))),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "effective",
                "0")));
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    var context =
        io.grpc.Context.current()
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
