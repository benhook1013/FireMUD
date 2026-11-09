package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanCatalog;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreationRecord;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controlled local source setup plus the real hosting lifecycle owner and Stripe SDK read path. The
 * caller must migrate its isolated Account schema before using this fixture. The fresh-tenant,
 * creator/consent, customer, wallet, and transport inputs are controlled; they do not qualify their
 * authenticated producers, PKI, Stripe, runtime registration, or production entitlement use.
 */
public final class CanonicalHostingSubscriptionTestFixture {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong GAME_ROWS = new AtomicLong(20_000);

  private CanonicalHostingSubscriptionTestFixture() {}

  public static Fixture create(DataSource source) {
    return createInternal(source, null);
  }

  /** Reuses the exact persisted Account of a genuine actor/authentication composition. */
  public static Fixture create(DataSource source, Account account) {
    return createInternal(source, Objects.requireNonNull(account, "persisted Account required"));
  }

  private static Fixture createInternal(DataSource source, Account existingAccount) {
    Objects.requireNonNull(source, "migrated Account datasource is required");
    JdbcTemplate jdbc = new JdbcTemplate(source);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    AccountHostingPlanCatalog catalog =
        new AccountHostingPlanCatalog(
            List.of(
                new AccountHostingPlanCatalog.ConfiguredPlan(
                    "hosting", "price_controlled", "1", "100", "2", "1048576")));
    AccountSubscriptionIntentRepository intents =
        new AccountSubscriptionIntentRepository(dsl, catalog);

    long row = GAME_ROWS.incrementAndGet();
    var accountRow =
        existingAccount == null
            ? dsl.fetchOne(
                "INSERT INTO accounts (username, email, password_hash) "
                    + "VALUES (?, ?, 'controlled-hash') RETURNING id, account_uuid",
                "hosting-fixture-" + row,
                "hosting-fixture-" + row + "@example.test")
            : dsl.fetchOne(
                "SELECT id, account_uuid FROM accounts WHERE id = ? AND account_uuid = ?",
                Objects.requireNonNull(existingAccount.getId(), "persisted Account ID required"),
                Objects.requireNonNull(
                    existingAccount.getAccountUuid(), "persisted Account UUID required"));
    Objects.requireNonNull(accountRow, "Exact persisted Account row required");
    long accountId = accountRow.get("id", Long.class);
    UUID accountUuid = accountRow.get("account_uuid", UUID.class);

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
        pendingCustomer.state() == AccountBillingCustomerRecord.State.CONFIRMED
            ? pendingCustomer
            : in(
                transaction,
                () ->
                    customers.recordObservation(
                        accountUuid,
                        pendingCustomer.customerProvisioningRequestId(),
                        pendingCustomer.rowVersion(),
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
                            State.ATTACHABLE,
                            new DisplayMetadata("visa", "4242", 12, 2030),
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

    MutableClock clock = new MutableClock(creation.createdAt().plusSeconds(1));
    ControlledStripeTransport transport = new ControlledStripeTransport();
    var lifecycleRepository =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl,
            intents,
            clock,
            new ReconciliationLimits(
                4, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
            new AccountHostingSubscriptionLifecyclePolicy(
                Duration.ofSeconds(15),
                new RecoveryTiming(Duration.ofSeconds(60), Duration.ofSeconds(120))),
            "prod",
            false);
    var observer =
        new StripeHostingSubscriptionObserver(
            new com.stripe.StripeClient(transport),
            clock,
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    var service =
        new AccountHostingSubscriptionLifecycleService(
            lifecycleRepository, observer, new DataSourceTransactionManager(source));
    Fixture fixture =
        new Fixture(
            jdbc,
            dsl,
            transaction,
            accountId,
            accountUuid,
            tenantId,
            creation,
            intents,
            instrument,
            "sub_" + row,
            clock,
            transport,
            service,
            lifecycleRepository,
            observer);
    fixture.providerStatus("active");
    return fixture;
  }

  private static <T> T in(TransactionTemplate transaction, Supplier<T> body) {
    return Objects.requireNonNull(transaction.execute(status -> body.get()));
  }

  public static final class Fixture {
    private final JdbcTemplate jdbc;
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final long accountId;
    private final UUID accountUuid;
    private final UUID tenantId;
    private final AccountSubscriptionCreationRecord creation;
    private final AccountSubscriptionIntentRepository intents;
    private final AccountPaymentInstrumentRecord instrument;
    private final String providerId;
    private final MutableClock clock;
    private final ControlledStripeTransport transport;
    private final AccountHostingSubscriptionLifecycleService service;
    private final AccountHostingSubscriptionLifecycleRepository lifecycleRepository;
    private final StripeHostingSubscriptionObserver observer;

    private Fixture(
        JdbcTemplate jdbc,
        DSLContext dsl,
        TransactionTemplate transaction,
        long accountId,
        UUID accountUuid,
        UUID tenantId,
        AccountSubscriptionCreationRecord creation,
        AccountSubscriptionIntentRepository intents,
        AccountPaymentInstrumentRecord instrument,
        String providerId,
        MutableClock clock,
        ControlledStripeTransport transport,
        AccountHostingSubscriptionLifecycleService service,
        AccountHostingSubscriptionLifecycleRepository lifecycleRepository,
        StripeHostingSubscriptionObserver observer) {
      this.jdbc = jdbc;
      this.dsl = dsl;
      this.transaction = transaction;
      this.accountId = accountId;
      this.accountUuid = accountUuid;
      this.tenantId = tenantId;
      this.creation = creation;
      this.intents = intents;
      this.instrument = instrument;
      this.providerId = providerId;
      this.clock = clock;
      this.transport = transport;
      this.service = service;
      this.lifecycleRepository = lifecycleRepository;
      this.observer = observer;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Integration tests intentionally inspect the fixture's actual JdbcTemplate and datasource; copying it would change the physical SQL boundary.")
    public JdbcTemplate jdbc() {
      return jdbc;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Integration tests intentionally use this exact datasource-backed DSLContext for physical SQL assertions; a copy would change the fixture boundary.")
    public DSLContext dsl() {
      return dsl;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Integration tests intentionally use this exact TransactionTemplate to exercise the fixture's real transaction boundary; a copy would change it.")
    public TransactionTemplate transaction() {
      return transaction;
    }

    public long accountId() {
      return accountId;
    }

    public UUID accountUuid() {
      return accountUuid;
    }

    public UUID tenantId() {
      return tenantId;
    }

    public AccountSubscriptionCreationRecord creation() {
      return creation;
    }

    public AccountSubscriptionIntentRepository intents() {
      return intents;
    }

    public AccountPaymentInstrumentRecord instrument() {
      return instrument;
    }

    public String providerId() {
      return providerId;
    }

    public MutableClock clock() {
      return clock;
    }

    public ControlledStripeTransport transport() {
      return transport;
    }

    public AccountHostingSubscriptionLifecycleService service() {
      return service;
    }

    /** Actual source collaborators for explicit prepare/observe/final-transaction tests. */
    public AccountHostingSubscriptionLifecycleRepository lifecycleRepository() {
      return lifecycleRepository;
    }

    public StripeHostingSubscriptionObserver observer() {
      return observer;
    }

    public void providerStatus(String status) {
      transport.json = response(this, status);
    }
  }

  public static final class MutableClock extends Clock {
    private volatile Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    public void advance(Duration duration) {
      current = current.plus(duration);
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
      return current;
    }
  }

  public static final class ControlledStripeTransport implements StripeResponseGetter {
    private String json;
    private int reads;
    private Runnable duringRead;

    public int reads() {
      return reads;
    }

    /** One-shot owner mutation while the real SDK request is outside Account SQL. */
    public void duringRead(Runnable action) {
      if (duringRead != null) throw new IllegalStateException("A provider-read action is pending");
      duringRead = Objects.requireNonNull(action);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(method).isEqualTo(RequestMethod.GET);
      assertThat(path).startsWith("/v1/subscriptions/");
      reads++;
      Runnable action = duringRead;
      duringRead = null;
      if (action != null) action.run();
      return (T) com.stripe.net.ApiResource.GSON.fromJson(json, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      throw new AssertionError("No Stripe streaming or provider mutations are allowed");
    }
  }

  @SuppressFBWarnings(
      value = "VA_FORMAT_STRING_USES_NEWLINE",
      justification =
          "This is a JSON test fixture whose text block intentionally retains LF characters independent of the host console line separator.")
  private static String response(Fixture fixture, String status) {
    String endedAt =
        "canceled".equals(status)
            ? Long.toString(fixture.clock().instant().getEpochSecond())
            : "null";
    return """
        {"id":"%s","object":"subscription","customer":"%s","default_payment_method":"%s",
         "livemode":false,"status":"%s","cancel_at_period_end":false,"cancel_at":null,"canceled_at":null,"ended_at":%s,
         "metadata":{"firemud_metadata_schema_version":"1","firemud_operation_type":"subscription_provisioning",
          "firemud_operation_id":"%s","firemud_subscription_id":"%s"},
         "items":{"object":"list","data":[{"id":"si_controlled","object":"subscription_item","subscription":"%s",
          "quantity":1,"current_period_start":%d,"current_period_end":%d,"price":{"id":"%s","object":"price"}}],"has_more":false}}
        """
        .formatted(
            fixture.providerId(),
            fixture.creation().providerCustomerId(),
            fixture.instrument().providerPaymentMethodId(),
            status,
            endedAt,
            fixture.creation().subscriptionProvisioningRequestId(),
            fixture.creation().subscriptionId(),
            fixture.providerId(),
            fixture.creation().createdAt().getEpochSecond(),
            fixture.creation().createdAt().getEpochSecond() + 3600,
            fixture.creation().planQuote().providerPriceId());
  }
}
