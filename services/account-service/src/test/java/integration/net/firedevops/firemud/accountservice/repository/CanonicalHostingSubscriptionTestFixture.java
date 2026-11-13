package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import de.mkammerer.argon2.Argon2Factory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.client.StripeHostingWebhookVerifier;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionPaidSource;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePayment;
import net.firedevops.firemud.accountservice.repository.AccountHostingFirstInvoicePaymentRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingPlanCatalog;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.accountservice.repository.AccountInitialTenantBillingOwnerRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreationRecord;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.repository.RetainedSubscriptionEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.StripeEventInboxRepository;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiKeyring;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiResponseCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicyRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationRepository;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationService;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.mapstruct.factory.Mappers;
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
  public static final String BILLING_PASSWORD = "test-only-hosting-first-invoice-password";
  private static final String WEBHOOK_SECRET = "whsec_test_only_hosting_first_invoice";
  private static final AtomicLong GAME_ROWS = new AtomicLong(20_000);

  private CanonicalHostingSubscriptionTestFixture() {}

  public static Fixture create(DataSource source) {
    return createInternal(source, null, "prod");
  }

  /** Uses the caller's explicit workload namespace without changing controlled source custody. */
  public static Fixture create(DataSource source, String namespace) {
    return createInternal(source, null, namespace);
  }

  /** Reuses the exact persisted Account of a genuine actor/authentication composition. */
  public static Fixture create(DataSource source, Account account) {
    return createInternal(
        source, Objects.requireNonNull(account, "persisted Account required"), "prod");
  }

  private static Fixture createInternal(
      DataSource source, Account existingAccount, String namespace) {
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
    Account account =
        existingAccount == null ? createBillingAccount(dsl, transaction) : existingAccount;
    var accountRow =
        dsl.fetchOne(
            "SELECT id, account_uuid FROM accounts WHERE id = ? AND account_uuid = ?",
            Objects.requireNonNull(account.getId(), "persisted Account ID required"),
            Objects.requireNonNull(account.getAccountUuid(), "persisted Account UUID required"));
    Objects.requireNonNull(accountRow, "Exact persisted Account row required");
    long accountId = accountRow.get("id", Long.class);
    UUID accountUuid = accountRow.get("account_uuid", UUID.class);

    UUID tenantId = UUID.randomUUID();
    UUID tenantCreateRequest = UUID.randomUUID();
    UUID tenantCreateOperation = UUID.randomUUID();
    String tenantKey = "hosting-fixture-game-" + row;
    String tenantRequestDigest =
        GameTenantCreationDigest.requestDigest(
            namespace, tenantCreateRequest, tenantKey, "Controlled hosting", "Storage only");
    var tenantSource =
        new FreshTenantCreationEvidence(
            1,
            namespace,
            tenantCreateRequest,
            tenantCreateOperation,
            tenantRequestDigest,
            tenantId,
            row,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                namespace,
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
          new FreshTenantIdentityAssociationRepository(dsl, namespace).importVerified(tenantSource);
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
    var limits =
        new ReconciliationLimits(
            4, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1));
    var policy =
        new AccountHostingSubscriptionLifecyclePolicy(
            Duration.ofSeconds(15),
            new RecoveryTiming(Duration.ofSeconds(60), Duration.ofSeconds(120)));
    var lifecycleRepository =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl, intents, clock, limits, policy, namespace, false);
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
            observer,
            new AccountGameplayAdmissionPaidSource(
                catalog, clock, limits, policy, observer, namespace, false),
            source);
    fixture.providerStatus("active");
    return fixture;
  }

  /** Separate factory for protected HTTPS owner tests that supply their own genuine actor. */
  public static Fixture createAwaitingPayment(DataSource source, Account account) {
    var fixture = create(source, account);
    fixture.awaitFirstInvoice();
    fixture.providerStatus("incomplete");
    return fixture;
  }

  /** Same isolated receiving namespace, selected before immutable tenant-source creation. */
  public static Fixture createAwaitingPayment(
      DataSource source, Account account, String namespace) {
    var fixture = createInternal(source, Objects.requireNonNull(account), namespace);
    fixture.awaitFirstInvoice();
    fixture.providerStatus("incomplete");
    return fixture;
  }

  /** Fresh Account insert through the real identity/authority owner, with a real test password. */
  public static Account createBillingAccount(DSLContext dsl, TransactionTemplate transaction) {
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var accounts =
        new AccountRepository(
            dsl, new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox));
    var account = new Account();
    String suffix = UUID.randomUUID().toString();
    account.setUsername("hosting-fi-" + suffix);
    account.setEmail(suffix + "@example.test");
    char[] password = BILLING_PASSWORD.toCharArray();
    var argon = Argon2Factory.create();
    try {
      account.setPasswordHash(argon.hash(2, 4096, 1, password));
    } finally {
      argon.wipeArray(password);
    }
    account.setRole("player");
    account.setLoginAuthModes("PASSWORD");
    account.setLifecycleState(AccountLifecycleState.ACTIVE);
    return in(transaction, () -> accounts.save(account));
  }

  /** Actual dispatch/capture owner. Commercial/security policy rows are explicit test inputs. */
  public static FirstInvoiceCheckout prepareFirstInvoice(
      DataSource source,
      DSLContext dsl,
      TransactionTemplate transaction,
      AccountSubscriptionCreationRecord creation,
      AccountPaymentInstrumentRecord instrument,
      String providerId,
      Clock clock,
      AccountHostingSubscriptionLifecycleRepository lifecycle) {
    in(
        transaction,
        () -> {
          dsl.execute(
              "INSERT INTO account_billing_reauthentication_policy_values (policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) VALUES ('PLATFORM',1,300,30),('DEPLOYMENT',1,300,30) ON CONFLICT DO NOTHING");
          dsl.execute(
              "INSERT INTO account_billing_reauthentication_policy_current (policy_scope, policy_version) VALUES ('PLATFORM',1),('DEPLOYMENT',1) ON CONFLICT DO NOTHING");
          dsl.execute(
              "INSERT INTO account_hosting_billing_policy_values (provider_price_id, policy_version, quantity, amount, currency) VALUES (?,1,1,2400,'usd') ON CONFLICT DO NOTHING",
              creation.planQuote().providerPriceId());
          dsl.execute(
              "INSERT INTO account_hosting_billing_policy_current (provider_price_id, policy_version) VALUES (?,1) ON CONFLICT DO NOTHING",
              creation.planQuote().providerPriceId());
          return true;
        });
    var claim =
        in(transaction, () -> lifecycle.claim(creation.requestId(), 1, UUID.randomUUID()).claim());
    var prepared =
        in(transaction, () -> lifecycle.prepareProviderDispatch(claim, UUID.randomUUID()));
    var dispatch = in(transaction, () -> lifecycle.readProviderDispatch(prepared));
    var transport = new ControlledFirstInvoiceTransport(creation, instrument, providerId);
    var provider =
        new StripeHostingFirstInvoicePaymentClient(
            new com.stripe.StripeClient(transport),
            clock,
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    var payments =
        new AccountHostingFirstInvoicePaymentRepository(
            dsl,
            new AccountSubscriptionIntentRepository(dsl, new AccountHostingPlanCatalog(List.of())),
            clock);
    try {
      var observed = provider.capture(dispatch, providerId);
      var invoice =
          in(transaction, () -> payments.retainAwaitingPayment(dispatch, providerId, observed));
      return new FirstInvoiceCheckout(
          source, dsl, transaction, clock, payments, provider, transport, invoice);
    } catch (StripeException failure) {
      throw new IllegalStateException("Controlled existing-invoice capture failed", failure);
    }
  }

  /**
   * Real payment/reauth owners with test-only policy, keys, primary password and signed transport.
   */
  public static final class FirstInvoiceCheckout {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AccountHostingFirstInvoicePaymentRepository payments;
    private final StripeHostingFirstInvoicePaymentClient provider;
    private final ControlledFirstInvoiceTransport transport;
    private final AccountHostingFirstInvoicePayment invoice;
    private final StripeHostingWebhookVerifier verifier;
    private final AccountRecentReauthenticationService reauthentication;
    private final AccountRepository accounts;

    private FirstInvoiceCheckout(
        DataSource source,
        DSLContext dsl,
        TransactionTemplate transaction,
        Clock clock,
        AccountHostingFirstInvoicePaymentRepository payments,
        StripeHostingFirstInvoicePaymentClient provider,
        ControlledFirstInvoiceTransport transport,
        AccountHostingFirstInvoicePayment invoice) {
      this.dsl = dsl;
      this.transaction = transaction;
      this.clock = clock;
      this.payments = payments;
      this.provider = provider;
      this.transport = transport;
      this.invoice = invoice;
      verifier = new StripeHostingWebhookVerifier(WEBHOOK_SECRET, clock);
      var manager = new DataSourceTransactionManager(source);
      var generations = new AccountAuthorityGenerationRepository(dsl);
      var outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      accounts = new AccountRepository(dsl, sources);
      var primary =
          new AccountServiceImpl(
              accounts,
              generations,
              sources,
              outbox,
              mock(AccountPasswordResetOperationRepository.class),
              mock(AccountLogoutAllOperationRepository.class),
              mock(AccountSecurityStateOperationRepository.class),
              mock(AccountPasswordResetDraftSourceChangeRepository.class),
              mock(AccountAuditOutboxRepository.class),
              mock(AccountJoinOperationRepository.class),
              new AccountEmailLoginChallengeRepository(dsl),
              null,
              null,
              Mappers.getMapper(AccountMapper.class),
              null,
              null,
              null,
              mock(RetainedSubscriptionEvidenceRepository.class),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              manager);
      try {
        Path custody = Files.createTempDirectory("hosting-first-invoice-test-only-custody-");
        for (String purpose : List.of("encryption", "request-mac")) {
          byte[] bytes = new byte[32];
          Arrays.fill(bytes, (byte) (purpose.equals("encryption") ? 11 : 29));
          Files.createDirectories(custody.resolve(purpose));
          Files.writeString(
              custody.resolve(purpose).resolve("keyring"),
              "firemud-account-response-envelope-keyring-v1\nactive test1 "
                  + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                  + "\n");
        }
        reauthentication =
            new AccountRecentReauthenticationService(
                primary,
                accounts,
                generations,
                new AccountRecentReauthenticationRepository(dsl),
                new AccountRecentReauthenticationPolicyRepository(dsl),
                manager,
                clock,
                new AccountControlUiResponseCryptography(
                    new AccountControlUiKeyring(custody), clock));
      } catch (IOException failure) {
        throw new IllegalStateException("Test-only custody setup failed", failure);
      }
    }

    public AccountHostingFirstInvoicePayment invoice() {
      return invoice;
    }

    public AccountHostingFirstInvoicePaymentRepository payments() {
      return payments;
    }

    public StripeHostingFirstInvoicePaymentClient provider() {
      return provider;
    }

    public StripeHostingWebhookVerifier verifier() {
      return verifier;
    }

    public void status(String status) {
      transport.status = status;
    }

    public int confirmations() {
      return transport.confirmations;
    }

    public void loseNextConfirmationResponse() {
      transport.loseNextConfirmationResponse = true;
    }

    public String lastConfirmationIdempotencyKey() {
      return transport.lastConfirmationIdempotencyKey;
    }

    public AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch
        authorizeAndRetainSuccess(String password) {
      authorize(password);
      return retainSignedSuccess();
    }

    public AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch authorize(
        String password) {
      var dispatch = prepareConfirmationDispatch(password);
      try {
        assertThat(
                provider
                    .confirm(
                        dispatch,
                        () ->
                            in(
                                transaction,
                                () -> payments.claimCommand(dispatch, invoice.ownerAccountId())))
                    .status())
            .isEqualTo("succeeded");
        assertThat(transport.confirmations).isOne();
        return dispatch;
      } catch (StripeException failure) {
        throw new IllegalStateException("Controlled confirmation failed", failure);
      }
    }

    public AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch
        prepareConfirmationDispatch(String password) {
      UUID requestId = UUID.randomUUID();
      Account account =
          in(
              transaction,
              () -> accounts.findByAccountUuidForUpdate(invoice.ownerAccountId()).orElseThrow());
      long expectedRowVersion =
          in(
              transaction,
              () -> payments.ownerRowVersion(invoice.subscriptionId(), invoice.ownerAccountId()));
      var billingContext =
          new AccountRecentReauthenticationEvidence.BillingContext(
              invoice.ownerAccountId(),
              invoice.subscriptionId(),
              requestId,
              invoice.digest(),
              expectedRowVersion,
              invoice.expiresAt(),
              requestId,
              0L,
              AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose
                  .INITIAL_CONFIRMATION);
      var evidence =
          reauthentication.reauthenticate(
              new AccountRecentReauthenticationService.Request(
                  UUID.randomUUID(),
                  invoice.ownerAccountId(),
                  AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                  requestId,
                  billingContext,
                  account.getEmail(),
                  password));
      var prepared =
          in(
              transaction,
              () -> {
                payments.bindReauthenticationContext(
                    invoice.ownerAccountId(), invoice.subscriptionId(), billingContext, evidence);
                reauthentication.validateAndConsumeLocked(
                    evidence,
                    invoice.ownerAccountId(),
                    AccountRecentReauthenticationPolicy.BILLING_PURPOSE,
                    requestId);
                return payments.prepareConfirmation(
                    invoice.ownerAccountId(),
                    requestId,
                    invoice.subscriptionId(),
                    expectedRowVersion,
                    invoice.digest(),
                    evidence);
              });
      return in(transaction, () -> payments.readDispatch(prepared, invoice.ownerAccountId()));
    }

    /**
     * Signed success and independent paid readback may arrive after consumed authorization expires.
     */
    public AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch retainSignedSuccess() {
      try {
        var signed = signedSuccess();
        var verified = verifier.verify(signed.payload(), signed.signature());
        in(transaction, () -> new StripeEventInboxRepository(dsl).retainOrReplay(verified));
        var readback = provider.observe(invoice);
        return in(transaction, () -> payments.retainSignedSuccess(verified, readback));
      } catch (StripeException failure) {
        throw new IllegalStateException("Controlled signed checkout failed", failure);
      }
    }

    public SignedSuccess signedSuccess() {
      String payload =
          "{\"id\":\"evt_"
              + invoice.paymentIntentId().substring(3)
              + "\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\",\"created\":"
              + clock.instant().getEpochSecond()
              + ",\"livemode\":false,\"data\":{\"object\":"
              + transport.intent("succeeded")
              + "}}";
      return signWebhookPayload(payload);
    }

    public SignedSuccess signWebhookPayload(String payload) {
      String timestamp = Long.toString(clock.instant().getEpochSecond());
      try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return new SignedSuccess(
            payload,
            "t="
                + timestamp
                + ",v1="
                + HexFormat.of()
                    .formatHex(
                        mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8))));
      } catch (java.security.GeneralSecurityException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  public record SignedSuccess(String payload, String signature) {}

  private static final class ControlledFirstInvoiceTransport implements StripeResponseGetter {
    private final AccountSubscriptionCreationRecord creation;
    private final AccountPaymentInstrumentRecord instrument;
    private final String providerId, invoiceId, intentId;
    private String status = "requires_confirmation";
    private int confirmations;
    private String confirmationStatus = "succeeded";
    private boolean loseNextConfirmationResponse;
    private String lastConfirmationIdempotencyKey;

    private ControlledFirstInvoiceTransport(
        AccountSubscriptionCreationRecord creation,
        AccountPaymentInstrumentRecord instrument,
        String providerId) {
      this.creation = creation;
      this.instrument = instrument;
      this.providerId = providerId;
      String suffix = creation.subscriptionId().toString().replace("-", "");
      invoiceId = "in_" + suffix;
      intentId = "pi_" + suffix;
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
      String json;
      if (path.equals("/v1/subscriptions/" + providerId)) {
        assertThat(method).isEqualTo(RequestMethod.GET);
        json =
            "{\"id\":\""
                + providerId
                + "\",\"object\":\"subscription\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"default_payment_method\":\""
                + instrument.providerPaymentMethodId()
                + "\",\"livemode\":false,\"status\":\"incomplete\",\"created\":"
                + creation.createdAt().getEpochSecond()
                + ",\"latest_invoice\":\""
                + invoiceId
                + "\",\"metadata\":{\"firemud_operation_id\":\""
                + creation.subscriptionProvisioningRequestId()
                + "\",\"firemud_subscription_id\":\""
                + creation.subscriptionId()
                + "\"}}";
      } else if (path.equals("/v1/invoices/" + invoiceId)) {
        assertThat(method).isEqualTo(RequestMethod.GET);
        boolean paid = "succeeded".equals(status);
        json =
            "{\"id\":\""
                + invoiceId
                + "\",\"object\":\"invoice\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"billing_reason\":\"subscription_create\",\"status\":\""
                + (paid ? "paid" : "open")
                + "\",\"amount_due\":2400,\"total\":2400,\"starting_balance\":0,\"amount_paid\":"
                + (paid ? 2400 : 0)
                + ",\"amount_remaining\":"
                + (paid ? 0 : 2400)
                + ",\"currency\":\"usd\",\"parent\":{\"subscription_details\":{\"subscription\":\""
                + providerId
                + "\"}},\"lines\":{\"has_more\":false,\"data\":[{\"object\":\"line_item\",\"amount\":2400,\"currency\":\"usd\",\"quantity\":"
                + creation.planQuote().providerQuantity()
                + ",\"pricing\":{\"type\":\"price_details\",\"price_details\":{\"price\":\""
                + creation.planQuote().providerPriceId()
                + "\"}}}]},\"payments\":{\"object\":\"list\",\"has_more\":false,\"data\":[{\"object\":\"invoice_payment\",\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":"
                + intent(status)
                + "}}]}}";
      } else if (path.equals("/v1/payment_intents/" + intentId)) {
        assertThat(method).isEqualTo(RequestMethod.GET);
        json = intent(status);
      } else if (path.equals("/v1/payment_intents/" + intentId + "/confirm")) {
        assertThat(method).isEqualTo(RequestMethod.POST);
        assertThat(options.getIdempotencyKey()).isNotBlank();
        lastConfirmationIdempotencyKey = options.getIdempotencyKey();
        assertThat(params)
            .containsEntry("payment_method", instrument.providerPaymentMethodId())
            .containsEntry("use_stripe_sdk", true);
        assertThat(++confirmations).isOne();
        status = confirmationStatus;
        if (loseNextConfirmationResponse) {
          loseNextConfirmationResponse = false;
          throw new com.stripe.exception.ApiConnectionException(
              "Controlled accepted confirmation response was lost");
        }
        json = intent(status);
      } else {
        throw new AssertionError("Unexpected controlled Stripe call: " + path);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(json, type);
    }

    private String intent(String state) {
      return "{\"id\":\""
          + intentId
          + "\",\"object\":\"payment_intent\",\"customer\":\""
          + creation.providerCustomerId()
          + "\",\"payment_method\":\""
          + instrument.providerPaymentMethodId()
          + "\",\"livemode\":false,\"confirmation_method\":\"automatic\",\"status\":\""
          + state
          + "\",\"amount\":2400,\"amount_received\":"
          + (state.equals("succeeded") ? 2400 : 0)
          + ",\"currency\":\"usd\""
          + (state.equals("requires_action")
              ? ",\"client_secret\":\"" + intentId + "_secret_testOnly\""
              : "")
          + "}";
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("No streaming provider calls");
    }
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
    private final AccountGameplayAdmissionPaidSource admissionPaidSource;
    private final DataSource source;
    private FirstInvoiceCheckout checkout;

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
        StripeHostingSubscriptionObserver observer,
        AccountGameplayAdmissionPaidSource admissionPaidSource,
        DataSource source) {
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
      this.admissionPaidSource = admissionPaidSource;
      this.source = source;
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

    public AccountGameplayAdmissionPaidSource admissionPaidSource() {
      return admissionPaidSource;
    }

    /** Retains the actual existing invoice without granting payment or hosting authority. */
    public FirstInvoiceCheckout awaitFirstInvoice() {
      if (checkout == null)
        checkout =
            prepareFirstInvoice(
                source,
                dsl,
                transaction,
                creation,
                instrument,
                providerId,
                clock,
                lifecycleRepository);
      return checkout;
    }

    public AccountHostingFirstInvoicePayment firstInvoice() {
      return awaitFirstInvoice().invoice();
    }

    public AccountHostingFirstInvoicePaymentRepository firstInvoiceRepository() {
      return awaitFirstInvoice().payments();
    }

    /** Actual SDK with only the existing bounded controlled transports, never Stripe network IO. */
    public com.stripe.StripeClient receivingClient() {
      var checkoutTransport = awaitFirstInvoice().transport;
      return new com.stripe.StripeClient(
          new StripeResponseGetter() {
            @Override
            public <T extends StripeObject> T request(
                BaseAddress address,
                RequestMethod method,
                String path,
                Map<String, Object> params,
                Type type,
                RequestOptions options,
                ApiMode mode)
                throws StripeException {
              StripeResponseGetter selected =
                  path.startsWith("/v1/subscriptions/") ? transport : checkoutTransport;
              return selected.request(address, method, path, params, type, options, mode);
            }

            @Override
            public InputStream requestStream(
                BaseAddress address,
                RequestMethod method,
                String path,
                Map<String, Object> params,
                RequestOptions options,
                ApiMode mode) {
              throw new AssertionError("No streaming provider calls in isolated receiving");
            }
          });
    }

    public String receivingWebhookSecret() {
      return WEBHOOK_SECRET;
    }

    public StripeHostingFirstInvoicePaymentClient firstInvoiceProvider() {
      return awaitFirstInvoice().provider();
    }

    public StripeHostingWebhookVerifier webhookVerifier() {
      return awaitFirstInvoice().verifier();
    }

    public void firstInvoiceStatus(String status) {
      awaitFirstInvoice().status(status);
    }

    public void firstInvoiceConfirmationStatus(String status) {
      awaitFirstInvoice().transport.confirmationStatus = status;
    }

    public int firstInvoiceConfirmations() {
      return awaitFirstInvoice().confirmations();
    }

    public void loseNextFirstInvoiceConfirmationResponse() {
      awaitFirstInvoice().loseNextConfirmationResponse();
    }

    public String lastFirstInvoiceConfirmationIdempotencyKey() {
      return awaitFirstInvoice().lastConfirmationIdempotencyKey();
    }

    public String signedSuccessPayload() {
      return signedSuccess().payload();
    }

    public String signedSuccessHeader() {
      return signedSuccess().signature();
    }

    public SignedSuccess signedSuccess() {
      return awaitFirstInvoice().signedSuccess();
    }

    /** Genuine password reauthentication and signed completion over controlled provider inputs. */
    public AccountHostingSubscriptionLifecycleRepository.TransitionResult completeFirstInvoice() {
      return completeFirstInvoice(BILLING_PASSWORD);
    }

    public AccountHostingSubscriptionLifecycleRepository.TransitionResult completeFirstInvoice(
        String password) {
      var authorized = awaitFirstInvoice().authorizeAndRetainSuccess(password);
      providerStatus("active");
      var committed =
          service.reconcile(
              authorized.lifecycleRequestId(),
              creation.requestId(),
              authorized.expectedRowVersion(),
              providerId,
              null);
      assertThat(committed.outcome())
          .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Outcome.COMMITTED);
      assertThat(service.readCurrent(tenantId).receiptRequestId())
          .isEqualTo(authorized.lifecycleRequestId());
      assertThat(
              in(
                  transaction,
                  () ->
                      checkout
                          .payments()
                          .completed(creation.subscriptionId(), authorized.lifecycleRequestId())))
          .isTrue();
      return committed;
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
