package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadGrpcCodec;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicDraftDesignDigestServiceImpl;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicGrpcService;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.test.TestContainerImages;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual current-default GL retention, immutable owner read, digest service and receiver.
 * Account authorization and GD source responses are explicitly controlled upstream inputs;
 * direct handler invocation supplies controlled peer context, not authenticated socket/TLS proof.
 * No production composition, genuine upstream authority or registration is established.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameLogicPublicationDigestReadPostgresIntegrationTest {
  private static final String NAMESPACE = "test";
  @Container static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void actualRetainedOwnerFlowsThroughDigestServiceAndReceiverWithExactReplayAndRefusals()
      throws Exception {
    var f = fixture();
    var authorization = authorization();
    var accountReader = mock(GameLogicIntakeAuthorizationReadClient.class);
    when(accountReader.read(any())).thenAnswer(invocation -> {
      GameLogicIntakeAuthorizationReadEvidence.Request request = invocation.getArgument(0);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(request.binding().canonicalBytes()).isEqualTo(authorization.canonicalBytes());
      return new GameLogicIntakeAuthorizationReadEvidence(request);
    });
    var sourceReader = mock(GameplayRuleSourceReadClient.class);
    when(sourceReader.read(any())).thenAnswer(invocation -> {
      GameplayRuleSourceReadEvidence.Request request = invocation.getArgument(0);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(request.proof()).isInstanceOf(GameplayRuleSourceReadEvidence.Finalized.class);
      assertThat(request.proof().canonicalBytes()).isEqualTo(authorization.canonicalBytes());
      return new GameplayRuleSourceReadEvidence(request, authorization.source());
    });
    var intake = new GameLogicGameplayRuleIntakeService(
        f.repository(), f.manager(), accountReader, sourceReader, NAMESPACE);
    var terminal = asGameDesign(() -> intake.retain(authorization));
    var reader = new GameLogicPublicationSourceReadService(f.repository(), NAMESPACE);
    var digest = new GameLogicDraftDesignDigestServiceImpl(reader);
    var receiver = receiver(digest);
    var binding = publicationBinding(authorization);
    var request = GameLogicPublicationSourceReadGrpcCodec.toRequest(binding);
    String retained = row(f, authorization.operationId());

    var response = asGameDesign(() -> invoke(receiver, request));
    assertThat(response.hasError()).isFalse();
    var evidence = GameLogicPublicationSourceReadGrpcCodec.fromResponse(binding, response, NAMESPACE);
    assertThat(evidence.terminal().canonicalBytes()).isEqualTo(terminal.canonicalBytes());
    assertThat(evidence.terminal().selectedSourceBytes()).isEqualTo(authorization.source().canonicalBytes());
    assertThat(evidence.manifestDigest()).isEqualTo(authorization.source().manifest().digest());
    assertThat(asGameDesign(() -> digest.getDraftDesignDigest(binding)).terminalBytes()).isEqualTo(terminal.canonicalBytes());
    assertThat(asGameDesign(() -> invoke(receiver, request))).isEqualTo(response);

    var missing = copyAuthorization(authorization, UUID.randomUUID(), authorization.fenceId(), authorization.intakeRequestId(), authorization.source());
    assertError(receiver, GameLogicPublicationSourceReadGrpcCodec.toRequest(publicationBinding(missing)), "FAILED_PRECONDITION");
    var changed = copyAuthorization(authorization, authorization.operationId(), UUID.randomUUID(), authorization.intakeRequestId(), authorization.source());
    assertError(receiver, GameLogicPublicationSourceReadGrpcCodec.toRequest(publicationBinding(changed)), "ALREADY_EXISTS");
    assertError(receiver, request.toBuilder().clearSourceReadBinding().clearSourceReadBindingDigest().build(), "INVALID_ARGUMENT");
    assertError(receiver(new GameLogicDraftDesignDigestServiceImpl()), request, "UNSUPPORTED_SCOPE");

    var wrongPeer = GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/world-management-service").orElseThrow();
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    var previous = context.attach();
    try { assertThat(invoke(receiver, request).getError().getCode()).isEqualTo("PERMISSION_DENIED"); }
    finally { context.detach(previous); }
    SessionContext.setContext("controlled-user", List.of(), Map.of());
    assertError(receiver, request, "PERMISSION_DENIED");
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try { assertError(receiver, request, "FAILED_PRECONDITION"); }
    finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    assertThat(row(f, authorization.operationId())).isEqualTo(retained);

    var abortedAuthorization = authorization();
    var abortAccountReader = mock(GameLogicIntakeAuthorizationReadClient.class);
    when(abortAccountReader.read(any())).thenAnswer(invocation -> {
      GameLogicIntakeAuthorizationReadEvidence.Request heldRequest = invocation.getArgument(0);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(heldRequest.binding().canonicalBytes())
          .isEqualTo(abortedAuthorization.canonicalBytes());
      return new GameLogicIntakeAuthorizationReadEvidence(heldRequest);
    });
    var unusedAbortSourceReader = mock(GameplayRuleSourceReadClient.class);
    var abortOwner = new GameLogicGameplayRuleIntakeService(
        f.repository(), f.manager(), abortAccountReader, unusedAbortSourceReader, NAMESPACE);
    asGameDesign(() -> abortOwner.abort(abortedAuthorization));
    verify(abortAccountReader).read(any());
    verify(unusedAbortSourceReader, never()).read(any());
    String aborted = row(f, abortedAuthorization.operationId());
    assertError(receiver, GameLogicPublicationSourceReadGrpcCodec.toRequest(publicationBinding(abortedAuthorization)), "FAILED_PRECONDITION");
    assertThat(row(f, abortedAuthorization.operationId())).isEqualTo(aborted);
    assertThat(row(f, authorization.operationId())).isEqualTo(retained);
  }

  private static GameLogicGrpcService receiver(GameLogicDraftDesignDigestServiceImpl digest) {
    // Unrelated gameplay owners are unused by this focused direct receiver composition.
    return new GameLogicGrpcService(null, null, null, null, null, null, digest, null,
        new SimpleMeterRegistry(), new PublicationReadGuard(NAMESPACE));
  }

  private static GetDraftDesignDigestResponse invoke(GameLogicGrpcService receiver,
      GetDraftDesignDigestRequest request) {
    var result = new AtomicReference<GetDraftDesignDigestResponse>();
    receiver.getDraftDesignDigest(request, new StreamObserver<>() {
      @Override public void onNext(GetDraftDesignDigestResponse value) { result.set(value); }
      @Override public void onError(Throwable failure) { throw new AssertionError(failure); }
      @Override public void onCompleted() {}
    });
    assertThat(result.get()).isNotNull();
    return result.get();
  }

  private static void assertError(GameLogicGrpcService receiver, GetDraftDesignDigestRequest request,
      String code) throws Exception {
    var response = asGameDesign(() -> invoke(receiver, request));
    assertThat(response.getError().getCode()).isEqualTo(code);
    assertThat(response.getRetainedIntakeTerminal().isEmpty()).isTrue();
    assertThat(response.getContentDigest()).isEmpty();
    assertThat(response.getAbilitySchemaDigest()).isEmpty();
  }

  private static String row(Fixture f, UUID operation) {
    return f.dsl().fetchSingle("SELECT row_to_json(t)::TEXT || ':' || t.xmin::TEXT FROM game_logic_gameplay_rule_intake_terminal t WHERE operation_id = ?", operation).get(0, String.class);
  }

  private static Fixture fixture() {
    String schema = "gl_intake_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    dataSource.setSchema(schema);
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .table("flyway_schema_history")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    var transactionManager = new DataSourceTransactionManager(dataSource);
    return new Fixture(dsl, transactionManager, new GameLogicGameplayRuleIntakeRepository(dsl));
  }

  private static <T> T asGameDesign(java.util.concurrent.Callable<T> action) throws Exception {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-design-service", NAMESPACE, "game-design-service");
    Context previous = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).attach();
    try {
      return action.call();
    } finally {
      Context.current().detach(previous);
    }
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    var actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 1, "private", 2, "private", "NEW_GAME_ROW");
    var binding =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", binding.canonicalJson(),
                    "bindingDigest", binding.digest(),
                    "sourceEpoch", "1",
                    "inheritedCommitId", "",
                    "genesisReceiptId", UUID.randomUUID().toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {4, 5, 6});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        source,
        List.of(accountSource));
  }

  private static GameLogicIntakeAuthorizationBinding copyAuthorization(
      GameLogicIntakeAuthorizationBinding original,
      UUID operationId,
      UUID fenceId,
      UUID intakeRequestId,
      GameplayRuleSelectedSource source) {
    return new GameLogicIntakeAuthorizationBinding(
        operationId,
        fenceId,
        intakeRequestId,
        original.actorAccountId(),
        source,
        original.sources());
  }

  private static GameLogicPublicationSourceReadBinding publicationBinding(
      GameLogicIntakeAuthorizationBinding authorization) {
    var target = authorization.source().binding().target();
    return new GameLogicPublicationSourceReadBinding(
        PublicationDigestRequestBinding.full(
            authorization.tenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            "publication-read"),
        authorization);
  }

  private record Fixture(DSLContext dsl, DataSourceTransactionManager manager,
      GameLogicGameplayRuleIntakeRepository repository) {}
}
