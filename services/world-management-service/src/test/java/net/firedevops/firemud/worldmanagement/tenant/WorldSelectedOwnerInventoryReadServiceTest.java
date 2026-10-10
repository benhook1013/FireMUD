package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.TemplateConfigSourceSnapshot;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.PublishIntent;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding.VisibilityFence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic exact source/freeze fixtures; Account and retained storage are explicit boundaries. */
class WorldSelectedOwnerInventoryReadServiceTest {
  private static final String NAMESPACE = "firemud";

  @AfterEach
  void clearRequestContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesOwnerWorkloadBeforeParsingAndRequiresIndependentRead() {
    var inventory = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
    var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
    var orchestrator = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventory, account);
    var receiver = new WorldSelectedOwnerInventoryReadGrpcService(NAMESPACE, orchestrator);
    var malformed = ReadSelectedOwnerWorldInventoryRequest.getDefaultInstance();

    var anonymous = new RecordingObserver();
    withoutPeer(() -> receiver.readSelectedOwnerWorldInventory(malformed, anonymous));
    assertDenied(anonymous, Status.Code.UNAUTHENTICATED);

    var wrongWorkload = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "game-design-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, wrongWorkload));
    assertDenied(wrongWorkload, Status.Code.PERMISSION_DENIED);

    var wrongNamespace = new RecordingObserver();
    withPeer(
        peer("other", "entity-management-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, wrongNamespace));
    assertDenied(wrongNamespace, Status.Code.PERMISSION_DENIED);

    SessionContext.setContext(
        "11111111-1111-4111-8111-111111111111", java.util.List.of(), java.util.Map.of());
    var endUser = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "automation-scripting-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, endUser));
    assertDenied(endUser, Status.Code.PERMISSION_DENIED);
    SessionContext.clear();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var ambient = new RecordingObserver();
      withPeer(
          peer(NAMESPACE, "entity-management-service"),
          () -> receiver.readSelectedOwnerWorldInventory(malformed, ambient));
      assertDenied(ambient, Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    var authenticatedMalformed = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "entity-management-service"),
        () -> receiver.readSelectedOwnerWorldInventory(malformed, authenticatedMalformed));
    assertDenied(authenticatedMalformed, Status.Code.INVALID_ARGUMENT);

    var authenticatedAutomationMalformed = new RecordingObserver();
    withPeer(
        peer(NAMESPACE, "automation-scripting-service"),
        () ->
            receiver.readSelectedOwnerWorldInventory(malformed, authenticatedAutomationMalformed));
    assertDenied(authenticatedAutomationMalformed, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(inventory, account);
  }

  @Test
  void accountConfirmationPrecedesTheExactRetainedReadForBothOwners() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = fixture(owner);
      var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
      var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
      var retainedInventory = mock(WorldSelectedPublicationArtifactInventoryEvidence.class);
      when(retainedInventory.freezeEvidence()).thenReturn(fixture.freezeEvidence());
      when(retainedInventory.canonicalBytes()).thenReturn(new byte[] {1, 2, 3});
      when(retainedInventory.digest()).thenReturn("sha256:" + "a".repeat(64));

      var callOrder = new ArrayList<String>();
      when(account.readHeld(any()))
          .thenAnswer(
              invocation -> {
                var request =
                    (SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request)
                        invocation.getArgument(0);
                callOrder.add("account");
                assertThat(request.binding().canonicalBytes())
                    .containsExactly(fixture.binding().canonicalBytes());
                assertThat(request.intendedReader())
                    .isEqualTo("spiffe://firemud/ns/firemud/sa/world-management-service");
                assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
                assertThat(request.readRequestId()).isNotEqualTo(fixture.request().readRequestId());
                assertThat(request.closureReadPurpose())
                    .isEqualTo(
                        owner == Owner.ENTITY_MANAGEMENT
                            ? "ENTITY_INTAKE_WORLD_CLOSURE_READ"
                            : "AUTOMATION_INTAKE_WORLD_CLOSURE_READ");
                return new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(request);
              });
      when(inventoryRead.readRetained(fixture.freezeEvidence()))
          .thenAnswer(
              invocation -> {
                callOrder.add("retained-world");
                return retainedInventory;
              });

      var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);
      var result =
          withPeer(peer(NAMESPACE, serviceName(owner)), () -> service.read(fixture.request()));

