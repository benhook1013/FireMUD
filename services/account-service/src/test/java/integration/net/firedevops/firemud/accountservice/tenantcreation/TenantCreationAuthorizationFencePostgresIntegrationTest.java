package integration.net.firedevops.firemud.accountservice.tenantcreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChangeAbortReason;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.accountservice.tenantcreation.TenantCreationAuthorizationFenceRepository;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Synthetic original authorization captures and definitive readbacks stipulate absent authenticated
 * producers. These tests prove component ordering only, never real authorization, atomic Game
 * Design qualification, Account bootstrap, admission or runtime activation.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantCreationAuthorizationFencePostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void revokeFirstRetainsBothFamilyFencesUntilExactAborts() {
    Context c = context("67");
    var b = binding();
    var draft = draft(b.sources().getFirst());
    var change = new SourceChange(UUID.randomUUID(), b.sources(), new byte[] {7});
    tx(
        c,
        () -> {
          c.creation.reserve(b);
          c.draft.reserve(draft);
          return null;
        });
    assertThat(tx(c, () -> c.draft.requestSourceChange(change))).isFalse();
    assertThat(tx(c, () -> c.creation.read(b).ordering())).isEqualTo(Ordering.REVOKE_ORDER);
    assertThat(tx(c, () -> c.draft.read(draft).ordering())).isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(() -> tx(c, () -> c.creation.claimCommitOrder(b)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> tx(c, () -> c.creation.reserve(bindingWithSources(b.sources()))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tx(c, () -> c.creation.readOwnerResult(b, Owner.GAME_DESIGN))).isEmpty();
    owner(c, b, Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED);
    owner(c, b, Owner.ACCOUNT, Outcome.DEFINITIVELY_ABORTED);
    assertThat(tx(c, () -> c.draft.sourceMutationPermitted(change))).isFalse();
    draftOwners(c, draft);
    assertThat(tx(c, () -> c.draft.sourceMutationPermitted(change))).isTrue();
    tx(
        c,
        () -> {
          c.draft.markSourceCommitted(change);
          return null;
        });
    assertThat(tx(c, () -> c.creation.reserve(b).ordering())).isEqualTo(Ordering.REVOKE_ORDER);
    assertThatThrownBy(() -> tx(c, () -> c.creation.claimCommitOrder(b)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void commitFirstRequiresBothExactOutcomesAndMixedVectorDoesNotQualifySuccess() {
    Context c = context("67");
    var b = binding();
    var change = new SourceChange(UUID.randomUUID(), b.sources(), new byte[] {8});
    tx(
        c,
        () -> {
          c.creation.reserve(b);
          return c.creation.claimCommitOrder(b);
        });
    assertThat(tx(c, () -> c.draft.requestSourceChange(change))).isFalse();
    owner(c, b, Owner.GAME_DESIGN, Outcome.COMMITTED);
    assertThat(tx(c, () -> c.creation.readSettlement(b))).isEqualTo(Settlement.PENDING);
    assertThat(tx(c, () -> c.draft.sourceAbortPermitted(change))).isFalse();
    assertThatThrownBy(
            () ->
                tx(
                    c,
                    () -> {
                      c.draft.markSourceCommitted(change);
                      return null;
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                tx(
                    c,
                    () ->
                        c.dsl.execute(
                            "UPDATE account_draft_authorization_source_changes SET status = 'SOURCE_ABORTED',"
                                + " aborted_at = CURRENT_TIMESTAMP, abort_reason = 'DEFINITIVE_ABORT' WHERE change_id = ?",
                            change.changeId())))
        .isInstanceOf(DataAccessException.class);
    owner(c, b, Owner.ACCOUNT, Outcome.DEFINITIVELY_ABORTED);
    assertThat(tx(c, () -> c.creation.readSettlement(b)))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    assertThat(tx(c, () -> c.draft.sourceAbortPermitted(change))).isTrue();
    tx(
        c,
        () -> {
          c.draft.markSourceAborted(change, SourceChangeAbortReason.DEFINITIVE_ABORT);
          return null;
        });
    assertThat(
            tx(c, () -> c.creation.readOwnerResult(b, Owner.GAME_DESIGN)).orElseThrow().outcome())
        .isEqualTo(Outcome.COMMITTED);
  }

  @Test
  void contradictoryRevokeOutcomeNeverReleasesSourceOrdering() {
    Context c = context("67");
    var b = binding();
    var change = new SourceChange(UUID.randomUUID(), b.sources(), new byte[] {9});
    tx(c, () -> c.creation.reserve(b));
    assertThat(tx(c, () -> c.draft.requestSourceChange(change))).isFalse();
    owner(c, b, Owner.GAME_DESIGN, Outcome.COMMITTED);
    owner(c, b, Owner.ACCOUNT, Outcome.DEFINITIVELY_ABORTED);
    assertThat(tx(c, () -> c.creation.readSettlement(b))).isEqualTo(Settlement.PENDING);
    assertThat(tx(c, () -> c.draft.sourceMutationPermitted(change))).isFalse();
    assertThat(tx(c, () -> c.draft.sourceAbortPermitted(change))).isFalse();
  }

  @Test
  void exactRetryAndDatabaseGuardsPreserveBindingAndFinalizedResults() {
    Context c = context("67");
    var b = binding();
    tx(c, () -> c.creation.reserve(b));
    assertThatThrownBy(() -> owner(c, b, Owner.ACCOUNT, Outcome.COMMITTED))
        .isInstanceOf(IllegalStateException.class);
    tx(c, () -> c.creation.claimCommitOrder(b));
    owner(c, b, Owner.ACCOUNT, Outcome.COMMITTED);
    owner(c, b, Owner.GAME_DESIGN, Outcome.COMMITTED);
    owner(c, b, Owner.GAME_DESIGN, Outcome.COMMITTED);
    assertThat(tx(c, () -> c.creation.readSettlement(b))).isEqualTo(Settlement.COMMITTED);
    assertThat(tx(c, () -> c.creation.claimCommitOrder(b).binding()))
        .containsExactly(b.canonicalBytes());
    assertThat(
            tx(c, () -> c.creation.readOriginalBinding(b.operationId()))
                .orElseThrow()
                .canonicalBytes())
        .containsExactly(b.canonicalBytes());
    var changedActor = copy(b, UUID.randomUUID(), b.originalAuthorizationCapture());
    assertThatThrownBy(() -> tx(c, () -> c.creation.reserve(changedActor)))
        .isInstanceOf(IllegalArgumentException.class);
    var changedProvenance = copy(b, b.actorAccountId(), new byte[] {99});
    assertThatThrownBy(() -> tx(c, () -> c.creation.read(changedProvenance)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> owner(c, b, Owner.ACCOUNT, Outcome.DEFINITIVELY_ABORTED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                tx(
                    c,
                    () -> {
                      c.creation.recordOwnerReadback(
                          b,
                          new OwnerReadback(
                              Owner.ACCOUNT,
                              Outcome.COMMITTED,
                              b.creationOperationId(),
                              b.canonicalBytes(),
                              new byte[] {1}));
                      return null;
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    for (String sql :
        List.of(
            "UPDATE account_tenant_creation_authorization_fences SET actor_account_id = '"
                + UUID.randomUUID()
                + "'",
            "UPDATE account_tenant_creation_authorization_readbacks SET outcome = 'DEFINITIVELY_ABORTED'",
            "DELETE FROM account_tenant_creation_authorization_sources",
            "TRUNCATE account_tenant_creation_authorization_readbacks")) {
      assertThatThrownBy(() -> tx(c, () -> c.dsl.execute(sql)))
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void lateFailureRollsBackSourceTransitionWithOwnersRemainingImmutable() {
    Context c = context("67");
    var b = binding();
    var change = new SourceChange(UUID.randomUUID(), b.sources(), new byte[] {10});
    tx(
        c,
        () -> {
          c.creation.reserve(b);
          return c.creation.claimCommitOrder(b);
        });
    tx(c, () -> c.draft.requestSourceChange(change));
    owner(c, b, Owner.GAME_DESIGN, Outcome.COMMITTED);
    owner(c, b, Owner.ACCOUNT, Outcome.COMMITTED);
    assertThatThrownBy(
            () ->
                tx(
                    c,
                    () -> {
                      c.draft.markSourceCommitted(change);
                      throw new IllegalStateException("synthetic late owner-transaction failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(tx(c, () -> c.draft.readSourceChange(change).status())).isEqualTo("WAITING");
    assertThat(tx(c, () -> c.creation.readSettlement(b))).isEqualTo(Settlement.COMMITTED);
    tx(
        c,
        () -> {
          c.draft.markSourceCommitted(change);
          return null;
        });
  }

  @Test
  void sharedSourceLocksSerializeBothCompetingInterleavings() throws Exception {
    for (boolean commitFirst : List.of(true, false)) {
      Context c = context("67");
      var b = binding();
      var change = new SourceChange(UUID.randomUUID(), b.sources(), new byte[] {11});
      tx(c, () -> c.creation.reserve(b));
      CountDownLatch held = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch followerStarted = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var first =
            executor.submit(
                () ->
                    tx(
                        c,
                        () -> {
                          if (commitFirst) c.creation.claimCommitOrder(b);
                          else c.draft.requestSourceChange(change);
                          held.countDown();
                          await(release);
                          return null;
                        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        var second =
            executor.submit(
                () -> {
                  followerStarted.countDown();
                  if (commitFirst) return tx(c, () -> c.draft.requestSourceChange(change));
                  assertThatThrownBy(() -> tx(c, () -> c.creation.claimCommitOrder(b)))
                      .isInstanceOf(IllegalStateException.class);
                  return false;
                });
        assertThat(followerStarted.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
      } finally {
        release.countDown();
      }
      assertThat(tx(c, () -> c.creation.read(b).ordering()))
          .isEqualTo(commitFirst ? Ordering.COMMIT_ORDER : Ordering.REVOKE_ORDER);
      assertThat(tx(c, () -> c.draft.sourceMutationPermitted(change))).isFalse();
    }
  }

  @Test
  void migrationRetainsV66AndDraftImagesAndOriginalDraftBindingBytes() {
    Context c = context("66");
    var b = binding();
    var draft = draft(b.sources().getFirst());
    // These existing Draft-only methods do not call the common source-change/settlement engine.
    // Keep V66's populated image intact before migrating; current source interactions require V67.
    tx(
        c,
        () -> {
          c.draft.reserve(draft);
          c.draft.claimCommitOrder(draft);
          return null;
        });
    draftOwners(c, draft);
    tx(
        c,
        () -> {
          Record account =
              c.dsl.fetchOne(
                  "INSERT INTO accounts (username, email, password_hash)"
                      + " VALUES (?, ?, 'synthetic-password-verifier') RETURNING id, account_uuid, account_uuid_provenance",
                  "retained",
                  "retained@example.test");
          if (account == null) {
            throw new IllegalStateException("Retained Account fixture insert returned no row");
          }
          UUID operation = UUID.randomUUID();
          UUID request = UUID.randomUUID();
          c.dsl.execute(
              "INSERT INTO account_control_ui_issuance_operations"
                  + " (operation_id, request_id, account_uuid, account_id, account_provenance, profile, audience,"
                  + " request_digest_version, request_digest, authority_capture, authority_capture_digest,"
                  + " issuance_fence_capture, issuance_fence_digest, status)"
                  + " VALUES (?, ?, ?, ?, ?, 'control-ui', 'control-ui', 1, ?, ?, ?, ?, ?, 'PENDING')",
              operation,
              request,
              account.get("account_uuid", UUID.class),
              account.get("id", Long.class),
              account.get("account_uuid_provenance", String.class),
              new byte[32],
              new byte[] {21},
              new byte[32],
              new byte[] {22},
              new byte[32]);
          c.dsl.execute(
              "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', token_hash = ?,"
                  + " response_digest = ?, issued_at = '2030-01-01T00:00:00Z', expires_at = '2030-01-01T00:10:00Z'"
                  + " WHERE operation_id = ?",
              "a".repeat(64),
              new byte[32],
              operation);
          c.dsl.execute(
              "INSERT INTO account_control_ui_issuance_response_envelopes"
                  + " (operation_id, request_id, account_uuid, account_id, account_provenance, profile, audience,"
                  + " request_digest_version, request_digest, token_hash, response_digest, authority_capture_digest,"
                  + " issuance_fence_digest, issued_at, expires_at, format_version, key_id, purpose, nonce, ciphertext)"
                  + " SELECT operation_id, request_id, account_uuid, account_id, account_provenance, profile, audience,"
                  + " request_digest_version, request_digest, token_hash, response_digest, authority_capture_digest,"
                  + " issuance_fence_digest, issued_at, expires_at, 1, 'fixture', 'CONTROL_UI_RESPONSE', ?, ?"
                  + " FROM account_control_ui_issuance_operations WHERE operation_id = ?",
              new byte[12],
              new byte[16],
              operation);
          return null;
        });
    Map<String, List<String>> images = retainedImages(c);
    migration(c.source, c.schema, "67").migrate();
    assertThat(retainedImages(c)).isEqualTo(images);
    assertThat(tx(c, () -> c.draft.read(draft).binding())).containsExactly(draft.canonicalBytes());
    assertThat(tx(c, () -> c.draft.readSettlement(draft)))
        .isEqualTo(Settlement.FAILED_NONPUBLICATION);
    tx(c, () -> c.creation.reserve(b));
    assertThat(retainedImages(c).get("account_control_ui_issuance_operations"))
        .isEqualTo(images.get("account_control_ui_issuance_operations"));
  }

  private Map<String, List<String>> retainedImages(Context c) {
    Map<String, List<String>> images = new LinkedHashMap<>();
    for (String table :
        List.of(
            "account_control_ui_issuance_operations",
            "account_control_ui_issuance_response_envelopes",
            "account_draft_authorization_fences",
            "account_draft_authorization_sources",
            "account_draft_authorization_owner_readbacks",
            "account_draft_authorization_source_locks",
            "account_draft_authorization_source_changes",
            "account_draft_authorization_changed_scopes")) {
      images.put(
          table,
          tx(
              c,
              () ->
                  c.dsl
                      .fetch(
                          "SELECT to_jsonb(t)::text AS image FROM "
                              + table
                              + " t ORDER BY to_jsonb(t)::text")
                      .getValues("image", String.class)));
    }
    return images;
  }

  private void owner(
      Context c, TenantCreationAuthorizationFenceBinding b, Owner owner, Outcome outcome) {
    tx(
        c,
        () -> {
          c.creation.recordOwnerReadback(
              b,
              new OwnerReadback(
                  owner,
                  outcome,
                  owner == Owner.GAME_DESIGN ? b.creationOperationId() : b.operationId(),
                  b.canonicalBytes(),
                  new byte[] {(byte) owner.ordinal(), (byte) outcome.ordinal()}));
          return null;
        });
  }

  private void draftOwners(Context c, DraftAuthorizationFenceBinding b) {
    for (var owner : DraftAuthorizationFenceBinding.Owner.values()) {
      tx(
          c,
          () -> {
            c.draft.recordOwnerReadback(
                b,
                new DraftAuthorizationFenceBinding.OwnerReadback(
                    owner,
                    DraftAuthorizationFenceBinding.Outcome.DEFINITIVELY_ABORTED,
                    b.operationId(),
                    b.commitId(),
                    b.fenceId(),
                    b.inputDigest(),
                    b.canonicalBytes(),
                    new byte[] {12}));
            return null;
          });
    }
  }

  private TenantCreationAuthorizationFenceBinding binding() {
    UUID actor = UUID.randomUUID();
    return bindingWithSources(
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                actor.toString(),
                "1",
                "1",
                "fixture-stream/" + actor,
                "0",
                new byte[] {1})));
  }

  private TenantCreationAuthorizationFenceBinding bindingWithSources(List<SourceEvidence> sources) {
    UUID request = UUID.randomUUID();
    return new TenantCreationAuthorizationFenceBinding(
        UUID.randomUUID(),
        request,
        UUID.randomUUID(),
        UUID.fromString(sources.getFirst().scopeId()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "firemud",
        "fixture-key",
        "Fixture",
        null,
        GameTenantCreationDigest.requestDigest("firemud", request, "fixture-key", "Fixture", null),
        new byte[] {2},
        sources);
  }

  private TenantCreationAuthorizationFenceBinding copy(
      TenantCreationAuthorizationFenceBinding b, UUID actor, byte[] provenance) {
    return new TenantCreationAuthorizationFenceBinding(
        b.operationId(),
        b.requestId(),
        b.fenceId(),
        actor,
        b.tenantId(),
        b.creationOperationId(),
        b.targetNamespace(),
        b.sourceGameTenantKey(),
        b.name(),
        b.description(),
        b.creationRequestDigest(),
        provenance,
        b.sources());
  }

  private DraftAuthorizationFenceBinding draft(SourceEvidence source) {
    var complete =
        DraftCommitBinding.create(
            new TargetProof(
                UUID.randomUUID(), UUID.randomUUID(), 1, "fixture", 2, "fixture", "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "fixture-base",
            List.of(
                new RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "fixture-payload")),
            List.of(
                new AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "fixture",
                    "aggregate",
                    "fixture",
                    "1")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        complete.requestId(),
        complete.commitId(),
        UUID.randomUUID(),
        UUID.fromString(source.scopeId()),
        complete.target().canonicalTenantId(),
        complete.target().canonicalVersionId(),
        complete.baseCommitId(),
        "1",
        complete.canonicalBytes(),
        complete.canonicalBytes(),
        complete.digest(),
        List.of(source));
  }

  private Context context(String target) {
    String schema = "creation_fence_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    migration(source, schema, target).migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    return new Context(
        source,
        schema,
        dsl,
        new DraftAuthorizationFenceRepository(dsl),
        new TenantCreationAuthorizationFenceRepository(dsl),
        transaction);
  }

  private Flyway migration(DriverManagerDataSource source, String schema, String target) {
    return Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  private <T> T tx(Context c, Supplier<T> work) {
    return c.transaction.execute(status -> work.get());
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("Fixture coordination timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext dsl,
      DraftAuthorizationFenceRepository draft,
      TenantCreationAuthorizationFenceRepository creation,
      TransactionTemplate transaction) {}
}
