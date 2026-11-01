package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mkammerer.argon2.Argon2Factory;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient;
import net.firedevops.firemud.accountservice.client.GameDesignOriginalTenantCreationOutcomeClient.VerifiedTerminal;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountLifecycleState;
import net.firedevops.firemud.accountservice.mapper.AccountMapper;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountEmailLoginChallengeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountLogoutAllOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountPasswordResetOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountSecurityStateOperationRepository;
import net.firedevops.firemud.accountservice.service.AccountPasswordResetDraftSourceChangeRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Binding;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeEvidence.Outcome;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec;
import net.firedevops.firemud.common.tenant.GameTenantCreationOriginalOutcomeGrpcCodec.ReadRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveOriginalTenantCreationOutcomeResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL storage and shared source-writer ordering proof. Password reauthentication is real.
 * Issuance and qualified AOF/replica evidence are stipulated SQL rows, not genuine actor/Redis
 * proof. Recent-reauth policy sources come from their owner reader; the other source values and
 * serialization rows are explicitly controlled test inputs, not claims of live source capture. The
 * terminal transport is a controlled in-process codec path, not physical mTLS or live Game Design
 * authority. This proves immutable data retention and the shared key gate only, never creation
 * authority.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantCreationSourceOrderPostgresIntegrationTest {
  @org.junit.jupiter.api.io.TempDir static java.nio.file.Path reauthenticationCustody;

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  private static final String PASSWORD = "test-only-creation-source-order-password";

  @Test
  void exactRetryPreservesOriginalBytesAndSharedGateBlocksEverySourceKey() {
    Context c = context();
    Fixture fixture = fixture(c);
    List<SourceEvidence> sources = controlledSources(c, fixture);
    seedSerializationRows(c, sources);
    var repository = repository(c);
    var executor = Executors.newSingleThreadExecutor();
    AccountTenantCreationSourceOrderBinding first;
    try {
      first =
          c.tx(
              () -> {
                AccountTenantCreationSourceOrderBinding retained =
                    repository.acquireLocked(fixture.intent(), fixture.admitted(), sources);
                for (SourceEvidence source : sources) {
                  CompletableFuture.runAsync(
                          () -> assertWriterGateDenied(c, source.key()), executor)
                      .join();
                }
                return retained;
              });
    } finally {
      executor.shutdownNow();
    }

    String originalXmin = xmin(c, fixture.intent().creationOperationId());
    var retry = c.tx(() -> repository.acquireLocked(fixture.intent(), fixture.admitted(), sources));
    assertThat(retry.canonicalBytes()).containsExactly(first.canonicalBytes());
    assertThat(retry.authorizationDigest()).isEqualTo(first.authorizationDigest());
    assertThat(xmin(c, fixture.intent().creationOperationId())).isEqualTo(originalXmin);
    assertThat(
            c.tx(
                () ->
                    repository
                        .readOriginal(fixture.intent().creationOperationId())
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(first.canonicalBytes());
    assertThat(
            c.tx(
                () ->
                    repository.readPending(null, 10).stream()
                        .map(row -> row.binding().creationOperationId())
                        .toList()))
        .containsExactly(fixture.intent().creationOperationId());

    for (SourceEvidence source : sources) {
      assertWriterGateDenied(c, source.key());
    }
  }

  @Test
  void distinctOriginalOrdersMayShareSourcesAndBothKeepSourceChangesWaiting() {
    Context c = context();
    Account account = c.account();
    UUID creatorPartyId = UUID.randomUUID();
    Fixture firstFixture = fixture(c, account, creatorPartyId);
    Fixture secondFixture = fixture(c, account, creatorPartyId);
    List<SourceEvidence> sources = controlledSources(c, firstFixture);
    seedSerializationRows(c, sources);
    var repository = repository(c);

    var first =
        c.tx(
            () ->
                repository.acquireLocked(firstFixture.intent(), firstFixture.admitted(), sources));
    var second =
        c.tx(
            () ->
                repository.acquireLocked(
                    secondFixture.intent(), secondFixture.admitted(), sources));

    assertThat(first.creationOperationId()).isNotEqualTo(second.creationOperationId());
    assertThat(first.sources()).containsExactlyElementsOf(second.sources());
    assertThat(
            c.tx(
                () ->
                    repository.readPending(null, 10).stream()
                        .map(row -> row.binding().creationOperationId())
                        .toList()))
        .containsExactlyInAnyOrder(
            firstFixture.intent().creationOperationId(),
            secondFixture.intent().creationOperationId());
    assertThat(
            c.tx(
                () ->
                    repository
                        .readOriginal(firstFixture.intent().creationOperationId())
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(first.canonicalBytes());
    assertThat(
            c.tx(
                () ->
                    repository
                        .readOriginal(secondFixture.intent().creationOperationId())
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(second.canonicalBytes());
    assertThat(
            c.tx(
                () ->
                    Objects.requireNonNull(
                            c.dsl.fetchOne(
                                "SELECT count(*) AS count FROM account_tenant_creation_source_order_settlements "
                                    + "WHERE creation_operation_id IN (?, ?)",
                                first.creationOperationId(),
                                second.creationOperationId()))
                        .get("count", Long.class)))
        .isZero();

    var sourceChangeRepository = new DraftAuthorizationFenceRepository(c.dsl);
    var change =
        new DraftAuthorizationFenceRepository.SourceChange(
            UUID.randomUUID(),
            sources,
            "controlled source change request".getBytes(StandardCharsets.UTF_8));
    assertThat(c.tx(() -> sourceChangeRepository.requestSourceChange(change))).isFalse();
    assertThat(c.tx(() -> sourceChangeRepository.inspectSourceChange(change).status()))
        .isEqualTo("WAITING");
    assertThat(c.tx(() -> sourceChangeRepository.sourceMutationPermitted(change))).isFalse();
    assertWriterGateDenied(c, "ISSUER:firemud-account-service");
  }

  @Test
  void verifiedTerminalSettlesEachSharedSourceHolderAndUnblocksOnlyAfterBoth() throws Exception {
    Context c = context();
    Account account = c.account();
    UUID creatorPartyId = UUID.randomUUID();
    Fixture firstFixture = fixture(c, account, creatorPartyId);
    Fixture secondFixture = fixture(c, account, creatorPartyId);
    List<SourceEvidence> sources = controlledSources(c, firstFixture);
    seedSerializationRows(c, sources);
    var repository = repository(c);
    var first =
        c.tx(
            () ->
                repository.acquireLocked(firstFixture.intent(), firstFixture.admitted(), sources));
    var second =
        c.tx(
            () ->
                repository.acquireLocked(
                    secondFixture.intent(), secondFixture.admitted(), sources));

    var sourceChangeRepository = new DraftAuthorizationFenceRepository(c.dsl);
    var waitingChange =
        new DraftAuthorizationFenceRepository.SourceChange(
            UUID.randomUUID(),
            sources,
            "controlled source change waits for original tenant outcomes"
                .getBytes(StandardCharsets.UTF_8));
    assertThat(c.tx(() -> sourceChangeRepository.requestSourceChange(waitingChange))).isFalse();
    assertThat(c.tx(() -> sourceChangeRepository.inspectSourceChange(waitingChange).status()))
        .isEqualTo("WAITING");

    var created = terminalEvidence(first.outcomeBinding(), Outcome.CREATED);
    VerifiedTerminal verifiedCreated = readTerminal(created, first.outcomeBinding());
    String originalXmin = xmin(c, first.creationOperationId());
    var firstSettlement = c.tx(() -> repository.settleLocked(first, verifiedCreated));
    assertThat(firstSettlement.outcome()).isEqualTo(Outcome.CREATED);
    assertThat(firstSettlement.canonicalBytes()).containsExactly(created.canonicalBytes());
    assertThat(xmin(c, first.creationOperationId())).isEqualTo(originalXmin);
    assertThat(
            c.tx(
                () ->
                    repository
                        .readSettlement(first.creationOperationId())
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(created.canonicalBytes());
    assertWriterGateDenied(c, "ISSUER:firemud-account-service");
    assertThat(c.tx(() -> repository.readSettlement(second.creationOperationId()))).isEmpty();

    var exactRetry = c.tx(() -> repository.settleLocked(first, verifiedCreated));
    assertThat(exactRetry.canonicalBytes()).containsExactly(created.canonicalBytes());
    assertThat(xmin(c, first.creationOperationId())).isEqualTo(originalXmin);
    var conflictingTerminal = terminalEvidence(first.outcomeBinding(), Outcome.NOT_CREATED);
    VerifiedTerminal conflictingVerified =
        readTerminal(conflictingTerminal, first.outcomeBinding());
    assertThatThrownBy(() -> c.tx(() -> repository.settleLocked(first, conflictingVerified)))
        .isInstanceOf(IllegalStateException.class);
    assertThat(xmin(c, first.creationOperationId())).isEqualTo(originalXmin);
    for (String mutation :
        List.of(
            "UPDATE account_tenant_creation_source_order_settlements SET recorded_at = CURRENT_TIMESTAMP",
            "DELETE FROM account_tenant_creation_source_order_settlements",
            "TRUNCATE account_tenant_creation_source_order_settlements")) {
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute(mutation)))
          .isInstanceOf(DataAccessException.class);
    }

    var notCreated = terminalEvidence(second.outcomeBinding(), Outcome.NOT_CREATED);
    VerifiedTerminal verifiedNotCreated = readTerminal(notCreated, second.outcomeBinding());
    assertThat(c.tx(() -> repository.settleLocked(second, verifiedNotCreated).outcome()))
        .isEqualTo(Outcome.NOT_CREATED);
    assertWriterGateAllowed(c, "ISSUER:firemud-account-service");
    assertThat(c.tx(() -> sourceChangeRepository.sourceMutationPermitted(waitingChange))).isTrue();
    assertThat(c.tx(() -> repository.readPending(null, 10))).isEmpty();
  }

  @Test
  void failedOrMismatchedTerminalReadsCannotSettleAnOriginalHolder() throws Exception {
    Context c = context();
    Fixture fixture = fixture(c);
    List<SourceEvidence> sources = controlledSources(c, fixture);
    seedSerializationRows(c, sources);
    var repository = repository(c);
    var original =
        c.tx(() -> repository.acquireLocked(fixture.intent(), fixture.admitted(), sources));
    Binding expected = original.outcomeBinding();

    var notCreated = terminalEvidence(expected, Outcome.NOT_CREATED);
    for (Status status :
        List.of(
            Status.NOT_FOUND.withDescription("no terminal record"),
            Status.UNAVAILABLE.withDescription("terminal read unavailable"))) {
      assertThatThrownBy(() -> readTerminal(notCreated, expected, status, response -> response))
          .isInstanceOf(RuntimeException.class);
    }

    List<Binding> changedBindings =
        List.of(
            new Binding(
                "other-game",
                expected.creationRequestId(),
                expected.accountAuthorizationOperationId(),
                expected.initiatingAccountId(),
                expected.creationRequestDigest(),
                expected.accountAuthorizationDigest()),
            new Binding(
                expected.targetNamespace(),
                UUID.randomUUID(),
                expected.accountAuthorizationOperationId(),
                expected.initiatingAccountId(),
                expected.creationRequestDigest(),
                expected.accountAuthorizationDigest()),
            new Binding(
                expected.targetNamespace(),
                expected.creationRequestId(),
                UUID.randomUUID(),
                expected.initiatingAccountId(),
                expected.creationRequestDigest(),
                expected.accountAuthorizationDigest()),
            new Binding(
                expected.targetNamespace(),
                expected.creationRequestId(),
                expected.accountAuthorizationOperationId(),
                UUID.randomUUID(),
                expected.creationRequestDigest(),
                expected.accountAuthorizationDigest()),
            new Binding(
                expected.targetNamespace(),
                expected.creationRequestId(),
                expected.accountAuthorizationOperationId(),
                expected.initiatingAccountId(),
                "sha256:" + "a".repeat(64),
                expected.accountAuthorizationDigest()),
            new Binding(
                expected.targetNamespace(),
                expected.creationRequestId(),
                expected.accountAuthorizationOperationId(),
                expected.initiatingAccountId(),
                expected.creationRequestDigest(),
                "sha256:" + "b".repeat(64)));
    for (Binding changed : changedBindings) {
      var mismatched = terminalEvidence(changed, Outcome.NOT_CREATED);
      assertThatThrownBy(() -> readTerminal(mismatched, expected))
          .isInstanceOf(RuntimeException.class);
    }

    var created = terminalEvidence(expected, Outcome.CREATED);
    assertThatThrownBy(
            () ->
                readTerminal(
                    created,
                    expected,
                    null,
                    response ->
                        response.toBuilder()
                            .setCreatedReceipt(
                                response.getCreatedReceipt().toBuilder()
                                    .setEvidenceDigest("sha256:" + "f".repeat(64)))
                            .build()))
        .isInstanceOf(RuntimeException.class);

    assertThat(c.tx(() -> repository.readSettlement(original.creationOperationId()))).isEmpty();
    assertWriterGateDenied(c, "ISSUER:firemud-account-service");
  }

  @Test
  void changedOrIncompleteSourceInputCannotReplacePendingOriginalAndSettlementRemainsClosed() {
    Context c = context();
    Fixture fixture = fixture(c);
    List<SourceEvidence> sources = controlledSources(c, fixture);
    seedSerializationRows(c, sources);
    var repository = repository(c);
    var original =
        c.tx(() -> repository.acquireLocked(fixture.intent(), fixture.admitted(), sources));

    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        repository.acquireLocked(
                            fixture.intent(), fixture.admitted(), sources.subList(0, 7))))
        .isInstanceOf(IllegalArgumentException.class);

    List<SourceEvidence> changed = new ArrayList<>(sources);
    SourceEvidence first = changed.get(0);
    changed.set(
        0,
        new SourceEvidence(
            first.kind(),
            first.scopeId(),
            first.generation(),
            first.sourceVersion(),
            first.checkpointStream(),
            first.checkpointSequence(),
            "changed controlled owner bytes".getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(
            () ->
                c.tx(() -> repository.acquireLocked(fixture.intent(), fixture.admitted(), changed)))
        .isInstanceOf(IllegalStateException.class);

    AccountTenantCreationIntentRepository.OriginalIntent alteredActor =
        new AccountTenantCreationIntentRepository.OriginalIntent(
            fixture.intent().requestId(),
            fixture.intent().creationOperationId(),
            fixture.intent().gameDesignCreationRequestId(),
            UUID.randomUUID(),
            fixture.intent().targetNamespace(),
            fixture.intent().sourceGameTenantKey(),
            fixture.intent().name(),
            fixture.intent().description(),
            fixture.intent().recentReauthenticationEvidenceId(),
            fixture.intent().creatorPartyId(),
            fixture.intent().consentOperationId(),
            fixture.intent().consentStatementVersion(),
            fixture.intent().affirmativeConsent(),
            fixture.intent().gameDesignRequestDigest(),
            fixture.intent().requestPayload(),
            fixture.intent().requestDigest(),
            fixture.intent().createdAt());
    assertThatThrownBy(
            () ->
                AccountTenantCreationSourceOrderBinding.from(
                    alteredActor, fixture.admitted(), sources))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(
            c.tx(
                () ->
                    repository
                        .readOriginal(fixture.intent().creationOperationId())
                        .orElseThrow()
                        .canonicalBytes()))
        .containsExactly(original.canonicalBytes());
    assertThat(c.tx(() -> consumptionCount(c, fixture.intent().recentReauthenticationEvidenceId())))
        .isZero();
    byte[] invalidTerminal = "unverified terminal input".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                c.tx(
                    () ->
                        c.dsl.execute(
                            "INSERT INTO account_tenant_creation_source_order_settlements "
                                + "(creation_operation_id, target_namespace, game_design_creation_request_id, "
                                + "account_authorization_operation_id, initiating_account_id, creation_request_digest, "
                                + "account_authorization_digest, outcome_status, terminal_read_payload, terminal_read_digest) "
                                + "VALUES (?, 'test-game', ?, ?, ?, ?, ?, 'NOT_CREATED', ?, ?)",
                            fixture.intent().creationOperationId(),
                            fixture.intent().gameDesignCreationRequestId(),
                            fixture.intent().creationOperationId(),
                            fixture.intent().initiatingAccountId(),
                            fixture.intent().gameDesignRequestDigest(),
                            original.authorizationDigest(),
                            invalidTerminal,
                            "sha256:"
                                + AccountControlUiCoordination.digest("SHA-256", invalidTerminal))))
        .isInstanceOf(DataAccessException.class);
    for (String table :
        List.of(
            "account_tenant_creation_source_orders",
            "account_tenant_creation_source_order_sources")) {
      String update =
          "account_tenant_creation_source_orders".equals(table)
              ? "UPDATE " + table + " SET created_at = CURRENT_TIMESTAMP"
              : "UPDATE " + table + " SET source_key = source_key";
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute(update)))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute("DELETE FROM " + table)))
          .isInstanceOf(DataAccessException.class);
      assertThatThrownBy(() -> c.tx(() -> c.dsl.execute("TRUNCATE " + table + " CASCADE")))
          .isInstanceOf(DataAccessException.class);
    }
  }

  private static GameTenantCreationOriginalOutcomeEvidence terminalEvidence(
      Binding binding, Outcome outcome) {
    if (outcome == Outcome.NOT_CREATED) {
      return new GameTenantCreationOriginalOutcomeEvidence(
          1, binding, outcome, java.util.Optional.empty());
    }
    UUID tenantId = UUID.randomUUID();
    UUID gameDesignOperationId = UUID.randomUUID();
    String sourceGameTenantKey = "fresh-source-" + UUID.randomUUID().toString().substring(0, 8);
    String receiptDigest =
        GameTenantCreationDigest.evidenceDigest(
            binding.targetNamespace(),
            binding.creationRequestId(),
            gameDesignOperationId,
            binding.creationRequestDigest(),
            tenantId,
            91L,
            sourceGameTenantKey,
            "NEW_GAME_ROW");
    FreshTenantCreationEvidence receipt =
        new FreshTenantCreationEvidence(
            1,
            binding.targetNamespace(),
            binding.creationRequestId(),
            gameDesignOperationId,
            binding.creationRequestDigest(),
            tenantId,
            91L,
            sourceGameTenantKey,
            "NEW_GAME_ROW",
            receiptDigest);
    return new GameTenantCreationOriginalOutcomeEvidence(
        1, binding, outcome, java.util.Optional.of(receipt));
  }

  private static VerifiedTerminal readTerminal(
      GameTenantCreationOriginalOutcomeEvidence evidence, Binding requestedBinding)
      throws Exception {
    return readTerminal(evidence, requestedBinding, null, response -> response);
  }

  private static VerifiedTerminal readTerminal(
      GameTenantCreationOriginalOutcomeEvidence evidence,
      Binding requestedBinding,
      Status failure,
      UnaryOperator<ResolveOriginalTenantCreationOutcomeResponse> responseMutation)
      throws Exception {
    ServiceEndpointsProperties endpoints = new ServiceEndpointsProperties();
    endpoints.setGameDesignService("controlled-game-design:6565");
    Path tlsDirectory = Files.createTempDirectory("source-order-terminal-transport-");
    Path certificate = Files.createFile(tlsDirectory.resolve("account.crt"));
    Path privateKey = Files.createFile(tlsDirectory.resolve("account.key"));
    Path caCertificate = Files.createFile(tlsDirectory.resolve("game-design-ca.crt"));
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain(certificate.toString());
    tls.setPrivateKey(privateKey.toString());
    tls.setCaCert(caCertificate.toString());

    var channels = mock(GrpcChannelFactory.class);
    var channel =
        new ControlledOutcomeChannel(
            evidence, requestedBinding.targetNamespace(), failure, responseMutation);
    when(channels.buildChannel(
            anyString(), anyInt(), any(CommonGrpcClientProperties.class), anyBoolean()))
        .thenReturn(channel);
    var client =
        new GameDesignOriginalTenantCreationOutcomeClient(
            endpoints, tls, channels, requestedBinding.targetNamespace());
    try {
      client.init();
      return client.readOriginal(requestedBinding);
    } finally {
      client.close();
      Files.deleteIfExists(certificate);
      Files.deleteIfExists(privateKey);
      Files.deleteIfExists(caCertificate);
      Files.deleteIfExists(tlsDirectory);
    }
  }

  private static AccountTenantCreationSourceOrderRepository repository(Context context) {
    return new AccountTenantCreationSourceOrderRepository(
        context.dsl, new DraftAuthorizationFenceRepository(context.dsl));
  }

  private static void assertWriterGateDenied(Context context, String sourceKey) {
    assertThatThrownBy(
            () ->
                context.tx(
                    () ->
                        context.dsl.fetch(
                            "SELECT account_control_ui_hold_required_sources(ARRAY[?]::TEXT[])",
                            sourceKey)))
        .isInstanceOf(DataAccessException.class);
  }

  private static void assertWriterGateAllowed(Context context, String sourceKey) {
    assertThatCode(
            () ->
                context.tx(
                    () ->
                        context.dsl.fetch(
                            "SELECT account_control_ui_hold_required_sources(ARRAY[?]::TEXT[])",
                            sourceKey)))
        .doesNotThrowAnyException();
  }

  private static void seedSerializationRows(Context context, List<SourceEvidence> sources) {
    context.tx(
        () -> {
          for (SourceEvidence source : sources) {
            context.dsl.execute(
                "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?) "
                    + "ON CONFLICT DO NOTHING",
                source.key());
          }
          return null;
        });
  }

  private static List<SourceEvidence> controlledSources(Context context, Fixture fixture) {
    List<SourceEvidence> policySources =
        context.tx(
            () ->
                new AccountRecentReauthenticationPolicyRepository(context.dsl)
                    .resolveCurrentSourcesForGameCreation()
                    .sources());
    UUID actor = fixture.intent().initiatingAccountId();
    UUID creatorParty = fixture.intent().creatorPartyId();
    UUID hostedScope = UUID.randomUUID();
    List<SourceEvidence> sources =
        new ArrayList<>(
            List.of(
                controlled(
                    SourceKind.ISSUER, "firemud-account-service", "issuer controlled fixture"),
                controlled(SourceKind.ACCOUNT, actor.toString(), "account controlled fixture"),
                controlled(
                    SourceKind.GLOBAL_ROLES, actor.toString(), "global roles controlled fixture"),
                controlled(
                    SourceKind.CREATOR_PARTY,
                    creatorParty.toString(),
                    "creator party controlled fixture"),
                controlled(
                    SourceKind.HOSTED_TERMS,
                    "environment-boundary/test-environment",
                    "hosted environment controlled fixture"),
                controlled(
                    SourceKind.HOSTED_TERMS,
                    hostedScope.toString(),
                    "hosted scope controlled fixture")));
    sources.addAll(policySources);
    return sources.stream().sorted(java.util.Comparator.comparing(SourceEvidence::key)).toList();
  }

  private static SourceEvidence controlled(SourceKind kind, String scope, String value) {
    return new SourceEvidence(
        kind,
        scope,
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "1"
            : null,
        "1",
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "test-controlled-checkpoint"
            : null,
        kind == SourceKind.ISSUER || kind == SourceKind.ACCOUNT || kind == SourceKind.GLOBAL_ROLES
            ? "1"
            : null,
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static String xmin(Context c, UUID operationId) {
    return Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT xmin::text AS xmin FROM account_tenant_creation_source_orders "
                    + "WHERE creation_operation_id = ?",
                operationId))
        .get("xmin", String.class);
  }

  private static long consumptionCount(Context c, UUID evidenceId) {
    return Objects.requireNonNull(
            c.dsl.fetchOne(
                "SELECT count(*) AS count FROM account_recent_reauthentication_consumptions "
                    + "WHERE evidence_id = ?",
                evidenceId))
        .get("count", Long.class);
  }

  private static Fixture fixture(Context context) {
    Account account = context.account();
    return fixture(context, account, UUID.randomUUID());
  }

  private static Fixture fixture(Context context, Account account, UUID creatorPartyId) {
    UUID creationOperationId = UUID.randomUUID();
    var reauthentication =
        context.service.reauthenticate(
            context.request(account, UUID.randomUUID(), creationOperationId));
    var intent =
        context.tx(
            () ->
                new AccountTenantCreationIntentRepository(context.dsl, context.evidenceRepository)
                    .claim(
                        new AccountTenantCreationIntentRepository.Request(
                            UUID.randomUUID(),
                            creationOperationId,
                            UUID.randomUUID(),
                            account.getAccountUuid(),
                            "test-game",
                            "source-game",
                            "Game",
                            "controlled source-order test",
                            reauthentication.evidenceId(),
                            creatorPartyId,
                            UUID.randomUUID(),
                            1,
                            true)));

    UUID issuanceOperationId = UUID.randomUUID();
    String tokenHash = "e".repeat(64);
    byte[] activeRegistry = "test-only-active-registry".getBytes(StandardCharsets.UTF_8);
    long issuedAt = Instant.now().getEpochSecond();
    long tokenExpiry = (issuedAt + 300) * 1000;
    long preparedAt = issuedAt * 1000;
    long operationDeadline = tokenExpiry;
    long cleanupDeadline = tokenExpiry + 300_000;
    long receiptDeadline = operationDeadline + 86_400_000;
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_control_ui_issuance_operations "
                    + "(request_id, operation_id, token_jti, account_uuid, tenant_uuid, authorization_scope, caller_workload, "
                    + "caller_context_id, request_mac_key_id, request_digest, claims_payload, source_payload, bundle_payload, signer_receipt, "
                    + "issued_at_epoch_second, expires_at_epoch_second, registry_cleanup_deadline_epoch_millis, recovery_expires_at, status) "
                    + "VALUES (?, ?, ?, ?, NULL, 'ACCOUNT', 'spiffe://test-only/source-order', ?, 'test-mac', ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, 'PREPARED')",
                UUID.randomUUID(),
                issuanceOperationId,
                UUID.randomUUID(),
                account.getAccountUuid(),
                UUID.randomUUID(),
                "c".repeat(64),
                activeRegistry,
                activeRegistry,
                activeRegistry,
                activeRegistry,
                issuedAt,
                issuedAt + 300,
                cleanupDeadline,
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(issuedAt + 60), ZoneOffset.UTC)));
    context.tx(
        () ->
            context.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'CANDIDATE', token_hash = ?, "
                    + "pending_registry = ?, active_registry = ? WHERE operation_id = ?",
                tokenHash,
                activeRegistry,
                activeRegistry,
                issuanceOperationId));
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_control_ui_response_envelopes "
                    + "(operation_id, encrypted_response, owner_binding, recovery_expires_at) "
                    + "SELECT operation_id, ?, ?, recovery_expires_at "
                    + "FROM account_control_ui_issuance_operations WHERE operation_id = ?",
                activeRegistry,
                activeRegistry,
                issuanceOperationId));
    context.tx(
        () ->
            context.dsl.execute(
                "UPDATE account_control_ui_issuance_operations SET status = 'COMMITTED', "
                    + "pending_receipt = ?, committed_at = CURRENT_TIMESTAMP WHERE operation_id = ?",
                activeRegistry,
                issuanceOperationId));

    var admissionBinding =
        new AccountTenantCreationAdmissionBinding(
            intent.requestId(),
            creationOperationId,
            issuanceOperationId,
            account.getAccountUuid(),
            intent.targetNamespace(),
            intent.gameDesignCreationRequestId(),
            intent.gameDesignRequestDigest(),
            intent.requestDigest(),
            AccountControlUiCoordination.digest("SHA-256", activeRegistry),
            reauthentication.evidenceId(),
            tokenHash,
            cleanupDeadline,
            tokenExpiry,
            preparedAt,
            operationDeadline,
            receiptDeadline);
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_tenant_creation_admission_requests "
                    + "(creation_operation_id, intent_request_id, issuance_operation_id, actor_account_id, target_namespace, "
                    + "game_design_creation_request_id, game_design_request_digest, intent_digest, active_registry_digest, "
                    + "reauthentication_evidence_id, token_hash, token_cleanup_deadline_epoch_millis, token_expires_at_epoch_millis, "
                    + "prepared_at_epoch_millis, operation_deadline_epoch_millis, receipt_cleanup_deadline_epoch_millis, binding_payload, binding_digest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                creationOperationId,
                intent.requestId(),
                issuanceOperationId,
                account.getAccountUuid(),
                admissionBinding.targetNamespace(),
                admissionBinding.gameDesignCreationRequestId(),
                admissionBinding.gameDesignRequestDigest(),
                admissionBinding.intentDigest(),
                admissionBinding.activeRegistryDigest(),
                reauthentication.evidenceId(),
                tokenHash,
                cleanupDeadline,
                tokenExpiry,
                preparedAt,
                operationDeadline,
                receiptDeadline,
                admissionBinding.canonicalReceipt(),
                AccountControlUiCoordination.digest(
                    "SHA-256", admissionBinding.canonicalReceipt())));
    // Physical AOF/replica/qualification values are controlled test facts. The private Admitted
    // value is still obtained only by reading the actual V1022 durable rows.
    context.tx(
        () ->
            context.dsl.execute(
                "INSERT INTO account_tenant_creation_admissions "
                    + "(creation_operation_id, receipt_payload, receipt_digest, local_aof_count, replica_aof_count, "
                    + "protected_binding_id, qualification_fence) VALUES (?, ?, ?, 1, 1, 'test-stipulated-physical-evidence', 7)",
                creationOperationId,
                admissionBinding.canonicalReceipt(),
                AccountControlUiCoordination.digest(
                    "SHA-256", admissionBinding.canonicalReceipt())));
    var admitted =
        context.tx(
            () ->
                new AccountTenantCreationAdmissionRepository(context.dsl)
                    .readAdmitted(creationOperationId)
                    .orElseThrow());
    return new Fixture(account, intent, admitted);
  }

  private static Context context() {
    String schema = "creation_source_order_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var manager = new DataSourceTransactionManager(source);
    var transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.executeWithoutResult(
        ignored -> {
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_values "
                  + "(policy_scope, policy_version, recent_age_seconds, max_clock_skew_seconds) "
                  + "VALUES ('PLATFORM', 1, 300, 30), ('DEPLOYMENT', 1, 300, 30)");
          dsl.execute(
              "INSERT INTO account_recent_reauthentication_policy_current "
                  + "(policy_scope, policy_version) VALUES ('PLATFORM', 1), ('DEPLOYMENT', 1)");
        });
    var generations = new AccountAuthorityGenerationRepository(dsl);
    var outbox = new AccountAuthorityOutboxRepository(dsl);
    var sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
    var accounts = new AccountRepository(dsl, sourceEvidence);
    var challenges = new AccountEmailLoginChallengeRepository(dsl);
    var primary =
        new AccountServiceImpl(
            accounts,
            generations,
            sourceEvidence,
            outbox,
            mock(AccountPasswordResetOperationRepository.class),
            mock(AccountLogoutAllOperationRepository.class),
            mock(AccountSecurityStateOperationRepository.class),
            mock(AccountPasswordResetDraftSourceChangeRepository.class),
            mock(AccountAuditOutboxRepository.class),
            mock(AccountConnectScopeRepository.class),
            mock(AccountJoinOperationRepository.class),
            challenges,
            null,
            null,
            Mappers.getMapper(AccountMapper.class),
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
            null,
            null,
            null,
            null,
            null,
            null,
            manager);
    var evidence = new AccountRecentReauthenticationRepository(dsl);
    var policies = new AccountRecentReauthenticationPolicyRepository(dsl);
    var service =
        new AccountRecentReauthenticationService(
            primary,
            accounts,
            generations,
            evidence,
            policies,
            manager,
            Clock.systemUTC(),
            reauthenticationCryptography(Clock.systemUTC()));
    return new Context(dsl, accounts, evidence, service, transaction);
  }

  private static AccountControlUiResponseCryptography reauthenticationCryptography(Clock clock) {
    var custody = reauthenticationCustody.resolve(UUID.randomUUID().toString());
    try {
      for (String purpose : new String[] {"encryption", "request-mac"}) {
        var directory = java.nio.file.Files.createDirectories(custody.resolve(purpose));
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) (purpose.equals("encryption") ? 11 : 29));
        java.nio.file.Files.writeString(
            directory.resolve("keyring"),
            "firemud-account-response-envelope-keyring-v1\nactive test-key "
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
                + "\n");
      }
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Controlled reauthentication custody setup failed", failure);
    }
    return new AccountControlUiResponseCryptography(new AccountControlUiKeyring(custody), clock);
  }

  private static String hash(String secret) {
    char[] chars = secret.toCharArray();
    var argon = Argon2Factory.create();
    try {
      return argon.hash(2, 4096, 1, chars);
    } finally {
      argon.wipeArray(chars);
    }
  }

  private record Fixture(
      Account account,
      AccountTenantCreationIntentRepository.OriginalIntent intent,
      AccountTenantCreationAdmissionRepository.Admitted admitted) {}

  private record Context(
      DSLContext dsl,
      AccountRepository accounts,
      AccountRecentReauthenticationRepository evidenceRepository,
      AccountRecentReauthenticationService service,
      TransactionTemplate transaction) {
    <T> T tx(Supplier<T> action) {
      return transaction.execute(ignored -> action.get());
    }

    Account account() {
      Account account = new Account();
      String suffix = UUID.randomUUID().toString();
      account.setUsername("creation-source-order-" + suffix);
      account.setEmail(suffix + "@example.test");
      account.setPasswordHash(hash(PASSWORD));
      account.setRole("player");
      account.setLoginAuthModes("PASSWORD");
      account.setLifecycleState(AccountLifecycleState.ACTIVE);
      return tx(() -> accounts.save(account));
    }

    AccountRecentReauthenticationService.Request request(
        Account account, UUID requestId, UUID operationId) {
      return new AccountRecentReauthenticationService.Request(
          requestId,
          account.getAccountUuid(),
          AccountRecentReauthenticationPolicy.PURPOSE,
          operationId,
          account.getEmail(),
          PASSWORD);
    }
  }

  /** Controlled in-process client transport; its synthetic peer is not physical mTLS evidence. */
  private static final class ControlledOutcomeChannel extends ManagedChannel {
    private final GameTenantCreationOriginalOutcomeEvidence evidence;
    private final String trustedNamespace;
    private final Status failure;
    private final UnaryOperator<ResolveOriginalTenantCreationOutcomeResponse> responseMutation;
    private final Attributes attributes;
    private boolean shutdown;

    private ControlledOutcomeChannel(
        GameTenantCreationOriginalOutcomeEvidence evidence,
        String trustedNamespace,
        Status failure,
        UnaryOperator<ResolveOriginalTenantCreationOutcomeResponse> responseMutation)
        throws Exception {
      this.evidence = evidence;
      this.trustedNamespace = trustedNamespace;
      this.failure = failure;
      this.responseMutation = responseMutation;
      X509Certificate peerCertificate = mock(X509Certificate.class);
      List<List<?>> uriSans = new ArrayList<>();
      uriSans.add(
          List.of(6, "spiffe://firemud/ns/" + trustedNamespace + "/sa/game-design-service"));
      when(peerCertificate.getSubjectAlternativeNames()).thenReturn(uriSans);
      when(peerCertificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});
      SSLSession session = mock(SSLSession.class);
      when(session.getPeerCertificates()).thenReturn(new Certificate[] {peerCertificate});
      this.attributes =
          Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build();
    }

    @Override
    public String authority() {
      return "controlled-game-design";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options) {
      return new ClientCall<>() {
        private Listener<RespT> listener;
        private ReqT request;

        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
          this.listener = listener;
          listener.onHeaders(new Metadata());
        }

        @Override
        public void request(int count) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {
          if (failure != null) {
            listener.onClose(failure, new Metadata());
            return;
          }
          var incoming =
              GameTenantCreationOriginalOutcomeGrpcCodec.fromReadRequest(
                  trustedNamespace, (ResolveOriginalTenantCreationOutcomeRequest) request);
          ReadRequest responseRequest =
              new ReadRequest(incoming.readRequestId(), evidence.binding());
          ResolveOriginalTenantCreationOutcomeResponse response =
              GameTenantCreationOriginalOutcomeGrpcCodec.toReadResponse(responseRequest, evidence);
          response = responseMutation.apply(response);
          try (InputStream serialized = method.getResponseMarshaller().stream((RespT) response)) {
            listener.onMessage(method.parseResponse(serialized));
          } catch (java.io.IOException failure) {
            listener.onClose(Status.INTERNAL.withCause(failure), new Metadata());
            return;
          }
          listener.onClose(Status.OK, new Metadata());
        }

        @Override
        public void sendMessage(ReqT message) {
          this.request = message;
        }

        @Override
        public Attributes getAttributes() {
          return attributes;
        }
      };
    }

    @Override
    public ManagedChannel shutdown() {
      shutdown = true;
      return this;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public ManagedChannel shutdownNow() {
      shutdown = true;
      return this;
    }

    @Override
    public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
      return shutdown;
    }
  }
}
