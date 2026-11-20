package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.*;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual Flyway/PostgreSQL source selection over committed demo and hosting owners. Enrollment and
 * Stripe transport are controlled fixture inputs. No triggers are bypassed and no clock is changed;
 * this does not qualify authenticated producers, live Stripe, public registration or admission.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantAuthoritySourceSelectionPostgresIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void mixedOriginalOwnersKeepTenantMutationAndExactRetryFenced() {
    for (Outcome worldOutcome : List.of(Outcome.COMMITTED, Outcome.DEFINITIVELY_ABORTED)) {
      Context c = context();
      var f = c.fixture();
      var prior = c.provisionDemo(null, true);
      var original = c.select().checkpoint();
      var complete =
          DraftCommitBinding.create(
              new DraftCommitBinding.TargetProof(
                  UUID.randomUUID(),
                  UUID.randomUUID(),
                  1,
                  "test-tenant",
                  2,
                  "test-tenant",
                  "NEW_GAME_ROW"),
              UUID.randomUUID(),
              UUID.randomUUID(),
              "test/base",
              List.of(
                  new DraftCommitBinding.RevisionPayload(
                      "0",
                      UUID.randomUUID(),
                      DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                      "test-payload")),
              List.of(
                  new DraftCommitBinding.AffectedUnit(
                      DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                      "region",
                      "test-region",
                      "aggregate",
                      "test-region",
                      "0")));
      // Controlled source-storage capture only: this is not authenticated creator authorization.
      var holder =
          new DraftAuthorizationFenceBinding(
              UUID.randomUUID(),
              complete.requestId(),
              complete.commitId(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              complete.target().canonicalTenantId(),
              complete.target().canonicalVersionId(),
              complete.baseCommitId(),
              "0",
              complete.canonicalBytes(),
              complete.canonicalBytes(),
              complete.digest(),
              List.of(
                  new SourceEvidence(
                      DraftAuthorizationFenceBinding.SourceKind.TENANT,
                      f.tenantId().toString(),
                      Long.toString(original.tenantAuthorityGeneration()),
                      Long.toString(original.tenantAuthoritySourceVersion()),
                      original.outboxStreamKey(),
                      Long.toString(original.outboxSequence()),
                      original.payload())));
      var fences = new DraftAuthorizationFenceRepository(f.dsl());
      f.transaction()
          .executeWithoutResult(
              status -> {
                fences.reserve(holder);
                fences.claimCommitOrder(holder);
              });
      // Owner terminal evidence is stipulated, not an authenticated publisher.
      for (Owner owner : List.of(Owner.WORLD, Owner.GAME_DESIGN)) {
        Outcome outcome =
            owner == Owner.WORLD
                ? worldOutcome
                : worldOutcome == Outcome.COMMITTED
                    ? Outcome.DEFINITIVELY_ABORTED
                    : Outcome.COMMITTED;
        var readback =
            new OwnerReadback(
                owner,
                outcome,
                holder.operationId(),
                holder.commitId(),
                holder.fenceId(),
                holder.inputDigest(),
                holder.canonicalBytes(),
                new byte[] {(byte) owner.ordinal(), (byte) outcome.ordinal()});
        f.transaction()
            .executeWithoutResult(status -> fences.recordOwnerReadback(holder, readback));
      }
      var retained = tenantSnapshot(f);
      UUID requestId = UUID.randomUUID();
      for (int attempt = 0; attempt < 2; attempt++) {
        RuntimeException failure =
            assertThrows(RuntimeException.class, () -> c.provisionDemo(prior, false, requestId));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState()).isEqualTo("55P03");
        assertThat(cause)
            .hasMessageContaining(
                "Required creator source is held until every original owner settles");
        assertThat(tenantSnapshot(f)).isEqualTo(retained);
      }
      assertThat(
              f.dsl()
                  .fetchSingle(
                      "SELECT account_draft_authorization_is_settled(?)", holder.operationId())
                  .get(0, Boolean.class))
          .isFalse();
      assertThat(c.select().matches(original)).isTrue();
      assertThat(f.transport().reads()).isZero();
    }
  }

  private static Map<String, List<Map<String, Object>>> tenantSnapshot(
      CanonicalHostingSubscriptionTestFixture.Fixture f) {
    Map<String, List<Map<String, Object>>> rows = new java.util.LinkedHashMap<>();
    for (String table :
        List.of(
            "account_authority_generations",
            "account_tenant_authority_source_records",
            "account_authority_outbox_events",
            "account_authority_outbox_streams",
            "account_tenant_entitlement_outbox_events",
            "account_tenant_entitlement_outbox_streams",
            "account_draft_authorization_fences",
            "account_draft_authorization_sources",
            "account_draft_authorization_owner_readbacks")) {
      rows.put(
          table,
          f.dsl()
              .fetch(
                  "SELECT to_jsonb(t)::text AS evidence, xmin::text AS row_version FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text")
              .intoMaps());
    }
    return rows;
  }

  @Test
  void retainedPendingSubscriptionWithoutCommittedTenantSourceIsUnavailable() {
    Context c = context();
    var f = c.fixture();
    assertThat(f.intents().findCurrentByTenant(f.tenantId())).isPresent();
    assertThatThrownBy(c::select).hasMessageContaining("Current tenant source is absent");
    assertThat(f.transport().reads()).isZero();
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT count(*) FROM account_tenant_authority_source_records WHERE tenant_uuid = ?",
                            f.tenantId()))
                .get(0, Long.class))
        .isZero();
  }

  @Test
  void demoSelectionUsesExactCommittedBindingWhilePaidConfigurationIsRetained() {
    Context c = context();
    var f = c.fixture();
    DemoTenantEntitlementSnapshot first = c.provisionDemo(null, true);
    SourceSelection selected = c.select();
    assertThat(selected.kind()).isEqualTo(SourceKind.NON_PAID_DEMO);
    assertThat(selected.checkpoint().tenantBillingEventId()).isEqualTo(first.eventId());
    assertThat(selected.checkpoint().tenantBillingEventDigest()).isEqualTo(first.eventDigest());
    assertThat(selected.checkpoint().eventId()).isEqualTo(first.tenantAuthorityEventId());
    assertThat(selected.checkpoint().eventDigest()).isEqualTo(first.tenantAuthorityEventDigest());
    assertCurrentCheckpoint(c, selected);
    assertThat(f.intents().findCurrentByTenant(f.tenantId()).orElseThrow().status())
        .isEqualTo("pending");
    assertThat(f.transport().reads()).isZero();

    // A genuine owner mutation changes the source; the earlier selected identity cannot follow it.
    DemoTenantEntitlementSnapshot second = c.provisionDemo(first, false);
    SourceSelection current = c.select();
    assertThat(current.kind()).isEqualTo(SourceKind.NON_PAID_DEMO);
    assertThat(selected.matches(current.checkpoint())).isFalse();
    assertThat(current.checkpoint().tenantBillingSequence())
        .isEqualTo(first.tenantBillingSequence() + 1)
        .isEqualTo(second.tenantBillingSequence());
    assertThatThrownBy(() -> f.transaction().execute(status -> c.demos().revalidate(first)))
        .hasMessageContaining("changed after");
    assertThat(
            f.transaction()
                .execute(status -> c.demos().readCurrent(f.tenantId()))
                .allowPublicJoin())
        .isFalse();
    assertCurrentCheckpoint(c, current);
    assertThat(f.transport().reads()).isZero();
  }

  @Test
  void paidCommitReplacesHeadWithoutDeletingOrSelectingTheRetainedDemoFamily() {
    Context c = context();
    var f = c.fixture();
    DemoTenantEntitlementSnapshot demo = c.provisionDemo(null, true);
    SourceSelection oldSelection = c.select();
    var retainedDemoRow =
        Objects.requireNonNull(
            f.dsl()
                .fetchOne(
                    "SELECT row_to_json(e)::text FROM account_demo_tenant_entitlements e WHERE tenant_uuid = ?",
                    f.tenantId()));
    assertThat(retainedDemoRow).isNotNull();
    String retainedDemo = retainedDemoRow.get(0, String.class);
    var retainedDemoEventRow =
        Objects.requireNonNull(
            f.dsl()
                .fetchOne(
                    "SELECT payload FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = ?",
                    f.tenantId(),
                    demo.tenantBillingSequence()));
    assertThat(retainedDemoEventRow).isNotNull();
    byte[] retainedDemoEvent = retainedDemoEventRow.get(0, byte[].class);

    UUID paidRequest = c.activatePaid();
    int providerReads = f.transport().reads();
    SourceSelection paid = c.select();
    assertThat(paid.kind()).isEqualTo(SourceKind.PAID_HOSTING);
    assertThat(paid.checkpoint().requestId()).isEqualTo(paidRequest);
    assertThat(paid.checkpoint().tenantBillingSequence())
        .isEqualTo(demo.tenantBillingSequence() + 1);
    assertThat(oldSelection.matches(paid.checkpoint())).isFalse();
    assertCurrentCheckpoint(c, paid);
    // Source selection is only routing data; it does not make its own provider observation.
    assertThat(f.transport().reads()).isEqualTo(providerReads);
    var actualReceipt = f.service().readCurrent(f.tenantId());
    assertThat(paid.matches(actualReceipt.tenantAuthorityEvent())).isTrue();
    assertThat(paid.checkpoint().tenantBillingEventId()).isEqualTo(actualReceipt.billingEventId());
    assertThat(paid.checkpoint().tenantBillingEventDigest())
        .isEqualTo(actualReceipt.billingEventDigest());
    assertThat(f.transport().reads()).isEqualTo(providerReads + 1);

    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT row_to_json(e)::text FROM account_demo_tenant_entitlements e WHERE tenant_uuid = ?",
                            f.tenantId()))
                .get(0, String.class))
        .isEqualTo(retainedDemo);
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT payload FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = ?",
                            f.tenantId(),
                            demo.tenantBillingSequence()))
                .get(0, byte[].class))
        .isEqualTo(retainedDemoEvent);
    assertThat(f.intents().findCurrentByTenant(f.tenantId()).orElseThrow().status())
        .isEqualTo("active");
    assertThatThrownBy(() -> f.transaction().execute(status -> c.demos().readCurrent(f.tenantId())))
        .hasMessageContaining("stale against Account's shared tenant authority generation");
    assertThat(c.select().kind()).isEqualTo(SourceKind.PAID_HOSTING);
  }

  @Test
  void immutableBillingReceiptAndHeadGuardsRejectMalformedOrMismatchingReplacement() {
    Context c = context();
    var f = c.fixture();
    c.activatePaid();
    SourceSelection original = c.select();

    // These corruptions cannot become committed source states under the production guards.
    assertSqlState23514(
        () ->
            f.dsl()
                .execute(
                    "UPDATE account_tenant_entitlement_outbox_events SET payload = ? WHERE tenant_uuid = ?",
                    "{\"schemaVersion\":\"unknown-source/v1\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    f.tenantId()));
    assertSqlState23514(
        () ->
            f.dsl()
                .execute(
                    "UPDATE account_tenant_authority_source_records SET tenant_billing_event_digest = ? WHERE tenant_uuid = ?",
                    "sha256:" + "f".repeat(64),
                    f.tenantId()));
    assertSqlState23514(
        () ->
            f.dsl()
                .execute(
                    "UPDATE subscription_lifecycle_receipts SET receipt_payload = ? WHERE tenant_id = ?",
                    new byte[] {'{', '}'},
                    f.tenantId()));

    SourceSelection unchanged = c.select();
    assertThat(unchanged.kind()).isEqualTo(SourceKind.PAID_HOSTING);
    assertThat(original.matches(unchanged.checkpoint())).isTrue();
    assertCurrentCheckpoint(c, unchanged);
  }

  private static void assertCurrentCheckpoint(Context c, SourceSelection selected) {
    var f = c.fixture();
    var head =
        f.dsl()
            .fetchOne(
                "SELECT * FROM account_tenant_authority_source_records WHERE tenant_uuid = ?",
                f.tenantId());
    assertThat(head).isNotNull();
    assertThat(selected.checkpoint().eventId().toString())
        .isEqualTo(head.get("last_event_id", String.class));
    assertThat(selected.checkpoint().eventDigest())
        .isEqualTo(head.get("last_event_digest", String.class));
    assertThat(selected.checkpoint().tenantAuthorityGeneration())
        .isEqualTo(head.get("current_generation", Long.class));
    assertThat(selected.checkpoint().tenantAuthoritySourceVersion())
        .isEqualTo(head.get("current_source_version", Long.class));
    assertThat(selected.checkpoint().requestId())
        .isEqualTo(head.get("last_request_id", UUID.class));
    assertThat(selected.checkpoint().tenantBillingSequence())
        .isEqualTo(head.get("tenant_billing_sequence", Long.class));
    var checkpoint =
        Objects.requireNonNull(
            f.transaction()
                .execute(
                    status ->
                        new AccountAuthorityOutboxRepository(f.dsl())
                            .readCheckpoint(TenantAuthorityEventV1Codec.streamKey(f.tenantId()))
                            .orElseThrow()));
    assertThat(selected.checkpoint().outboxSequence()).isEqualTo(checkpoint.outboxSequence());
    assertThat(selected.checkpoint().eventId().toString()).isEqualTo(checkpoint.sourceEventId());
    assertThat(selected.checkpoint().eventDigest()).isEqualTo(checkpoint.sourceEventDigest());
    var billing =
        f.dsl()
            .fetchOne(
                "SELECT e.event_id, e.event_digest, s.last_sequence FROM account_tenant_entitlement_outbox_events e "
                    + "JOIN account_tenant_entitlement_outbox_streams s USING (tenant_uuid) "
                    + "WHERE e.tenant_uuid = ? AND e.tenant_billing_sequence = ?",
                f.tenantId(),
                selected.checkpoint().tenantBillingSequence());
    assertThat(billing).isNotNull();
    assertThat(selected.checkpoint().tenantBillingSequence())
        .isEqualTo(billing.get("last_sequence", Long.class));
    assertThat(selected.checkpoint().tenantBillingEventId())
        .isEqualTo(billing.get("event_id", UUID.class));
    assertThat(selected.checkpoint().tenantBillingEventDigest())
        .isEqualTo(billing.get("event_digest", String.class));
  }

  private static void assertSqlState23514(Runnable operation) {
    RuntimeException failure = assertThrows(RuntimeException.class, operation::run);
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sql) {
        assertThat(sql.getSQLState()).isEqualTo("23514");
        return;
      }
      current = current.getCause();
    }
    throw new AssertionError("Expected PostgreSQL guard refusal", failure);
  }

  private static Context context() {
    String schema = "source_selection_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .load()
        .migrate();
    var f = CanonicalHostingSubscriptionTestFixture.create(source);
    var identities = new FreshTenantIdentityAssociationRepository(f.dsl(), "prod");
    var generations = new AccountAuthorityGenerationRepository(f.dsl());
    var billing = new AccountTenantEntitlementOutboxRepository(f.dsl());
    var events =
        new AccountTenantAuthorityEventRepository(
            f.dsl(),
            new AccountAuthorityOutboxRepository(f.dsl()),
            billing,
            identities,
            generations);
    var demos =
        new AccountDemoTenantEntitlementRepository(
            f.dsl(), identities, generations, billing, events);
    return new Context(f, events, demos);
  }

  private record Context(
      CanonicalHostingSubscriptionTestFixture.Fixture fixture,
      AccountTenantAuthorityEventRepository events,
      AccountDemoTenantEntitlementRepository demos) {
    private SourceSelection select() {
      return Objects.requireNonNull(
          fixture
              .transaction()
              .execute(status -> events.readCurrentSourceSelection(fixture.tenantId())));
    }

    private DemoTenantEntitlementSnapshot provisionDemo(
        DemoTenantEntitlementSnapshot prior, boolean publicJoin) {
      return provisionDemo(prior, publicJoin, UUID.randomUUID());
    }

    private DemoTenantEntitlementSnapshot provisionDemo(
        DemoTenantEntitlementSnapshot prior, boolean publicJoin, UUID requestId) {
      return Objects.requireNonNull(
          fixture
              .transaction()
              .execute(
                  status -> {
                    var source =
                        new FreshTenantIdentityAssociationRepository(fixture.dsl(), "prod")
                            .read(fixture.tenantId())
                            .orElseThrow();
                    return demos.provision(
                        new DemoTenantEntitlementRequest(
                            requestId,
                            fixture.tenantId(),
                            source.creationRequestId(),
                            source.requestDigest(),
                            prior == null ? null : prior.entitlementVersion(),
                            prior == null ? null : prior.tenantAuthorityGeneration(),
                            prior == null ? null : prior.tenantAuthoritySourceVersion(),
                            true,
                            publicJoin,
                            true,
                            true,
                            new DemoTenantEntitlementRequest.Quotas(3, 1, 1024)),
                        source);
                  }));
    }

    private UUID activatePaid() {
      return fixture.completeFirstInvoice().requestId();
    }
  }
}
