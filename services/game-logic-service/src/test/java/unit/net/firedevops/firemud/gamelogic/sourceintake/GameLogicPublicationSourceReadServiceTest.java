package net.firedevops.firemud.gamelogic.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Uses a repository stub to exercise the owner read boundary. It does not claim PostgreSQL storage
 * provenance; the repository's existing physical readback is responsible for indexed-row proof.
 */
class GameLogicPublicationSourceReadServiceTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearThreadState() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void returnsExactStoredTerminalAndBothDistinctCanonicalDigests() {
    var fixture = new Fixture();
    var authorization = authorization();
    var stored = retained(authorization);
    fixture.store(stored);
    var binding = request(authorization);

    var result = asGameDesign(() -> fixture.service.read(binding));

    assertThat(result.binding()).isSameAs(binding);
    assertThat(result.terminal()).isSameAs(stored);
    assertThat(result.operation().canonicalBytes())
        .containsExactly(stored.operation().canonicalBytes());
    assertThat(result.authorizationBytes()).containsExactly(authorization.canonicalBytes());
    assertThat(result.selectedSourceBytes())
        .containsExactly(authorization.source().canonicalBytes());
    assertThat(result.manifestBytes())
        .containsExactly(authorization.source().manifest().canonicalBytes());
    assertThat(result.manifestDigest()).isEqualTo(authorization.source().manifest().digest());
    assertThat(result.abilitySchemaDigest())
        .isEqualTo(GameplayAbilitySchemaProjection.digest(authorization.source().manifest()))
        .isNotEqualTo(result.manifestDigest());
    assertThat(result.digestSchemaVersion()).isEqualTo(1);
    assertThat(result.canonicalization()).isEqualTo("RFC8785");
    assertThat(result.terminalBytes()).containsExactly(stored.canonicalBytes());

