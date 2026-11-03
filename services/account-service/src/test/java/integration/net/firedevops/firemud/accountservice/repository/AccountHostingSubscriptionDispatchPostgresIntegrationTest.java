package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionProvisioner;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionProvisioningService;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Actual owner/SDK composition with controlled enrollment publications and provider transport. */
@Testcontainers(disabledWithoutDocker = true)
class AccountHostingSubscriptionDispatchPostgresIntegrationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong GAME_ROWS = new AtomicLong(90000);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void upgradeRetainsPriorAttemptOneAndAddsOnlyNonAuthorizingUnknownGuard() {
    Fixture f = fixture("1030");
    ProvisioningClaim claim = claim(f, 1);
    var before = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "accounts",
            "subscription_create_operations",
            "subscription",
            "subscription_provisioning_work",
            "subscription_provisioning_attempts")) {
      before.put(
          table,
          f.dsl()
              .fetch("SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM " + table + " t")
              .intoMaps());
    }
    migrate(f.source(), f.schema(), "1032");
    for (var entry : before.entrySet()) {
      assertThat(
              f.dsl()
                  .fetch(
                      "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t")
                  .intoMaps())
          .isEqualTo(entry.getValue());
    }
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT classification FROM subscription_provider_dispatch_claim"))
                .get(0, String.class))
        .isEqualTo("PREEXISTING_UNKNOWN");
    var prepared = f.tx().execute(s -> f.owner().prepareProviderDispatch(claim, UUID.randomUUID()));
    var read = f.tx().execute(s -> f.owner().readProviderDispatch(prepared));
    assertThat(read.readyForProviderIo()).isTrue();
    assertThat(read.initialCallAllowed()).isFalse();
  }

  @Test
  void onlyOneFreshInsertionCarriesInitialPermissionAndIndependentReadbackIsMandatory()
      throws Exception {
    Fixture f = fixture("1032");
    ProvisioningClaim claim = claim(f, 1);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return prepareAndRead(f, claim);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return prepareAndRead(f, claim);
              });
      start.countDown();
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(ProviderDispatch::initialCallAllowed)
          .containsExactlyInAnyOrder(true, false);
      assertThat(prepareAndRead(f, claim).initialCallAllowed()).isFalse();
    }
    assertThat(ProviderDispatch.class.getConstructors()).isEmpty();
  }

  @Test
  void uncommittedSameTransactionReadbackCannotProduceProviderPermission() {
    Fixture f = fixture("1032");
    var claim = claim(f, 1);
    assertThatThrownBy(
            () ->
                f.tx()
                    .execute(
                        s -> {
                          var uncommitted =
                              f.owner().prepareProviderDispatch(claim, UUID.randomUUID());
                          return f.owner().readProviderDispatch(uncommitted);
                        }))
        .hasMessageContaining("new transaction");
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
  }

  @Test
  void recoveredAttemptWithNoPreviousDispatchStillCannotCreateAndExpiredLeaseRefusesReadback() {
    Fixture f = fixture("1032");
    var first = claim(f, 1);
    f.tx().executeWithoutResult(s -> f.owner().deferUnresolved(first));
    f.clock().advance(Duration.ofSeconds(2));
    var second = claim(f, first.subscriptionRowVersion());
    var prepared =
        f.tx().execute(s -> f.owner().prepareProviderDispatch(second, UUID.randomUUID()));
    assertThat(f.tx().execute(s -> f.owner().readProviderDispatch(prepared)).initialCallAllowed())
        .isFalse();
    f.clock().advance(Duration.ofSeconds(10));
    assertThatThrownBy(() -> f.tx().execute(s -> f.owner().readProviderDispatch(prepared)))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void dispatchIdentityBindingClassificationAndTimeCannotChangeOrDisappear() {
    Fixture f = fixture("1032");
    prepareAndRead(f, claim(f, 1));
    var before = f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps();
    for (String command :
        List.of(
            "UPDATE subscription_provider_dispatch_claim SET classification='PREEXISTING_UNKNOWN'",
            "UPDATE subscription_provider_dispatch_claim SET recorded_at=recorded_at+interval '1 second'",
            "UPDATE subscription_provider_dispatch_claim SET dispatch_id='"
                + UUID.randomUUID()
                + "'",
            "DELETE FROM subscription_provider_dispatch_claim",
            "TRUNCATE subscription_provider_dispatch_claim")) {
      assertThatThrownBy(() -> f.dsl().execute(command))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
    assertThat(f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps())
        .isEqualTo(before);
  }

  @Test
  void realInitialSdkCreateFlowsThroughExistingLifecycleCommitAndIndependentCurrentRead() {
    Fixture f = fixture("1032");
    var result =
        (AccountHostingSubscriptionProvisioningService.Committed)
            service(f).provision(claim(f, 1), UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.current().current().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(result.current().current().subscriptionId())
        .isEqualTo(f.creation().subscriptionId());
    assertThat(f.transport().creates).isEqualTo(1);
    assertThat(f.transport().subscriptionReads).isGreaterThanOrEqualTo(3);
  }

  @Test
  void lostCreateResponseRecoversSameResourceWithoutRecreatingAndRetainsItsOriginalIdentity() {
    Fixture f = fixture("1032");
    f.transport().loseCreateResponse = true;
    var first = claim(f, 1);
    assertThat(service(f).provision(first, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(AccountHostingSubscriptionProvisioningService.Pending.class);
    assertThat(f.transport().creates).isEqualTo(1);
    f.clock().advance(Duration.ofSeconds(2));
    var second = claim(f, first.subscriptionRowVersion());
    var result =
        (AccountHostingSubscriptionProvisioningService.Committed)
            service(f).provision(second, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.current().current().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(f.transport().creates).isEqualTo(1);
  }

  private static ProviderDispatch prepareAndRead(Fixture f, ProvisioningClaim claim) {
    var prepared = f.tx().execute(s -> f.owner().prepareProviderDispatch(claim, UUID.randomUUID()));
    assertThat(prepared.readyForProviderIo()).isFalse();
    assertThat(prepared.initialCallAllowed()).isFalse();
    return f.tx().execute(s -> f.owner().readProviderDispatch(prepared));
  }

  private static ProvisioningClaim claim(Fixture f, long version) {
    return Objects.requireNonNull(
        f.tx()
            .execute(
                s ->
                    f.owner().claim(f.creation().requestId(), version, UUID.randomUUID()).claim()));
  }

  private static AccountHostingSubscriptionProvisioningService service(Fixture f) {
    var stripe = new com.stripe.StripeClient(f.transport());
    var lifecycle =
        new AccountHostingSubscriptionLifecycleService(
            f.owner(),
            new StripeHostingSubscriptionObserver(stripe, f.clock()),
            new DataSourceTransactionManager(f.source()));
    return new AccountHostingSubscriptionProvisioningService(
        f.owner(),
        new StripeHostingSubscriptionProvisioner(stripe, f.clock(), false),
        lifecycle,
        new DataSourceTransactionManager(f.source()));
  }

  private static Fixture fixture(String target) {
    String schema = "hosting_dispatch_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    migrate(source, schema, target);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setTimeout(10);
    var catalog =
        new AccountHostingPlanCatalog(
            List.of(
                new AccountHostingPlanCatalog.ConfiguredPlan(
                    "hosting", "price_controlled", "1", "100", "2", "1048576")));
    var intents = new AccountSubscriptionIntentRepository(dsl, catalog);
    long row = GAME_ROWS.incrementAndGet();
    UUID accountUuid = enroll(dsl, transaction, row);
    UUID tenantId = UUID.randomUUID();
    UUID tenantCreateRequest = UUID.randomUUID();
    UUID tenantCreateOperation = UUID.randomUUID();
    String tenantKey = "hosting-fixture-game-" + row;
    String tenantRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", tenantCreateRequest, tenantKey, "Controlled hosting", "Storage only");
    var tenantSource =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            tenantCreateRequest,
            tenantCreateOperation,
            tenantRequestDigest,
            tenantId,
            row,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod",
                tenantCreateRequest,
                tenantCreateOperation,
                tenantRequestDigest,
                tenantId,
                row,
                tenantKey,
                "NEW_GAME_ROW"));
    in(
        transaction,
        () -> {
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenantSource);
          return new AccountAuthorityGenerationRepository(dsl).initializeTenantIfAbsent(tenantId);
        });

    UUID authorizationOperation = UUID.randomUUID();
    var creator =
        new FreshTenantCreatorEvidence(
            1,
            tenantSource,
            accountUuid,
            authorizationOperation,
            DIGEST,
            FreshTenantCreatorDigest.evidenceDigest(
                1, tenantSource, accountUuid, authorizationOperation, DIGEST));
    var owner =
        in(
            transaction,
            () ->
                new AccountInitialTenantBillingOwnerRepository(dsl)
                    .establishInitial(
                        new AccountInitialTenantBillingOwnerRepository.InitialRequest(
                            UUID.randomUUID(), creator, UUID.randomUUID(), 1, true)));

    var customers = new AccountBillingCustomerRepository(dsl);
    var pendingCustomer =
        in(
            transaction,
            () ->
                customers
                    .retainIntent(accountUuid, UUID.randomUUID(), UUID.randomUUID())
                    .customer());
    var customer =
        in(
            transaction,
            () ->
                customers.recordObservation(
                    accountUuid,
                    pendingCustomer.customerProvisioningRequestId(),
                    1,
                    UUID.randomUUID(),
                    AccountBillingCustomerRecord.Observation.CONFIRMED,
                    "cus_" + row,
                    DIGEST));
    var instrument =
        in(
            transaction,
            () ->
                new AccountPaymentInstrumentRepository(dsl)
                    .retainAttachment(
                        new AccountPaymentInstrumentRepository.Attachment(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            accountUuid,
                            customer.customerProvisioningRequestId(),
                            customer.providerCustomerId(),
                            "pm_" + row,
                            AccountPaymentInstrumentRecord.State.ATTACHABLE,
                            new AccountPaymentInstrumentRecord.DisplayMetadata(
                                "visa", "4242", 12, 2030),
                            DIGEST)));
    var creation =
        in(
            transaction,
            () ->
                intents.retainPending(
                    new AccountSubscriptionCreateRequest(
                        UUID.randomUUID(),
                        tenantId,
                        new OwnerFence(accountUuid, owner.ownerVersion(), owner.ownerGeneration()),
                        "hosting",
                        instrument.instrumentUuid(),
                        1,
                        new ProducerEvidence(accountUuid, UUID.randomUUID(), DIGEST),
                        null)));

    var clock = new MutableClock(creation.createdAt().plusSeconds(1));
    var lifecycleOwner =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl,
            intents,
            clock,
            new AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits(
                4, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
            new AccountHostingSubscriptionLifecyclePolicy(
                Duration.ofSeconds(15),
                new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                    Duration.ofSeconds(60), Duration.ofSeconds(120))),
            "prod",
            false);
    var transport = new Transport(creation, instrument.providerPaymentMethodId(), clock);
    return new Fixture(
        source, schema, dsl, transaction, creation, lifecycleOwner, clock, transport);
  }

  private static UUID enroll(DSLContext dsl, TransactionTemplate tx, long suffix) {
    return in(
        tx,
        () -> {
          // Controlled publications stipulate delivery; the actual fresh insertion/certification
          // owners
          // run normally. These fixtures do not qualify production publisher or protected-mount
          // custody.
          for (Category category : Category.values()) {
            UUID publication = UUID.randomUUID(),
                policy = UUID.randomUUID(),
                closure = UUID.randomUUID();
            String identity = AccountFreshEnrollmentPolicySource.sourceIdentity(category);
            byte[] bytes;
            try {
              bytes =
                  Rfc8785CanonicalJson.canonicalizeUtf8(
                      JsonMapper.builder()
                          .build()
                          .writeValueAsString(
                              Map.of(
                                  "schemaVersion",
                                  "account-fresh-enrollment-policy/v1",
                                  "category",
                                  category.storageValue(),
                                  "sourceIdentity",
                                  identity,
                                  "publicationId",
                                  publication.toString(),
                                  "revision",
                                  "1",
                                  "policyId",
                                  policy.toString(),
                                  "policyRule",
                                  "CERTIFY_NEW_ACTIVE_ACCOUNT",
                                  "closureId",
                                  closure.toString(),
                                  "closureRule",
                                  "EXACT_FRESH_INSERT_ONLY")));
            } catch (java.io.IOException failure) {
              throw new IllegalStateException(failure);
            }
            dsl.execute(
                "INSERT INTO account_fresh_enrollment_policy_publications "
                    + "(publication_id, category, revision, policy_id, closure_id, policy_rule, closure_rule, source_identity, source_root, publication_digest, publication_bytes) "
                    + "VALUES (?, ?, 1, ?, ?, 'CERTIFY_NEW_ACTIVE_ACCOUNT', 'EXACT_FRESH_INSERT_ONLY', ?, ?, ?, ?)",
                publication,
                category.storageValue(),
                policy,
                closure,
                identity,
                "/controlled-test/owner-protected-mount",
                AccountFreshEnrollmentPolicySource.digest(bytes),
                bytes);
            dsl.execute(
                "INSERT INTO account_fresh_enrollment_policy_current (category, publication_id, revision) VALUES (?, ?, 1)",
                category.storageValue(),
                publication);
          }
          var generations = new AccountAuthorityGenerationRepository(dsl);
          var outbox = new AccountAuthorityOutboxRepository(dsl);
          var restrictions =
              new AccountPlatformRestrictionOperationRepository(
                  dsl,
                  generations,
                  outbox,
                  new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox),
                  new AccountPlatformRestrictionBirthRepository(dsl));
          var result =
              new AccountFreshEnrollmentRepository(
                      dsl,
                      new AccountRepository(dsl),
                      new AccountFreshEnrollmentPolicyRepository(dsl),
                      restrictions)
                  .enroll(
                      new AccountFreshEnrollmentRequest(
                          UUID.randomUUID(),
                          "hosting-" + suffix,
                          "hosting-" + suffix + "@example.test",
                          "controlled-password-verifier",
                          true,
                          "PASSWORD,EMAIL_OTP"));
          return result.accountUuid();
        });
  }

  private static <T> T in(TransactionTemplate tx, java.util.function.Supplier<T> action) {
    return Objects.requireNonNull(tx.execute(s -> action.get()));
  }

  private static void migrate(DriverManagerDataSource source, String schema, String target) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .target(target)
        .load()
        .migrate();
  }

  private record Fixture(
      DriverManagerDataSource source,
      String schema,
      DSLContext dsl,
      TransactionTemplate tx,
      AccountSubscriptionCreationRecord creation,
      AccountHostingSubscriptionLifecycleRepository owner,
      MutableClock clock,
      Transport transport) {}

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class Transport implements StripeResponseGetter {
    private final AccountSubscriptionCreationRecord creation;
    private final String paymentMethod;
    private final Clock clock;
    private boolean exists;
    private boolean loseCreateResponse;
    private int creates;
    private int subscriptionReads;

    private Transport(
        AccountSubscriptionCreationRecord creation, String paymentMethod, Clock clock) {
      this.creation = creation;
      this.paymentMethod = paymentMethod;
      this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      String response;
      if (path.equals("/v1/customers/" + creation.providerCustomerId())) {
        response =
            "{\"object\":\"customer\",\"id\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"deleted\":false,\"metadata\":{\"firemud_metadata_schema_version\":\"1\","
                + "\"firemud_operation_type\":\"customer_provisioning\",\"firemud_operation_id\":\""
                + creation.customerProvisioningRequestId()
                + "\",\"firemud_account_id\":\""
                + creation.owner().accountUuid()
                + "\"}}";
      } else if (path.equals("/v1/payment_methods/" + paymentMethod)) {
        response =
            "{\"object\":\"payment_method\",\"id\":\""
                + paymentMethod
                + "\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}";
      } else if (path.equals("/v1/subscriptions/search")) {
        assertThat(params.get("query").toString())
            .contains(
                creation.subscriptionProvisioningRequestId().toString(),
                creation.subscriptionId().toString());
        response =
            "{\"object\":\"search_result\",\"has_more\":false,\"data\":["
                + (exists ? subscription() : "")
                + "]}";
      } else if (path.equals("/v1/subscriptions") && method == RequestMethod.POST) {
        creates++;
        exists = true;
        assertThat(options.getIdempotencyKey())
            .isEqualTo(creation.subscriptionProvisioningRequestId().toString());
        assertThat(params)
            .containsEntry("customer", creation.providerCustomerId())
            .containsEntry("default_payment_method", paymentMethod);
        assertThat(params.get("items"))
            .isEqualTo(
                List.of(
                    Map.of(
                        "price",
                        creation.planQuote().providerPriceId(),
                        "quantity",
                        creation.planQuote().providerQuantity())));
        if (loseCreateResponse) {
          throw org.mockito.Mockito.mock(StripeException.class);
        }
        response = subscription();
      } else if (path.equals("/v1/subscriptions/sub_exact")) {
        subscriptionReads++;
        response = subscription();
      } else {
        throw new AssertionError("Unexpected provider path " + path);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    private String subscription() {
      return "{\"object\":\"subscription\",\"id\":\"sub_exact\",\"customer\":\""
          + creation.providerCustomerId()
          + "\",\"default_payment_method\":\""
          + paymentMethod
          + "\",\"livemode\":false,\"status\":\"active\",\"cancel_at_period_end\":false,"
          + "\"metadata\":{\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"subscription_provisioning\","
          + "\"firemud_operation_id\":\""
          + creation.subscriptionProvisioningRequestId()
          + "\",\"firemud_subscription_id\":\""
          + creation.subscriptionId()
          + "\"},"
          + "\"items\":{\"has_more\":false,\"data\":[{\"id\":\"si_exact\",\"object\":\"subscription_item\",\"subscription\":\"sub_exact\","
          + "\"quantity\":"
          + creation.planQuote().providerQuantity()
          + ",\"current_period_start\":"
          + clock.instant().minusSeconds(5).getEpochSecond()
          + ",\"current_period_end\":"
          + clock.instant().plusSeconds(3600).getEpochSecond()
          + ",\"price\":{\"id\":\""
          + creation.planQuote().providerPriceId()
          + "\",\"object\":\"price\"}}]}}";
    }

    @Override
    public InputStream requestStream(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("No streaming");
    }
  }
}
