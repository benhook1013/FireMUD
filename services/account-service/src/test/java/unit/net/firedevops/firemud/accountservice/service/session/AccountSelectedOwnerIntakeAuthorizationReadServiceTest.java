package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic Account read tests; they do not establish PostgreSQL or owner-terminal proof. */
class AccountSelectedOwnerIntakeAuthorizationReadServiceTest {
  private final AccountSelectedOwnerIntakeSourceReservationRepository repository =
      mock(AccountSelectedOwnerIntakeSourceReservationRepository.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  @AfterEach
  void clearCallerAndTransactionContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void readsExactEntityAndAutomationBindingsInIndependentReadCommittedTransactions() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    var service = newService();

    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = binding(owner);
      var request = SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding);
      asReader(binding, () -> service.requireHeld(request));
      verify(repository).readFinalAuthorization(binding);
    }

    var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, times(2)).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allSatisfy(
            definition -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(definition.isReadOnly()).isFalse();
            });
  }

  @Test
  void rejectsMissingWrongOwnerAndEndUserContextsBeforeRepositoryAccess() {
    var service = newService();
    var entity = binding(Owner.ENTITY_MANAGEMENT);
    var request = SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", entity);

    assertCode(Status.Code.UNAUTHENTICATED, () -> service.requireHeld(request));
    asPeer(
        "test",
        "automation-scripting-service",
        () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.requireHeld(request)));

    SessionContext.setContext("44", List.of(), Map.of());
    try {
      asReader(
          entity,
          () -> assertCode(Status.Code.PERMISSION_DENIED, () -> service.requireHeld(request)));
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void rejectsAmbientSqlBeforeStartingOwnerRead() {
    var service = newService();
    var binding = binding(Owner.ENTITY_MANAGEMENT);
    var request = SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      asReader(
          binding,
          () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.requireHeld(request)));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void distinguishesAbsentOrChangedAuthorizationFromUnavailableStorage() {
    when(transactions.getTransaction(any(TransactionDefinition.class)))
        .thenAnswer(ignored -> new SimpleTransactionStatus());
    var service = newService();
    var binding = binding(Owner.AUTOMATION_SCRIPTING);
    var request = SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding);

    org.mockito.Mockito.doThrow(new IllegalArgumentException("not available"))
        .when(repository)
        .readFinalAuthorization(binding);
    asReader(
        binding,
        () -> assertCode(Status.Code.FAILED_PRECONDITION, () -> service.requireHeld(request)));

    org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
        .when(repository)
        .readFinalAuthorization(binding);
    asReader(
        binding, () -> assertCode(Status.Code.UNAVAILABLE, () -> service.requireHeld(request)));
  }

  private AccountSelectedOwnerIntakeAuthorizationReadService newService() {
    return new AccountSelectedOwnerIntakeAuthorizationReadService(repository, transactions, "test");
  }

  static SelectedOwnerIntakeAuthorizationBinding binding(Owner owner) {
    UUID tenant = id("11111111-1111-4111-8111-111111111111");
    UUID actor = id("99999999-9999-4999-8999-999999999999");
    UUID operation = id("55555555-5555-4555-8555-555555555555");
    UUID fence = id("66666666-6666-4666-8666-666666666666");
    UUID intake = id("77777777-7777-4777-8777-777777777777");
    var selected = selected(tenant);
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner, "test", operation, fence, intake, actor, selected);
    return new SelectedOwnerIntakeAuthorizationBinding(content(scope), sources(actor, tenant));
  }

  private static SelectedOwnerIntakeSourceContent content(
      SelectedOwnerIntakeSourceReadScope scope) {
    var out = new ByteArrayOutputStream();
    frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = snapshot(family, scope.selected());
      frame(out, family);
      frame(out, snapshot);
      frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] snapshot(String family, DraftCommitBinding selected) {
    if ("GAMEPLAY_RULE".equals(family)) {
      return new GameplayRuleSelectedSource(
              GameplayRuleManifest.canonical(
                  Map.of(
                      "schema",
                      "game-design-gameplay-rule-source-snapshot/v1",
                      "bindingJson",
                      selected.canonicalJson(),
                      "bindingDigest",
                      selected.digest(),
                      "sourceEpoch",
                      "0",
                      "inheritedCommitId",
                      "",
                      "genesisReceiptId",
                      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                      "manifestJson",
                      GameplayRuleManifest.explicitEmpty().canonicalJson(),
                      "entries",
                      List.of())))
          .canonicalBytes();
    }
    if ("TEMPLATE_CONFIG".equals(family))
      return new TemplateConfigSourceSnapshot(
              selected, "0", null, id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), List.of())
          .canonicalBytes();
    return GameplayRuleManifest.canonical(
            Map.of(
                "schema",
                "synthetic-test-only/v1",
                "bindingJson",
                selected.canonicalJson(),
                "bindingDigest",
                selected.digest(),
                "family",
                family))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static List<SourceEvidence> sources(UUID actor, UUID tenant) {
    return List.of(
        evidence(SourceKind.TENANT, tenant.toString(), "tenant"),
        evidence(SourceKind.ACCOUNT, actor.toString(), "account"),
        evidence(SourceKind.MEMBERSHIP, actor + "/" + tenant, "membership"));
  }

  private static SourceEvidence evidence(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static DraftCommitBinding selected(UUID tenant) {
    UUID version = id("22222222-2222-4222-8222-222222222222");
    return DraftCommitBinding.create(
        new DraftCommitBinding.TargetProof(
            tenant, version, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
        id("33333333-3333-4333-8333-333333333333"),
        id("44444444-4444-4444-8444-444444444444"),
        "base-commit-0",
        List.of(
            new DraftCommitBinding.RevisionPayload(
                "0",
                id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"),
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "{}")),
        List.of(
            new DraftCommitBinding.AffectedUnit(
                Owner.GAME_DESIGN_CONTROL_PLANE,
                "TEMPLATE_CONFIG",
                version.toString(),
                "TEMPLATE_CONFIG",
                "ALL",
                "0")));
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private static void asReader(SelectedOwnerIntakeAuthorizationBinding binding, Runnable action) {
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri(binding.intendedReader()).orElseThrow());
    peer.run(action);
  }

  private static void asPeer(String namespace, String workload, Runnable action) {
    var peer =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                    .orElseThrow());
    peer.run(action);
  }

  private static void assertCode(Status.Code expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(expected));
  }
}
