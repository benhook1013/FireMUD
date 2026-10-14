package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.stub.AbstractStub;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalClient;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalReadGrpcCodec;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.testing.AuthoringFixtures;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GameDesignPublicationTerminalReadServiceGrpc;
import net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalResponse;
import net.firedevops.firemud.test.IsolatedWorldPublicationInventoryFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

public class WorldGameDesignPublicationTerminalVerifierTest {
  private static final String NAMESPACE = "test";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesSameNamespaceGameDesignCallerBeforeDecodingOrCallingClient() {
    var client = mock(GameDesignPublicationTerminalClient.class);
    var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, client);

    assertThatThrownBy(() -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2}))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("same-namespace Game Design");
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-design-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(SecurityException.class);

    verifyNoInteractions(client);
  }

  @Test
  void requiresNoAmbientTransactionAndRejectsMalformedOperationBeforeRemoteRead() throws Exception {
    var client = mock(GameDesignPublicationTerminalClient.class);
    var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, client);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside owner transactions");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoInteractions(client);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-design-service"),
                    () -> verifier.authenticateAndVerifyAndHold(new byte[] {1}, new byte[] {2})))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(client);
  }

  @Test
  // The real client and codec are exercised over a stubbed channel; this is not physical mTLS
  // proof.
  void authenticatedExactCanonicalReadReturnsClosableAuthorityThroughTheRealClientCodec(
      @TempDir Path directory) throws Exception {
    var operation = operation();
    var terminal = terminal(operation, Outcome.NO_PUBLICATION);
    try (var upstream = new StubbedGameDesignClient(directory)) {
      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                readRequest, terminalResult(readRequest, terminal));
          });
      var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, upstream.client);
      var verified =
          withPeer(
              peer(NAMESPACE, "game-design-service"),
              () ->
                  verifier.authenticateAndVerifyAndHold(
                      operation.canonicalBytes(), terminal.canonicalBytes()));

      assertThat(verified.request().operationBytes()).containsExactly(operation.canonicalBytes());
      assertThat(verified.request().terminalBytes()).containsExactly(terminal.canonicalBytes());
      verified.authority().requireHeld();
      verified.authority().close();
      assertThatThrownBy(verified.authority()::requireHeld)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no longer valid");
      assertThat(upstream.expectedServerIdentity())
          .isEqualTo("spiffe://firemud/ns/test/sa/game-design-service");
      verify(upstream.stub).readGameDesignPublicationTerminal(any());
    }
  }

  @Test
  void rejectsChangedReadEchoAndSubstitutedCanonicalTerminal(@TempDir Path directory)
      throws Exception {
    var operation = operation();
    var expectedTerminal = terminal(operation, Outcome.NO_PUBLICATION);
    var substitutedTerminal = terminal(operation, Outcome.PUBLISHED);
    try (var upstream = new StubbedGameDesignClient(directory)) {
      var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, upstream.client);
      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            var changed =
                new GameDesignPublicationTerminalReadEvidence.ReadRequest(
                    1,
                    readRequest.targetNamespace(),
                    UUID.randomUUID(),
                    readRequest.operationBytes());
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                changed, terminalResult(changed, expectedTerminal));
          });
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () ->
                          verifier.authenticateAndVerifyAndHold(
                              operation.canonicalBytes(), expectedTerminal.canonicalBytes())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("invalid publication terminal read evidence");

      upstream.respondWith(
          wireRequest -> {
            var readRequest = GameDesignPublicationTerminalReadGrpcCodec.fromRequest(wireRequest);
            return GameDesignPublicationTerminalReadGrpcCodec.toResponse(
                readRequest, terminalResult(readRequest, substitutedTerminal));
          });
      assertThatThrownBy(
              () ->
                  withPeer(
                      peer(NAMESPACE, "game-design-service"),
                      () ->
                          verifier.authenticateAndVerifyAndHold(
                              operation.canonicalBytes(), expectedTerminal.canonicalBytes())))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("differs from the submitted exact operation or result");
    }
  }

  @Test
  void unknownOwnerOutcomeNeverCreatesPositiveTerminalAuthority() throws Exception {
    var operation = operation();
    var expected = terminal(operation, Outcome.NO_PUBLICATION);
    var client = mock(GameDesignPublicationTerminalClient.class);
    when(client.read(any()))
        .thenAnswer(
            call ->
                new GameDesignPublicationTerminalReadEvidence.ReadResult(
                    call.getArgument(0),
                    GameDesignPublicationTerminalReadEvidence.Status.UNKNOWN,
                    Optional.empty()));
    var verifier = new WorldGameDesignPublicationTerminalVerifier(NAMESPACE, client);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-design-service"),
                    () ->
                        verifier.authenticateAndVerifyAndHold(
                            operation.canonicalBytes(), expected.canonicalBytes())))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("unknown");
  }

  private static GameDesignPublicationTerminalReadEvidence.ReadResult terminalResult(
      GameDesignPublicationTerminalReadEvidence.ReadRequest request,
      GameDesignPublicationTerminalEvidence terminal) {
    return new GameDesignPublicationTerminalReadEvidence.ReadResult(
        request,
        GameDesignPublicationTerminalReadEvidence.Status.valueOf(terminal.outcome().name()),
        Optional.of(terminal));
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static <T> T withPeer(GrpcPeerIdentity identity, Callable<T> task) throws Exception {
    return Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, identity).call(task);
  }

  public static GameDesignPublicationOperationBinding operation() throws Exception {
    // Canonical structural inputs only; this fixture does not stipulate real owner commits or
    // source holds.
    var upstream = AuthoringFixtures.freshGraphRequest();
    var applied = AuthoringFixtures.committedFreshGraphReadback(upstream);
    var original = upstream.accountBinding();
    var draft =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), StandardCharsets.UTF_8),
            original.inputDigest());
    var target = draft.target();
    var intent =
        new AuthoredDraftPublishSelectionBinding.PublishIntent(
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            "publication-request",
            "5",
            "fixture",
            draft.requestId(),
            draft.commitId(),
            draft.digest());
    var selection =
        AuthoredDraftPublishSelectionBinding.capture(
            intent,
            target,
            draft,
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                target,
                draft.requestId(),
                draft.commitId(),
                draft.digest(),
                "[]",
                OffsetDateTime.parse("2026-10-01T00:00:00Z")));
    var publicationSources =
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                original.actorAccountId().toString(),
                "1",
                "1",
                null,
                null,
                "stipulated-publication-account-source".getBytes(StandardCharsets.UTF_8)));
    var account =
        new AccountPublicationAuthorizationBinding(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountPublicationAuthorizationBinding.PreallocationInput(
                original.actorAccountId(), selection),
            publicationSources);
    var tuples =
        draft.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).stream()
            .map(
                unit ->
                    new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                        unit.owner().name(),
                        unit.aggregateType(),
                        unit.aggregateId(),
                        unit.scopeType(),
                        unit.scopeId(),
                        unit.expectedEpoch()))
            .toList();
    String workflow =
        PublicationDigestRequestBinding.full(
                target.canonicalTenantId().toString(),
                Long.toString(target.gameDesignVersionRowId()),
                intent.publishRequestId())
            .derivedWorkflowIdentity();
    var result = JsonMapper.builder().build().readTree(applied.result());
    var appliedOperation =
        new DraftAuthorizationFenceBinding.FrameReader(
            Base64.getDecoder().decode(result.get("operationBytesBase64").textValue()));
    appliedOperation.expect("world-draft-terminal-operation/v1");
    for (int frame = 1; frame < 13; frame++) appliedOperation.bytes();
    UUID intakeRequestId = UUID.fromString(appliedOperation.text());
    var worldRequest =
        new WorldPublishedStartLocationEvidence.Request(
            NAMESPACE,
            target.canonicalTenantId(),
            target.canonicalVersionId(),
            intakeRequestId,
            UUID.randomUUID(),
            intent.publishRequestId(),
            selection.digest().substring(7),
            5L,
            workflow,
            draft.commitId().toString(),
            "b".repeat(64),
            3,
            tuples);
    var world =
        new WorldPublishedStartLocationEvidence(
            worldRequest,
            Base64.getDecoder().decode(result.get("startLocationReceiptBase64").textValue()),
            applied.fullBinding(),
            applied.result());
    return new GameDesignPublicationOperationBinding(
        account, world, IsolatedWorldPublicationInventoryFixtures.stipulated(account, world));
  }

  public static GameDesignPublicationTerminalEvidence terminal(
      GameDesignPublicationOperationBinding operation, Outcome outcome) {
    if (outcome == Outcome.NO_PUBLICATION) {
      return new GameDesignPublicationTerminalEvidence(
          operation.canonicalBytes(), outcome, null, null);
    }
    var selection = operation.account().input().selection();
    var world = operation.world().request();
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new GameDesignPublicationTerminalEvidence.Participant(
                        owner,
                        Long.toString(selection.target().gameDesignVersionRowId()),
                        null,
                        world.appliedCommitId(),
                        "WORLD_MANAGEMENT".equals(owner) ? world.contentDigest() : "c".repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "d".repeat(64) : null,
                        null,
                        null))
            .toList();
    var release =
        new GameDesignPublicationTerminalEvidence.ReleaseContent(
            selection.intent().canonicalTenantId(),
            selection.intent().canonicalVersionId(),
            "fixture-bundle",
            1,
            "v2",
            world.publishWorkflowId(),
            "sha256:" + "a".repeat(64),
            1,
            List.of(),
            List.of(),
            participants,
            List.of(),
            "generation-1",
            operation.world());
    return new GameDesignPublicationTerminalEvidence(
        operation.canonicalBytes(), outcome, release, 6L);
  }

  private static final class StubbedGameDesignClient implements AutoCloseable {
    private final GameDesignPublicationTerminalClient client;
    private final String serverIdentity;
    private final GameDesignPublicationTerminalReadServiceGrpc
            .GameDesignPublicationTerminalReadServiceBlockingStub
        stub;

    private StubbedGameDesignClient(Path directory) throws Exception {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setGameDesignService("design.internal:6565");
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          Files.writeString(directory.resolve("client.crt"), "certificate").toString());
      tls.setPrivateKey(
          Files.writeString(directory.resolve("client.key"), "private key").toString());
      tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "CA certificate").toString());
      var channelFactory = mock(GrpcChannelFactory.class);
      var channel = mock(ManagedChannel.class);
      when(channelFactory.buildChannel(
              org.mockito.ArgumentMatchers.eq("design.internal:6565"),
              org.mockito.ArgumentMatchers.eq(6565),
              any(CommonGrpcClientProperties.class),
              org.mockito.ArgumentMatchers.eq(true)))
          .thenReturn(channel);
      client = new GameDesignPublicationTerminalClient(endpoints, tls, channelFactory, NAMESPACE);
      client.init();
      Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      AbstractStub<?> initialized = (AbstractStub<?>) field.get(client);
      var credentials = initialized.getCallOptions().getCredentials();
      if (!(credentials instanceof GrpcServerPeerIdentityCallCredentials)) {
        throw new AssertionError("Strict Game Design peer identity credentials are required");
      }
      Field peerField = credentials.getClass().getDeclaredField("expectedPeerUri");
      peerField.setAccessible(true);
      serverIdentity = (String) peerField.get(credentials);
      stub =
          mock(
              GameDesignPublicationTerminalReadServiceGrpc
                  .GameDesignPublicationTerminalReadServiceBlockingStub.class);
      when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
      field.set(client, stub);
    }

    private void respondWith(
        java.util.function.Function<
                net.firedevops.firemud.gamedesign.v1.ReadGameDesignPublicationTerminalRequest,
                ReadGameDesignPublicationTerminalResponse>
            response) {
      doAnswer(call -> response.apply(call.getArgument(0)))
          .when(stub)
          .readGameDesignPublicationTerminal(any());
    }

    private String expectedServerIdentity() throws Exception {
      return serverIdentity;
    }

    @Override
    public void close() throws Exception {
      client.close();
    }
  }
}
