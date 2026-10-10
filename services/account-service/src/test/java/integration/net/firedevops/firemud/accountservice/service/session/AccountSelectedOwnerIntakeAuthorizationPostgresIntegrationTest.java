package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountSelectedOwnerIntakeSourceReservationRepository.State;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AssetSnapshot;
import net.firedevops.firemud.common.gamedesign.BrandingSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSnapshot;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence.AppliedEpoch;
import net.firedevops.firemud.common.gamedesign.RealmPolicySnapshot;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigOwnerSourceInventoryDeclaration;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceValues;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.ArtifactDecision;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Checkpoint;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.FamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.Freeze;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceClosureDeclaration;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.InboundSourceFamilyCount;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicAccountOrder;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.PublicOwnerScope;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.RegionGeneratorInput;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SelectedApplication;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence.SourceModel;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
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
 * the settlement case supplies explicitly synthetic upstream Automation terminal evidence (with no
 * authentication proof) and proves real Account storage only, not producer transport or peer
 * authentication, activation, or runtime behavior.
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
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      abortedScope.operationId())
                  .get(0, Boolean.class))
          .isFalse();
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
  void commitsExactAutomationEmptySettlementAndReleasesOnlyThatOperation() throws Exception {
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
      var selected = selectedForSettlement(f.tenant);
      settleOriginal(issued, selected);
      var repository = new AccountSelectedOwnerIntakeSourceReservationRepository(f.dsl);
      var reservationService =
          new AccountSelectedOwnerIntakeSourceReservationService(
              issued.actors(), f.fences, repository, f.manager, "test");
      var bindings = new ArrayList<SelectedOwnerIntakeAuthorizationBinding>();
      var terminals = new ArrayList<AutomationSelectedSourceIntakeTerminalReadEvidence>();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      UUID.randomUUID())
                  .get(0, Boolean.class))
          .isTrue();

      for (int index = 0; index < 2; index++) {
        UUID requestId = UUID.randomUUID();
        var scope =
            asGameDesign(
                () ->
                    reservationService.reserveSourceRead(
                        issued.compact(),
                        requestId,
                        Owner.AUTOMATION_SCRIPTING,
                        selected,
                        issued.environment()));
        var content = content(scope, "automation-settlement-" + index);
        var binding =
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
        bindings.add(binding);
        terminals.add(terminalEvidence(binding, 9001L + index, 9002L + index));
      }

      var firstBinding = bindings.getFirst();
      var secondBinding = bindings.getLast();
      var firstTerminal = terminals.getFirst();
      assertThat(f.<Boolean>tx(() -> repository.findSettlement(firstBinding).isEmpty())).isTrue();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      firstBinding.operationId())
                  .get(0, Boolean.class))
          .isTrue();

      var writerBeforeSettlement =
          new DraftAuthorizationFenceRepository.SourceChange(
              UUID.randomUUID(), firstBinding.sources(), new byte[] {3});
      assertThat(f.<Boolean>tx(() -> f.fences.requestSourceChange(writerBeforeSettlement)))
          .isFalse();

      var committed = f.tx(() -> repository.settleCommittedEmpty(firstTerminal));
      assertThat(committed.authorizationBinding().canonicalBytes())
          .isEqualTo(firstBinding.canonicalBytes());
      assertThat(committed.terminalEvidence().receipt().canonicalBytes())
          .isEqualTo(firstTerminal.receipt().canonicalBytes());
      var stored =
          f.dsl.fetchOne(
              "SELECT * FROM account_selected_owner_intake_settlements WHERE operation_id = ?",
              firstBinding.operationId());
      assertThat(stored.get("receipt_bytes", byte[].class)).isEqualTo(committed.canonicalBytes());
      assertThat(stored.get("receipt_digest", String.class)).isEqualTo(committed.digest());
      assertThat(stored.get("terminal_receipt_bytes", byte[].class))
          .isEqualTo(firstTerminal.receipt().canonicalBytes());
      assertThat(stored.get("terminal_read_request_id", UUID.class))
          .isEqualTo(firstTerminal.request().readRequestId());

      var exactReadback = f.tx(() -> repository.findSettlement(firstBinding).orElseThrow());
      assertThat(exactReadback.canonicalBytes()).isEqualTo(committed.canonicalBytes());
      f.tx(
          () -> {
            repository.readFinalAuthorization(firstBinding);
            return null;
          });
      assertThatThrownBy(
              () ->
                  f.tx(
                      () -> {
                        repository.readHeldFinalAuthorization(firstBinding);
                        return null;
                      }))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no longer held");

      var freshRead =
          new AutomationSelectedSourceIntakeTerminalReadEvidence(
              AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(
                  "test", firstBinding),
              firstTerminal.receipt());
      assertThat(freshRead.request().readRequestId())
          .isNotEqualTo(firstTerminal.request().readRequestId());
      var retry = f.tx(() -> repository.settleCommittedEmpty(freshRead));
      assertThat(retry.canonicalBytes()).isEqualTo(committed.canonicalBytes());
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT terminal_read_request_id FROM account_selected_owner_intake_settlements "
                          + "WHERE operation_id = ?",
                      firstBinding.operationId())
                  .get(0, UUID.class))
          .isEqualTo(firstTerminal.request().readRequestId());
      var changedReceipt = terminalEvidence(firstBinding, 9101L, 9102L);
      assertThatThrownBy(() -> f.tx(() -> repository.settleCommittedEmpty(changedReceipt)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Changed Automation owner terminal receipt");

      var changedSources = new ArrayList<>(firstBinding.sources());
      var originalSource = changedSources.getFirst();
      changedSources.set(
          0,
          new DraftAuthorizationFenceBinding.SourceEvidence(
              originalSource.kind(),
              originalSource.scopeId(),
              originalSource.generation(),
              originalSource.sourceVersion(),
              originalSource.checkpointStream(),
              originalSource.checkpointSequence(),
              new byte[] {99}));
      var changedBinding =
          new SelectedOwnerIntakeAuthorizationBinding(firstBinding.content(), changedSources);
      var changedBindingEvidence = terminalEvidence(changedBinding, 9201L, 9202L);
      assertThatThrownBy(() -> f.tx(() -> repository.settleCommittedEmpty(changedBindingEvidence)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Original finalized owner authorization absent or changed");
      assertThatThrownBy(
              () ->
                  f.dsl.execute(
                      "UPDATE account_selected_owner_intake_settlements "
                          + "SET receipt_digest = ? WHERE operation_id = ?",
                      DraftAuthorizationFenceBinding.digest(new byte[] {1}),
                      firstBinding.operationId()))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT receipt_bytes FROM account_selected_owner_intake_settlements "
                          + "WHERE operation_id = ?",
                      firstBinding.operationId())
                  .get(0, byte[].class))
          .isEqualTo(committed.canonicalBytes());

      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      firstBinding.operationId())
                  .get(0, Boolean.class))
          .isFalse();
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      secondBinding.operationId())
                  .get(0, Boolean.class))
          .isTrue();
      var writerWhileOtherOperationHeld = writerBeforeSettlement;
      assertThat(f.<Boolean>tx(() -> f.fences.requestSourceChange(writerWhileOtherOperationHeld)))
          .isFalse();

      var settlementInserted = new CountDownLatch(1);
      var allowSettlementCommit = new CountDownLatch(1);
      var writerStarted = new CountDownLatch(1);
      var writerBackendPid = new AtomicInteger();
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        var settling =
            executor.submit(
                () ->
                    f.transactions.execute(
                        ignored -> {
                          var result = repository.settleCommittedEmpty(terminals.getLast());
                          settlementInserted.countDown();
                          awaitLatch(allowSettlementCommit);
                          return result;
                        }));
        assertThat(settlementInserted.await(10, TimeUnit.SECONDS)).isTrue();

        var waitingWriter =
            executor.submit(
                () ->
                    f.transactions.execute(
                        ignored -> {
                          writerBackendPid.set(
                              f.dsl.fetchSingle("SELECT pg_backend_pid()").get(0, Integer.class));
                          writerStarted.countDown();
                          return f.fences.requestSourceChange(writerWhileOtherOperationHeld);
                        }));
        assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(writerBackendPid.get()).isPositive();
        awaitSourceLockWait(f.dsl, writerBackendPid.get());
        assertThat(waitingWriter.isDone()).isFalse();

        allowSettlementCommit.countDown();
        assertThat(settling.get(10, TimeUnit.SECONDS).authorizationBinding().canonicalBytes())
            .isEqualTo(secondBinding.canonicalBytes());
        assertThat(waitingWriter.get(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        allowSettlementCommit.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(
              f.dsl
                  .fetchSingle(
                      "SELECT account_selected_owner_intake_source_read_is_pending(?)",
                      secondBinding.operationId())
                  .get(0, Boolean.class))
          .isFalse();
      assertThat(
              f.<Boolean>tx(() -> f.fences.sourceMutationPermitted(writerWhileOtherOperationHeld)))
          .isTrue();

      String settlementGuard =
          f.dsl
              .fetchSingle(
                  "SELECT pg_get_functiondef('account_selected_owner_intake_settlement_guard()'::regprocedure)")
              .get(0, String.class);
      assertThat(settlementGuard)
          .contains(
              "ORDER BY account_publication_authorization_source_sort_key(lock_row.source_key)",
              "FOR UPDATE OF lock_row");
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
          for (var owner : original.requiredOwners()) {
            f.fences.recordOwnerReadback(
                original,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    owner,
                    DraftAuthorizationFenceBinding.Outcome.COMMITTED,
                    original.operationId(),
                    original.commitId(),
                    original.fenceId(),
                    original.inputDigest(),
                    original.canonicalBytes(),
                    new byte[] {1}));
          }
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
    var template = templateSnapshot(scope.owner(), selected, genesis);
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

  private static TemplateConfigSourceSnapshot templateSnapshot(
      Owner owner, DraftCommitBinding selected, UUID genesis) {
    if (owner != Owner.AUTOMATION_SCRIPTING) {
      return new TemplateConfigSourceSnapshot(selected, "0", null, genesis, List.of());
    }
    var inventory =
        AutomationAuthoredSourceInventoryDeclaration.parse(
            "{\"schema\":\"automation-authored-source-inventory/v1\",\"families\":{"
                + "\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
                + "\"SCRIPT_PATCH_SOURCES\":[]}}");
    String payload = TemplateConfigSourceValues.ownerInventoryPayload(owner, inventory);
    DraftCommitBinding authored =
        DraftCommitBinding.create(
            selected.target(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "synthetic-template-config-base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", UUID.randomUUID(), Owner.GAME_DESIGN_CONTROL_PLANE, payload)),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    Owner.GAME_DESIGN_CONTROL_PLANE,
                    TemplateConfigSourceValues.SCOPE,
                    selected.target().canonicalVersionId().toString(),
                    TemplateConfigSourceValues.SCOPE,
                    TemplateConfigSourceValues.SCOPE_ID,
                    "0")));
    TemplateConfigOwnerSourceInventoryDeclaration declaration =
        TemplateConfigSourceValues.mutations(authored).stream()
            .filter(
                mutation ->
                    mutation.operation()
                        == TemplateConfigSourceValues.OperationKind.DECLARE_OWNER_SOURCE_INVENTORY)
            .map(TemplateConfigSourceValues.Mutation::ownerInventoryDeclaration)
            .findFirst()
            .orElseThrow();
    return new TemplateConfigSourceSnapshot(
        selected, "0", null, genesis, List.of(), List.of(declaration));
  }

  private static AutomationSelectedSourceIntakeTerminalReadEvidence terminalEvidence(
      SelectedOwnerIntakeAuthorizationBinding binding, long localTenantKey, long localVersionKey) {
    var freeze = freezeEvidence(binding);
    var worldRequest =
        SelectedOwnerWorldInventoryReadEvidence.create(binding.targetNamespace(), binding, freeze);
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromPublicEvidence(
            freeze, publicInventory(freeze));
    var worldEvidence = new SelectedOwnerWorldInventoryReadEvidence(worldRequest, inventory);
    var inputs = new SelectedOwnerEmptySourceInputs(binding, worldEvidence);
    var receipt =
        AutomationEmptySelectedSourceIntakeReceipt.create(
            inputs,
            localTenantKey,
            localVersionKey,
            AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                binding.targetNamespace(), binding, freeze),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            OffsetDateTime.parse("2026-10-10T00:00:00Z"));
    return new AutomationSelectedSourceIntakeTerminalReadEvidence(
        AutomationSelectedSourceIntakeTerminalReadEvidence.Request.create(
            binding.targetNamespace(), binding),
        receipt);
  }

  private static WorldSelectedDraftPublicationFreezeEvidence freezeEvidence(
      SelectedOwnerIntakeAuthorizationBinding binding) {
    var selected = binding.selected();
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                binding.tenantId(),
                binding.versionId(),
                "settlement-publication-request",
                "9",
                "synthetic selected-owner settlement fixture",
                selected.requestId(),
                selected.commitId(),
                selected.digest()),
            selected.target(),
            selected,
            new VisibilityFence(
                selected.target(),
                selected.requestId(),
                selected.commitId(),
                selected.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                binding.actorAccountId(), selection),
            List.of(accountSource(binding.actorAccountId())));
    var request =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            binding.targetNamespace(),
            binding.tenantId(),
            binding.versionId(),
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            account);
    var acknowledgement =
        new Acknowledgement(
            request,
            UUID.randomUUID(),
            9,
            UUID.randomUUID(),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            4);
    return WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
        request, WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
  }

  private static PublicEvidence publicInventory(
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    var request = freezeEvidence.request();
    var acknowledgement = freezeEvidence.acknowledgement();
    var account = request.accountBinding();
    var selection = account.input().selection();
    var selected = selection.selectedCommit();
    var closure =
        new InboundSourceClosureDeclaration(
            1,
            List.of(
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING", 0),
                new InboundSourceFamilyCount("WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE", 0),
                new InboundSourceFamilyCount(
                    "WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING", 0)));
    var sourceModel =
        new SourceModel(
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SOURCE_MODEL,
            WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_GRAPH_SCHEMA_VERSION,
            "sha256:" + "4".repeat(64),
            "sha256:" + "5".repeat(64),
            List.of(
                new FamilyCount("REGION", 1),
                new FamilyCount("ZONE", 1),
                new FamilyCount("ROOM", 1),
                new FamilyCount("ROOM_EXIT", 1),
                new FamilyCount("GENERATION_RULE", 0),
                new FamilyCount("WORLD_ENTITY_SPAWN_BINDING", 0)),
            List.of(new RegionGeneratorInput(UUID.randomUUID(), "", "")),
            List.of("id", "name", "scopeType", "scopeId", "value"),
            List.of(
                "id",
                "shardId",
                "name",
                "weather",
                "generationSeed",
                "generatorType",
                "generatorParams",
                "spacingMultiplier"),
            List.of("id", "regionId", "name"),
            List.of(
                "id",
                "zoneId",
                "name",
                "description",
                "nameLocalizedVariantsJson",
                "descriptionLocalizedVariantsJson"),
            List.of("id", "fromRoomId", "toRoomId", "direction", "cost"),
            List.of(
                "id",
                "roomId",
                "entityTemplateType",
                "entityReference.kind",
                "entityReference.tenantId",
                "entityReference.versionId",
                "entityReference.templateId",
                "spawnCount",
                "respawnDelaySeconds"),
            List.of(),
            0,
            0,
            selected.affectedUnits(Owner.WORLD_MANAGEMENT).stream()
                .map(
                    unit ->
                        new AppliedEpoch(
                            unit.aggregateType(),
                            unit.aggregateId(),
                            unit.scopeType(),
                            unit.scopeId(),
                            unit.expectedEpoch(),
                            new BigInteger(unit.expectedEpoch()).add(BigInteger.ONE).toString()))
                .toList(),
            closure);
    var ownerScope =
        new PublicOwnerScope(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.canonicalVersionId(),
            UUID.randomUUID(),
            acknowledgement.intakeRequestId(),
            UUID.randomUUID(),
            "sha256:" + "1".repeat(64),
            UUID.randomUUID(),
            "sha256:" + "2".repeat(64),
            "sha256:" + "3".repeat(64));
    return new PublicEvidence(
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA,
        WorldSelectedPublicationArtifactInventoryEvidence.INBOUND_CLOSURE_SCHEMA_VERSION,
        "COMPLETE",
        ownerScope,
        new SelectedApplication(
            UUID.randomUUID(),
            selected.requestId(),
            selected.commitId().toString(),
            selected.digest(),
            "sha256:" + "6".repeat(64)),
        new PublicAccountOrder(
            account.operationId(),
            account.fenceId(),
            account.input().actorAccountId(),
            selection.intent().publishRequestId(),
            selection.digest(),
            selected.commitId().toString(),
            DraftAuthorizationFenceBinding.digest(account.canonicalBytes())),
        new Freeze(
            acknowledgement.publicationFence(),
            request.publicationRequestId(),
            request.requestDigest(),
            Long.toString(acknowledgement.versionStateEpoch()),
            PublicationDigestRequestBinding.full(
                    request.canonicalTenantId().toString(),
                    Long.toString(selection.target().gameDesignVersionRowId()),
                    request.publicationRequestId())
                .derivedWorkflowIdentity()),
        new Checkpoint(acknowledgement.appliedCommitId(), acknowledgement.contentDigest(), 4),
        sourceModel,
        List.of(
            new ArtifactDecision(
                "NAVMESH", "NOT_REQUIRED", "ROOM_LOGICAL_TOPOLOGY_HAS_NO_SPATIAL_NAVMESH_INPUT"),
            new ArtifactDecision(
                "PATH_GRAPH",
                "NOT_REQUIRED",
                "AUTHORED_ROOM_EXIT_EDGES_ARE_THE_SUPPORTED_TRAVERSAL_GRAPH")));
  }

  private static SourceEvidence accountSource(UUID accountId) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        accountId.toString(),
        null,
        "1",
        null,
        null,
        "synthetic-account-publication".getBytes(StandardCharsets.UTF_8));
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

  private static DraftCommitBinding selectedForSettlement(UUID tenant) {
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
                "0", UUID.randomUUID(), Owner.WORLD_MANAGEMENT, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.WORLD_MANAGEMENT, "REGION", "region-1", "AGGREGATE", "region-1", "0")));
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Expected concurrent settlement boundary");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted concurrent settlement boundary", interrupted);
    }
  }

  private static void awaitSourceLockWait(DSLContext dsl, int writerBackendPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      if (Boolean.TRUE.equals(
          dsl.fetchSingle(
                  "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                      + " WHERE pid = ? AND datname = current_database()"
                      + " AND wait_event_type = 'Lock'"
                      + " AND query LIKE '%account_draft_authorization_source_locks%')",
                  writerBackendPid)
              .get(0, Boolean.class))) {
        return;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Source writer did not block on settlement-held source locks");
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