      assertThat(callOrder).containsExactly("account", "retained-world");
      assertThat(result.request()).isEqualTo(fixture.request());
      assertThat(result.request().authorizationBinding().canonicalBytes())
          .containsExactly(fixture.binding().canonicalBytes());
      assertThat(result.inventory()).isSameAs(retainedInventory);
      verify(account).readHeld(any());
      verify(inventoryRead).readRetained(fixture.freezeEvidence());
    }
  }

  @Test
  void absentOrChangedAccountEchoNeverReadsWorldStorage() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = fixture(owner);
      for (boolean absent : List.of(true, false)) {
        var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
        var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
        when(account.readHeld(any()))
            .thenAnswer(
                invocation -> {
                  var expected =
                      (SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request)
                          invocation.getArgument(0);
                  if (absent) return null;
                  var changed =
                      SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                          NAMESPACE, fixture.binding());
                  while (changed.readRequestId().equals(expected.readRequestId())) {
                    changed =
                        SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                            NAMESPACE, fixture.binding());
                  }
                  return new SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence(changed);
                });
        var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);

        assertThatThrownBy(
                () ->
                    withPeer(
                        peer(NAMESPACE, serviceName(owner)), () -> service.read(fixture.request())))
            .isInstanceOf(SecurityException.class);
        verify(inventoryRead, never()).readRetained(any());
      }
    }
  }

  @Test
  void domainServiceRejectsWrongRecipientEndUserAndAmbientSqlBeforeAccountCall() {
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var fixture = fixture(owner);
      var inventoryRead = mock(WorldSelectedPublicationArtifactInventoryReadService.class);
      var account = mock(SelectedOwnerIntakeWorldClosureAuthorizationReadClient.class);
      var service = new WorldSelectedOwnerInventoryReadService(NAMESPACE, inventoryRead, account);
      String caller = serviceName(owner);
      String wrongCaller =
          serviceName(
              owner == Owner.ENTITY_MANAGEMENT
                  ? Owner.AUTOMATION_SCRIPTING
                  : Owner.ENTITY_MANAGEMENT);

      assertThatThrownBy(
              () -> withPeer(peer(NAMESPACE, wrongCaller), () -> service.read(fixture.request())))
          .isInstanceOf(SecurityException.class);

      SessionContext.setContext("abababab-abab-4bab-8bab-abababababab", List.of(), Map.of());
      try {
        assertThatThrownBy(
                () -> withPeer(peer(NAMESPACE, caller), () -> service.read(fixture.request())))
            .isInstanceOf(SecurityException.class);
      } finally {
        SessionContext.clear();
      }

      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(
                () -> withPeer(peer(NAMESPACE, caller), () -> service.read(fixture.request())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ambient SQL");
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
      verifyNoInteractions(account, inventoryRead);
    }
  }

  private static Fixture fixture(Owner owner) {
    UUID tenant = id("11111111-1111-4111-8111-111111111111");
    UUID version = id("22222222-2222-4222-8222-222222222222");
    UUID actor = id("99999999-9999-4999-8999-999999999999");
    var selected =
        DraftCommitBinding.create(
            new TargetProof(tenant, version, 23L, "tenant", 42L, "tenant", "NEW_GAME_ROW"),
            id("33333333-3333-4333-8333-333333333333"),
            id("44444444-4444-4444-8444-444444444444"),
            "base-commit-0",
            List.of(
                new RevisionPayload(
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
    var scope =
        new SelectedOwnerIntakeSourceReadScope(
            owner,
            NAMESPACE,
            id("55555555-5555-4555-8555-555555555555"),
            id("66666666-6666-4666-8666-666666666666"),
            id("77777777-7777-4777-8777-777777777777"),
            actor,
            selected);
    var content = sourceContent(scope);
    var binding =
        new SelectedOwnerIntakeAuthorizationBinding(
            content,
            List.of(
                source(SourceKind.TENANT, tenant.toString(), "tenant"),
                source(SourceKind.ACCOUNT, actor.toString(), "account"),
                source(SourceKind.MEMBERSHIP, actor + "/" + tenant, "membership")));

    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            new PublishIntent(
                tenant,
                version,
                "publication-request",
                "9",
                "source-intake test",
                selected.requestId(),
                selected.commitId(),
                selected.digest()),
            selected.target(),
            selected,
            new VisibilityFence(
                selected.target(),
                selected.requestId(),
                selected.commitId(),
                selected.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var publicationBinding =
        new AccountPublicationAuthorizationBinding(
            id("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            id("ffffffff-ffff-4fff-8fff-ffffffffffff"),
            new AccountPublicationAuthorizationBinding.PreallocationInput(actor, selection),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT, actor.toString(), "1", "1", null, null, new byte[] {1})));
    var freezeRequest =
        WorldSelectedDraftPublicationFreezeEvidence.Request.create(
            NAMESPACE,
            tenant,
            version,
            selection.intent().publishRequestId(),
            9,
            selection.digest().substring("sha256:".length()),
            publicationBinding);
    var acknowledgement =
        new Acknowledgement(
            freezeRequest,
            id("12121212-1212-4212-8212-121212121212"),
            9,
            id("13131313-1313-4313-8313-131313131313"),
            OwnerFreezePhase.FROZEN,
            selected.commitId().toString(),
            "a".repeat(64),
            3);
    var freezeEvidence =
        WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
            freezeRequest,
            WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(acknowledgement));
    return new Fixture(
        binding,
        freezeEvidence,
        SelectedOwnerWorldInventoryReadEvidence.create(NAMESPACE, binding, freezeEvidence));
  }

  private static SelectedOwnerIntakeSourceContent sourceContent(
      SelectedOwnerIntakeSourceReadScope scope) {
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SelectedOwnerIntakeSourceContent.DOMAIN);
    DraftAuthorizationFenceBinding.frame(out, scope.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(out, scope.digest());
    for (String family :
        List.of(
            "COMMAND", "REALM_POLICY", "ASSET", "GAMEPLAY_RULE", "BRANDING", "TEMPLATE_CONFIG")) {
      byte[] snapshot = sourceSnapshot(family, scope.selected());
      DraftAuthorizationFenceBinding.frame(out, family);
      DraftAuthorizationFenceBinding.frame(out, snapshot);
      DraftAuthorizationFenceBinding.frame(out, DraftAuthorizationFenceBinding.digest(snapshot));
    }
    byte[] bytes = out.toByteArray();
    return SelectedOwnerIntakeSourceContent.fromStored(
        bytes, scope, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static byte[] sourceSnapshot(String family, DraftCommitBinding selected) {
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
    if ("TEMPLATE_CONFIG".equals(family)) {
      return new TemplateConfigSourceSnapshot(
              selected, "0", null, id("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), List.of())
          .canonicalBytes();
    }
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

  private static SourceEvidence source(SourceKind kind, String scope, String marker) {
    return new SourceEvidence(
        kind, scope, null, "1", null, null, marker.getBytes(StandardCharsets.UTF_8));
  }

  private static String serviceName(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "entity-management-service"
        : "automation-scripting-service";
  }

  private static UUID id(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence,
      SelectedOwnerWorldInventoryReadEvidence.Request request) {}

  private static void assertDenied(RecordingObserver observer, Status.Code code) {
    assertThat(Status.fromThrowable(observer.error).getCode()).isEqualTo(code);
    assertThat(observer.response).isNull();
    assertThat(observer.completed).isFalse();
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, java.util.function.Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    withPeer(
        peer,
        () -> {
          action.run();
          return null;
        });
  }

  private static void withoutPeer(Runnable action) {
    Context previous = Context.ROOT.attach();
    try {
      action.run();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadSelectedOwnerWorldInventoryResponse> {
    ReadSelectedOwnerWorldInventoryResponse response;
    Throwable error;
    boolean completed;

    @Override
    public void onNext(ReadSelectedOwnerWorldInventoryResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Test observer intentionally retains the reported failure.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