    byte[] returnedSource = result.selectedSourceBytes();
    returnedSource[0] ^= 1;
    assertThat(result.selectedSourceBytes())
        .containsExactly(authorization.source().canonicalBytes());
    verify(fixture.repository).findTerminal(authorization.operationId());
  }

  @Test
  void exactRetryReadsTheOriginalTerminalWithoutAccountOrLatestSourceReads() {
    var fixture = new Fixture();
    var authorization = authorization();
    var stored = retained(authorization);
    fixture.store(stored);
    var binding = request(authorization);

    var first = asGameDesign(() -> fixture.service.read(binding));
    var retry = asGameDesign(() -> fixture.service.read(binding));

    assertThat(retry.terminal()).isSameAs(stored);
    assertThat(retry.terminalBytes()).containsExactly(first.terminalBytes());
    assertThat(retry.binding().canonicalBytes()).containsExactly(binding.canonicalBytes());
    // This service has only the owner repository dependency, so settlement cannot trigger a HELD
    // read and retry cannot resolve a newer source snapshot.
    verify(fixture.repository, times(2)).findTerminal(authorization.operationId());
  }

  @Test
  void absentAndAbortedOperationsFailClosed() {
    var fixture = new Fixture();
    var authorization = authorization();
    var binding = request(authorization);

    assertCode(
        Status.Code.FAILED_PRECONDITION, () -> asGameDesign(() -> fixture.service.read(binding)));

    var aborted =
        GameLogicGameplayRuleIntakeTerminal.aborted(
            new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization));
    fixture.store(aborted);
    assertCode(
        Status.Code.FAILED_PRECONDITION, () -> asGameDesign(() -> fixture.service.read(binding)));
  }

  @Test
  void onlyExactSameNamespaceGameDesignCanReadAndAuthenticationPrecedesBindingInspection() {
    var fixture = new Fixture();
    List<GrpcPeerIdentity> deniedPeers =
        List.of(
            peer("test", "account-service"),
            peer("test", "game-logic-service"),
            peer("test", "world-management-service"),
            peer("other", "game-design-service"));

    for (GrpcPeerIdentity peer : deniedPeers) {
      assertCode(
          Status.Code.PERMISSION_DENIED, () -> asPeer(peer, () -> fixture.service.read(null)));
    }
    assertCode(Status.Code.UNAUTHENTICATED, () -> fixture.service.read(null));
    verify(fixture.repository, never()).findTerminal(any(UUID.class));

    assertCode(Status.Code.INVALID_ARGUMENT, () -> asGameDesign(() -> fixture.service.read(null)));
    verify(fixture.repository, never()).findTerminal(any(UUID.class));
  }

  @Test
  void ambientSqlOrSynchronizationIsRejectedBeforeStorageRead() {
    var fixture = new Fixture();
    var authorization = authorization();
    var binding = request(authorization);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertCode(
          Status.Code.FAILED_PRECONDITION, () -> asGameDesign(() -> fixture.service.read(binding)));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertCode(
          Status.Code.FAILED_PRECONDITION, () -> asGameDesign(() -> fixture.service.read(binding)));
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verify(fixture.repository, never()).findTerminal(any(UUID.class));
  }

  @Test
  void changedOperationFenceCommitAndSelectedSourceAreRejected() {
    var original = authorization();
    var stored = retained(original);

    var changedOperation = copy(original, UUID.randomUUID(), original.fenceId(), original.source());
    assertStoredMismatch(stored, changedOperation);

    var changedFence = copy(original, original.operationId(), UUID.randomUUID(), original.source());
    assertStoredMismatch(stored, changedFence);

    var originalCommit = original.source().binding();
    var changedCommit =
        DraftCommitBinding.create(
            originalCommit.target(),
            originalCommit.requestId(),
            UUID.randomUUID(),
            originalCommit.baseCommitId(),
            originalCommit.revisions(),
            originalCommit.affectedUnits());
    var changedCommitAuthorization =
        copy(original, original.operationId(), original.fenceId(), source(changedCommit, "1"));
    assertStoredMismatch(stored, changedCommitAuthorization);

    var changedSource =
        copy(original, original.operationId(), original.fenceId(), source(originalCommit, "2"));
    assertStoredMismatch(stored, changedSource);
  }

  @Test
  void changedScopeAliasesAndManifestReadbackAreRejected() {
    var authorization = authorization();
    var wrongNumericVersion =
        PublicationDigestRequestBinding.full(
            authorization.tenantId().toString(),
            Long.toString(authorization.source().binding().target().gameDesignVersionRowId() + 1),
            "publish-request");
    assertThatThrownBy(
            () -> new GameLogicPublicationSourceReadBinding(wrongNumericVersion, authorization))
        .isInstanceOf(IllegalArgumentException.class);

    var patch =
        PublicationDigestRequestBinding.patch(
            authorization.tenantId().toString(), "1", "patch-v1", "publish-request");
    assertThatThrownBy(() -> new GameLogicPublicationSourceReadBinding(patch, authorization))
        .isInstanceOf(IllegalArgumentException.class);

    var fixture = new Fixture();
    var validTerminal = retained(authorization);
    var corruptReadback = mock(GameLogicGameplayRuleIntakeTerminal.class);
    when(corruptReadback.operation()).thenReturn(validTerminal.operation());
    when(corruptReadback.authorizationBytes()).thenReturn(validTerminal.authorizationBytes());
    when(corruptReadback.outcome())
        .thenReturn(GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED);
    when(corruptReadback.selectedSourceBytes()).thenReturn(validTerminal.selectedSourceBytes());
    when(corruptReadback.manifestBytes()).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
    fixture.store(corruptReadback);

    assertCode(
        Status.Code.ALREADY_EXISTS,
        () -> asGameDesign(() -> fixture.service.read(request(authorization))));
  }

  private static void assertStoredMismatch(
      GameLogicGameplayRuleIntakeTerminal stored,
      GameLogicIntakeAuthorizationBinding requestedAuthorization) {
    var fixture = new Fixture();
    fixture.store(stored);
    assertCode(
        Status.Code.ALREADY_EXISTS,
        () -> asGameDesign(() -> fixture.service.read(request(requestedAuthorization))));
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(expected));
  }

  private static GameLogicIntakeAuthorizationBinding copy(
      GameLogicIntakeAuthorizationBinding original,
      UUID operationId,
      UUID fenceId,
      GameplayRuleSelectedSource source) {
    return new GameLogicIntakeAuthorizationBinding(
        operationId,
        fenceId,
        original.intakeRequestId(),
        original.actorAccountId(),
        source,
        original.sources());
  }

  private static GameLogicIntakeAuthorizationBinding authorization() {
    var actor = UUID.randomUUID();
    var selected = source(commitBinding(), "1");
    var accountSource =
        new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence(
            net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind
                .ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        selected,
        List.of(accountSource));
  }

  private static DraftCommitBinding commitBinding() {
    var target =
        new DraftCommitBinding.TargetProof(
            UUID.randomUUID(), UUID.randomUUID(), 17, "private", 29, "private", "NEW_GAME_ROW");
    return DraftCommitBinding.create(
        target,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "genesis",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0", UUID.randomUUID(), DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE, "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                "GAMEPLAY_RULE_SET",
                target.canonicalVersionId().toString(),
                "GAMEPLAY_RULE_SET",
                "0",
                "0")));
  }

  private static GameplayRuleSelectedSource source(DraftCommitBinding binding, String epoch) {
    return new GameplayRuleSelectedSource(
        GameplayRuleManifest.canonical(
            Map.of(
                "schema", "game-design-gameplay-rule-source-snapshot/v1",
                "bindingJson", binding.canonicalJson(),
                "bindingDigest", binding.digest(),
                "sourceEpoch", epoch,
                "inheritedCommitId", "",
                "genesisReceiptId", UUID.randomUUID().toString(),
                "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                "entries", List.of())));
  }

  private static GameLogicPublicationSourceReadBinding request(
      GameLogicIntakeAuthorizationBinding authorization) {
    var target = authorization.source().binding().target();
    return new GameLogicPublicationSourceReadBinding(
        PublicationDigestRequestBinding.full(
            authorization.tenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            "publish-request"),
        authorization);
  }

  private static GameLogicGameplayRuleIntakeTerminal retained(
      GameLogicIntakeAuthorizationBinding authorization) {
    return GameLogicGameplayRuleIntakeTerminal.retained(
        new GameLogicGameplayRuleIntakeOperation(NAMESPACE, authorization),
        authorization.source().canonicalBytes(),
        authorization.source().manifest().canonicalBytes());
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static <T> T asGameDesign(java.util.function.Supplier<T> action) {
    return asPeer(peer(NAMESPACE, "game-design-service"), action);
  }

  private static <T> T asPeer(GrpcPeerIdentity peer, java.util.function.Supplier<T> action) {
    return asContext(Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer), action);
  }

  private static <T> T asContext(Context context, java.util.function.Supplier<T> action) {
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static final class Fixture {
    private final GameLogicGameplayRuleIntakeRepository repository =
        mock(GameLogicGameplayRuleIntakeRepository.class);
    private final AtomicReference<GameLogicGameplayRuleIntakeTerminal> terminal =
        new AtomicReference<>();
    private final GameLogicPublicationSourceReadService service =
        new GameLogicPublicationSourceReadService(repository, NAMESPACE);

    private Fixture() {
      when(repository.findTerminal(any(UUID.class)))
          .thenAnswer(invocation -> Optional.ofNullable(terminal.get()));
    }

    private void store(GameLogicGameplayRuleIntakeTerminal value) {
      terminal.set(value);
    }
  }
}
