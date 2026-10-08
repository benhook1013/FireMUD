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
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
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

  @Test
  void authenticatesDistinctOrderReplaysExactlyAndBlocksSourceWritersAndDisclosure()
      throws Exception {
    try (var fixture = fixture()) {
      var issued = fixture.issueCreator();
      var f = issued.sources();
      var proof = proof(f, issued.environment());
      var service =
          new AccountPublicationAuthorizationService(
              issued.actors(), f.fences, new AccountPublicationAuthorizationRepository(f.dsl));
      var original =
          service.authorize(
              issued.compact(), proof.selection(), proof.world(), issued.environment());
      var replay =
          service.authorize(
              issued.compact(), proof.selection(), proof.world(), issued.environment());
      assertThat(replay.canonicalBytes()).isEqualTo(original.canonicalBytes());
      assertThat(original.operationId()).isNotEqualTo(proof.original().operationId());
      assertThat(original.fenceId()).isNotEqualTo(proof.original().fenceId());
      assertThat(f.dsl.fetchCount(org.jooq.impl.DSL.table("account_draft_authorization_fences")))
          .isZero();
      assertThat(
              f.dsl.fetchCount(
                  org.jooq.impl.DSL.table("account_selected_publication_authorizations")))
          .isEqualTo(1);
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
                  .authorize(
                      issued.compact(), proof.selection(), proof.world(), issued.environment())
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
      assertThatThrownBy(
              () ->
                  service.authorize(issued.compact(), changed, proof.world(), issued.environment()))
          .isInstanceOf(IllegalArgumentException.class);
      var request = proof.world().request();
      var changedWorld =
          new WorldPublishedStartLocationEvidence(
              new WorldPublishedStartLocationEvidence.Request(
                  request.targetNamespace(),
                  request.canonicalTenantId(),
                  request.canonicalVersionId(),
                  request.intakeRequestId(),
                  UUID.randomUUID(),
                  request.publicationRequestId(),
                  request.requestDigest(),
                  request.versionStateEpoch(),
                  request.publishWorkflowId(),
                  request.appliedCommitId(),
                  request.contentDigest(),
                  request.digestSchemaVersion(),
                  request.worldAffectedTuples()),
              proof.world().selectorReceiptBytes(),
              proof.world().originalAccountBindingBytes(),
              proof.world().appliedResultBytes());
      assertThatThrownBy(
              () ->
                  service.authorize(
                      issued.compact(), proof.selection(), changedWorld, issued.environment()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Changed immutable");
      assertThatThrownBy(
              () ->
                  service.authorize(
                      issued.compact() + "changed",
                      proof.selection(),
                      proof.world(),
                      issued.environment()))
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
              () ->
                  service.authorize(
                      issued.compact(), proof.selection(), proof.world(), issued.environment()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("source change is waiting");
      assertThatThrownBy(
              () ->
                  service.authorize(
                      "unsigned-caller-claim",
                      proof.selection(),
                      proof.world(),
                      issued.environment()))
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
                              issued.compact(),
                              proof.selection(),
                              proof.world(),
                              issued.environment())));
              long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
              Integer blockedPid = null;
              while (System.nanoTime() < deadline && blockedPid == null) {
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

  private static AuthoredDraftPublishSelectionBinding selection(
      DraftCommitBinding draft, String notes) {
    return AuthoredDraftPublishSelectionBinding.capture(
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            draft.target().canonicalTenantId(),
            draft.target().canonicalVersionId(),
            "publication-request",
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
