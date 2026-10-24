package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.CanonicalPlayerCreatedRosterSourceIdentity;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalSelectedPlayerCreatedCharacterResponse;
import net.firedevops.firemud.gamesession.charactercreate.GameSessionCharacterCreatePublicSourceService.ResolvedPlayerCreatedRosterSource;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedCharacter;
import net.firedevops.firemud.gamesession.client.CanonicalPlayerCreatedRosterClient.VerifiedPlayerCreatedRoster;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayRosterReferenceRepository.SelectionOutcome;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRosterReferenceOwner;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.testing.ControlledPlayerCreatedRosterValues;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled source, transport and owner doubles: no genuine mTLS or upstream custody claim. */
class CanonicalPlayerCreatedRosterClientTest {
  @TempDir Path directory;
  private final CanonicalGameplayRosterReferenceOwner owner =
      mock(CanonicalGameplayRosterReferenceOwner.class);
  private final ResolvedPlayerCreatedRosterSource source =
      mock(ResolvedPlayerCreatedRosterSource.class);
  private final GrpcChannelFactory channels = mock(GrpcChannelFactory.class);
  private final List<CanonicalPlayerCreatedRosterClient> clients = new ArrayList<>();
  private final CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub stub =
      mock(CanonicalGameplayRosterServiceGrpc.CanonicalGameplayRosterServiceBlockingStub.class);
  private Fixture fixture;
  private CommonGrpcClientProperties tls;
  private AtomicReference<GetCanonicalGameplayRosterReferenceResponse> stored;

  @BeforeEach
  void controlledComposition() throws Exception {
    fixture = fixture();
    tls = new CommonGrpcClientProperties();
    // Readable controlled files exercise configuration only; the channel factory is a double.
    tls.setCertChain(
        Files.writeString(directory.resolve("client.crt"), "controlled-cert").toString());
    tls.setPrivateKey(
        Files.writeString(directory.resolve("client.key"), "controlled-key").toString());
    tls.setCaCert(Files.writeString(directory.resolve("ca.crt"), "controlled-ca").toString());
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    when(source.target()).thenReturn(fixture.target());
    when(source.sourceIdentity()).thenReturn(fixture.source());
    var route = mock(CanonicalPlayableTarget.class);
    when(route.targetNamespace()).thenReturn(fixture.namespace());
    when(source.route()).thenReturn(route);
    when(stub.withDeadlineAfter(20L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.withMaxInboundMessageSize(anyInt())).thenReturn(stub);
    when(stub.listPlayerCreatedRoster(any()))
        .thenAnswer(
            call -> {
              outside();
              return fixture.roster();
            });
    when(stub.readSelectedPlayerCreatedCharacter(any()))
        .thenAnswer(
            call -> {
              outside();
              return fixture.selected();
            });
    stored =
        new AtomicReference<>(
            CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                fixture.original(), 1, UUID.randomUUID(), null));
    when(owner.begin(fixture.original()))
        .thenAnswer(
            call -> {
              outside();
              return stored.get();
            });
    when(owner.recordPlayerCreated(fixture.original(), fixture.roster()))
        .thenAnswer(
            call -> {
              outside();
              stored.set(
                  CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                      fixture.original(),
                      1,
                      UUID.fromString(stored.get().getTargetFenceUuid()),
                      fixture.roster()));
              return stored.get();
            });
    when(owner.selectOriginal(
            fixture.original(), UUID.fromString(fixture.selected().getSelectedCharacterUuid())))
        .thenAnswer(
            call -> {
              outside();
              stored.set(
                  stored.get().toBuilder()
                      .setSelectionInput(
                          CanonicalGameplayRosterSelectionInput.newBuilder().setSchemaVersion(1))
                      .setSelectedCharacterUuid(fixture.selected().getSelectedCharacterUuid())
                      .build());
              return stored.get();
            });
    doAnswer(
            call -> {
              outside();
              stored.set(
                  stored.get().toBuilder()
                      .setCanonicalSelectedPlayerCreatedCharacterReceipt(
                          fixture.selected().toByteString())
                      .build());
              return null;
            })
        .when(owner)
        .recordSelectedPlayerCreatedCharacter(fixture.original(), fixture.selected());
  }

  @AfterEach
  void closeControlledClients() throws Exception {
    TransactionSynchronizationManager.clear();
    for (var client : clients) client.close();
  }

  @Test
  void listsAndSelectsThroughDistinctMethodsAndPreservesCompleteOriginalTerminal()
      throws Exception {
    var client = client(true);
    var roster = client.listPlayerCreatedRoster(source, fixture.context());
    assertThat(roster.response()).isEqualTo(fixture.roster());
    assertThat(roster.resolvedSource()).isSameAs(source);
    assertThat(roster.originalSelection()).isEqualTo(fixture.original());
    assertThat(roster.target()).isEqualTo(fixture.target());
    assertThat(roster.sourceIdentity()).isEqualTo(fixture.source());
    assertThat(roster.actors())
        .containsExactlyElementsOf(fixture.roster().getRoster().getActorsList());
    assertThat(roster.snapshotDigest()).isEqualTo(fixture.roster().getRoster().getSnapshotDigest());
    var selected = client.readSelectedPlayerCreatedCharacter(roster, selectedContext());
    assertThat(selected.roster()).isSameAs(roster);
    assertThat(selected.response()).isEqualTo(fixture.selected());
    assertThat(selected.terminal().toByteString())
        .isEqualTo(fixture.selected().getTerminal().toByteString());
    assertThat(selected.creationOperationUuid().toString())
        .isEqualTo(fixture.selected().getCreationOperationUuid());
    assertThat(selected.mutationDigest()).isEqualTo(fixture.selected().getMutationDigest());
    var calls = inOrder(owner, stub);
    calls.verify(owner).begin(fixture.original());
    calls.verify(owner).markRosterReadStarted(fixture.original());
    calls.verify(stub).listPlayerCreatedRoster(any());
    calls.verify(owner).recordPlayerCreated(fixture.original(), fixture.roster());
    calls
        .verify(owner)
        .selectOriginal(
            fixture.original(), UUID.fromString(fixture.selected().getSelectedCharacterUuid()));
    calls.verify(stub).readSelectedPlayerCreatedCharacter(any());
    calls
        .verify(owner)
        .recordSelectedPlayerCreatedCharacter(fixture.original(), fixture.selected());
    verify(stub).withMaxInboundMessageSize(66 * 1024);
    verify(stub).withMaxInboundMessageSize(193 * 1024 * 1024);
    var list = org.mockito.ArgumentCaptor.forClass(CanonicalPlayerCreatedRosterRequest.class);
    verify(stub).listPlayerCreatedRoster(list.capture());
    assertThat(list.getValue().getExpectedSource()).isEqualTo(fixture.source());
    assertThat(list.getValue().getPlayerExecutionContext()).isEqualTo(fixture.context());
    var selection =
        org.mockito.ArgumentCaptor.forClass(CanonicalSelectedPlayerCreatedCharacterRequest.class);
    verify(stub).readSelectedPlayerCreatedCharacter(selection.capture());
    assertThat(selection.getValue().getExpectedSnapshot().getSnapshotUuid())
        .isEqualTo(roster.snapshotUuid().toString());
    assertThat(selection.getValue().getPlayerExecutionContext()).isEqualTo(selectedContext());
  }

  @Test
  void exactStoredMenuAndSelectedReceiptReplayWithoutAnInitializedTransport() throws Exception {
    var first = client(true);
    var roster = first.listPlayerCreatedRoster(source, fixture.context());
    first.readSelectedPlayerCreatedCharacter(roster, selectedContext());
    var replay = client(false);
    var replayedRoster = replay.listPlayerCreatedRoster(source, fixture.context());
    var selected = replay.readSelectedPlayerCreatedCharacter(replayedRoster, selectedContext());
    assertThat(replayedRoster.response()).isEqualTo(fixture.roster());
    assertThat(selected.response().toByteString()).isEqualTo(fixture.selected().toByteString());
    verify(stub).listPlayerCreatedRoster(any());
    verify(stub).readSelectedPlayerCreatedCharacter(any());
    verify(owner).recordPlayerCreated(fixture.original(), fixture.roster());
    verify(owner).recordSelectedPlayerCreatedCharacter(fixture.original(), fixture.selected());
  }

  @Test
  void reserveAndCompleteUseExactCreatedOriginalAndClosedInput() throws Exception {
    var client = client(false);
    UUID menu = UUID.randomUUID();
    client.reserveSelectionInput(source, fixture.context(), "2", menu, "1".repeat(64));
    verify(owner)
        .reserveSelectionInput(
            fixture.original(),
            CanonicalGameplayRosterSelectionInput.newBuilder()
                .setSchemaVersion(1)
                .setSelector("2")
                .setMenuSnapshotUuid(menu.toString())
                .setMenuSnapshotDigest("1".repeat(64))
                .build());
    client.completeSelection(source, fixture.context(), SelectionOutcome.DENIED);
    verify(owner).completeSelection(fixture.original(), SelectionOutcome.DENIED);
    assertThatThrownBy(
            () -> client.reserveSelectionInput(source, fixture.context(), "1", menu, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> client.reserveSelectionInput(source, fixture.context(), "1", null, "invalid"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                client.reserveSelectionInput(
                    source, fixture.context(), "x".repeat(129), null, null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(stub);
  }

  @Test
  void wrongPolicyNamespaceAndMalformedContextRefuseBeforeOwnerOrRpc() throws Exception {
    var client = client(false);
    when(source.target())
        .thenReturn(
            fixture.target().toBuilder()
                .setEntryPolicy(
                    CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
                .build());
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isInstanceOf(IllegalArgumentException.class);
    when(source.target()).thenReturn(fixture.target());
    var foreign = mock(CanonicalPlayableTarget.class);
    when(foreign.targetNamespace()).thenReturn("foreign");
    when(source.route()).thenReturn(foreign);
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isInstanceOf(IllegalArgumentException.class);
    when(source.route()).thenReturn(mock(CanonicalPlayableTarget.class));
    when(source.route().targetNamespace()).thenReturn(fixture.namespace());
    var invalid =
        List.of(
            fixture.context().toBuilder().setRealmId(UUID.randomUUID().toString()).build(),
            fixture.context().toBuilder().setSessionId("not-uuid").build(),
            fixture.context().toBuilder().setPlayableStateScope("1").build(),
            selectedContext());
    for (var context : invalid) {
      assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, context))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(owner, stub);
  }

  @Test
  void changedOwnerOriginalAndChangedEntitySourceOrAccountFailClosed() throws Exception {
    var client = client(true);
    var other =
        fixture.original().toBuilder().setOperationUuid(UUID.randomUUID().toString()).build();
    when(owner.begin(fixture.original()))
        .thenReturn(
            CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                other, 1, UUID.randomUUID(), null));
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isInstanceOf(IllegalArgumentException.class);
    when(owner.begin(fixture.original())).thenReturn(stored.get());
    var wrongSource =
        fixture.roster().toBuilder()
            .setSourceIdentity(
                fixture.source().toBuilder()
                    .setDescriptorRevisionUuid(UUID.randomUUID().toString()))
            .build();
    var wrongAccount =
        fixture.roster().toBuilder()
            .setRoster(
                fixture.roster().getRoster().toBuilder()
                    .setCanonicalAccountUuid(UUID.randomUUID().toString()))
            .build();
    for (var response : List.of(wrongSource, wrongAccount)) {
      when(stub.listPlayerCreatedRoster(any())).thenReturn(response);
      assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(owner, never()).recordPlayerCreated(any(), any());
  }

  @Test
  void selectedRequiresOriginalContextActorSourceAndExactStoredSnapshot() throws Exception {
    var client = client(true);
    var roster = client.listPlayerCreatedRoster(source, fixture.context());
    var invalid =
        List.of(
            selectedContext().toBuilder().setRequestId(UUID.randomUUID().toString()).build(),
            selectedContext().toBuilder().setSessionId(UUID.randomUUID().toString()).build(),
            selectedContext().toBuilder().setAccountId(UUID.randomUUID().toString()).build(),
            selectedContext().toBuilder().setCharacterId(UUID.randomUUID().toString()).build());
    for (var context : invalid) {
      assertThatThrownBy(() -> client.readSelectedPlayerCreatedCharacter(roster, context))
          .isInstanceOf(IllegalArgumentException.class);
    }
    when(source.sourceIdentity())
        .thenReturn(
            fixture.source().toBuilder().setDescriptorUuid(UUID.randomUUID().toString()).build());
    assertThatThrownBy(() -> client.readSelectedPlayerCreatedCharacter(roster, selectedContext()))
        .isInstanceOf(IllegalArgumentException.class);
    when(source.sourceIdentity()).thenReturn(fixture.source());
    var changedRoster =
        fixture.roster().toBuilder()
            .setRoster(
                fixture.roster().getRoster().toBuilder()
                    .setSnapshotUuid(UUID.randomUUID().toString()))
            .build();
    var changedOriginal =
        CanonicalGameplayRosterReferenceEvidence.playerCreatedResponse(
                fixture.original(),
                1,
                UUID.fromString(stored.get().getTargetFenceUuid()),
                changedRoster)
            .toBuilder()
            .setSelectionInput(
                CanonicalGameplayRosterSelectionInput.newBuilder().setSchemaVersion(1))
            .setSelectedCharacterUuid(fixture.selected().getSelectedCharacterUuid())
            .build();
    when(owner.selectOriginal(any(), any())).thenReturn(changedOriginal);
    assertThatThrownBy(() -> client.readSelectedPlayerCreatedCharacter(roster, selectedContext()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(stub, never()).readSelectedPlayerCreatedCharacter(any());
  }

  @Test
  void selectedResponseBindingOrTerminalSubstitutionCannotBeRecorded() throws Exception {
    var client = client(true);
    var roster = client.listPlayerCreatedRoster(source, fixture.context());
    var responses =
        List.of(
            fixture.selected().toBuilder()
                .setCreationOperationUuid(UUID.randomUUID().toString())
                .build(),
            fixture.selected().toBuilder()
                .setSnapshot(
                    fixture.selected().getSnapshot().toBuilder()
                        .setSnapshotUuid(UUID.randomUUID().toString()))
                .build(),
            fixture.selected().toBuilder()
                .setSourceIdentity(
                    fixture.source().toBuilder()
                        .setAuthoredBindingUuid(UUID.randomUUID().toString()))
                .build());
    for (var response : responses) {
      when(stub.readSelectedPlayerCreatedCharacter(any())).thenReturn(response);
      assertThatThrownBy(() -> client.readSelectedPlayerCreatedCharacter(roster, selectedContext()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(owner, never()).recordSelectedPlayerCreatedCharacter(any(), any());
  }

  @Test
  void noTransportOrOwnerAndEstablishedUnavailableFailuresRemainClosed() throws Exception {
    var client = client(false);
    assertUnavailable(() -> client.listPlayerCreatedRoster(source, fixture.context()));
    verify(owner, never()).markRosterReadStarted(any());
    var missingOwner =
        new CanonicalPlayerCreatedRosterClient(
            new ServiceEndpointsProperties(), tls, channels, fixture.namespace(), null);
    clients.add(missingOwner);
    assertUnavailable(() -> missingOwner.listPlayerCreatedRoster(source, fixture.context()));
    assertUnavailable(
        () -> missingOwner.reserveSelectionInput(source, fixture.context(), "", null, null));
    assertUnavailable(
        () -> missingOwner.completeSelection(source, fixture.context(), SelectionOutcome.DENIED));
    var storage =
        new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
            "controlled unavailable");
    doThrow(storage).when(owner).begin(any());
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isSameAs(storage);
    var remote = Status.UNAVAILABLE.withDescription("Entity unavailable").asRuntimeException();
    doThrow(remote).when(owner).begin(any());
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isSameAs(remote);
  }

  @Test
  void actualGrpcFailuresPropagateWithoutRecordingReceipts() throws Exception {
    var client = client(true);
    var failure =
        Status.FAILED_PRECONDITION.withDescription("exact source changed").asRuntimeException();
    doThrow(failure).when(stub).listPlayerCreatedRoster(any());
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isSameAs(failure);
    verify(owner, never()).recordPlayerCreated(any(), any());
    doReturn(fixture.roster()).when(stub).listPlayerCreatedRoster(any());
    var roster = client.listPlayerCreatedRoster(source, fixture.context());
    doThrow(failure).when(stub).readSelectedPlayerCreatedCharacter(any());
    assertThatThrownBy(() -> client.readSelectedPlayerCreatedCharacter(roster, selectedContext()))
        .isSameAs(failure);
    verify(owner, never()).recordSelectedPlayerCreatedCharacter(any(), any());
  }

  @Test
  void everyOperationRejectsActualAndSynchronizationOnlyAmbientSql() throws Exception {
    var client = client(true);
    var roster = client.listPlayerCreatedRoster(source, fixture.context());
    var operations =
        List.<Runnable>of(
            () -> client.reserveSelectionInput(source, fixture.context(), "", null, null),
            () -> client.completeSelection(source, fixture.context(), SelectionOutcome.DENIED),
            () -> client.listPlayerCreatedRoster(source, fixture.context()),
            () -> client.readSelectedPlayerCreatedCharacter(roster, selectedContext()));
    for (boolean synchronizationOnly : List.of(false, true)) {
      if (synchronizationOnly) TransactionSynchronizationManager.initSynchronization();
      else TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        for (var operation : operations) {
          assertThatThrownBy(operation::run).isInstanceOf(IllegalStateException.class);
        }
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    verify(owner).begin(fixture.original());
    verify(owner, never()).reserveSelectionInput(any(), any());
    verify(owner, never()).completeSelection(any(), any());
    verify(owner, never()).selectOriginal(any(), any());
  }

  @Test
  void actualBuiltStubPinsTheEntityPeerAndUsesCompressionDeadlineAndCreatedRosterBudget()
      throws Exception {
    var channel = mock(ManagedChannel.class);
    var ssl = mock(javax.net.ssl.SSLSession.class);
    var certificate = mock(java.security.cert.X509Certificate.class);
    when(ssl.getPeerCertificates()).thenReturn(new java.security.cert.Certificate[] {certificate});
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(
            List.of(List.of(6, "spiffe://firemud/ns/foreign/sa/entity-management-service")));
    @SuppressWarnings("unchecked")
    io.grpc.ClientCall<CanonicalPlayerCreatedRosterRequest, CanonicalPlayerCreatedRosterResponse>
        call = mock(io.grpc.ClientCall.class);
    when(call.getAttributes())
        .thenReturn(
            io.grpc.Attributes.newBuilder()
                .set(io.grpc.Grpc.TRANSPORT_ATTR_SSL_SESSION, ssl)
                .build());
    doAnswer(
            invocation -> {
              io.grpc.ClientCall.Listener<CanonicalPlayerCreatedRosterResponse> listener =
                  invocation.getArgument(0);
              listener.onMessage(fixture.roster());
              listener.onClose(Status.OK, new io.grpc.Metadata());
              return null;
            })
        .when(call)
        .start(any(), any());
    when(channel.<CanonicalPlayerCreatedRosterRequest, CanonicalPlayerCreatedRosterResponse>newCall(
            any(), any()))
        .thenReturn(call);
    when(channels.buildChannel(anyString(), anyInt(), any(), anyBoolean())).thenReturn(channel);
    var client = client(false);
    client.init();
    assertThatThrownBy(() -> client.listPlayerCreatedRoster(source, fixture.context()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    var options = org.mockito.ArgumentCaptor.forClass(io.grpc.CallOptions.class);
    verify(channel).newCall(any(), options.capture());
    assertThat(options.getValue().getCompressor()).isEqualTo("gzip");
    assertThat(options.getValue().getMaxInboundMessageSize()).isEqualTo(66 * 1024);
    assertThat(options.getValue().getDeadline()).isNotNull();
    assertThat(options.getValue().getDeadline().timeRemaining(TimeUnit.MILLISECONDS))
        .isBetween(19_000L, 20_000L);
    assertThat(options.getValue().getCredentials())
        .isInstanceOf(
            net.firedevops.firemud.common.grpc.GrpcServerPeerIdentityCallCredentials.class);
    verify(owner, never()).recordPlayerCreated(any(), any());
    // This is a controlled TLS-session transport seam, not a completed physical TLS handshake.
  }

  @Test
  void wrappersHaveOnlyPrivateConstructorsAndTlsConfigurationIsRequired() {
    for (var type :
        List.of(VerifiedPlayerCreatedRoster.class, VerifiedPlayerCreatedCharacter.class)) {
      assertThat(type.getDeclaredConstructors())
          .allMatch(c -> Modifier.isPrivate(c.getModifiers()));
    }
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new CanonicalPlayerCreatedRosterClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    channels,
                    fixture.namespace(),
                    owner))
        .isInstanceOf(IllegalArgumentException.class);
    var classpath = tls.copy();
    classpath.setCertChain("classpath:client.crt");
    assertThatThrownBy(
            () ->
                new CanonicalPlayerCreatedRosterClient(
                    new ServiceEndpointsProperties(),
                    classpath,
                    channels,
                    fixture.namespace(),
                    owner))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CanonicalPlayerCreatedRosterClient client(boolean initialized) throws Exception {
    var client =
        new CanonicalPlayerCreatedRosterClient(
            new ServiceEndpointsProperties(), tls, channels, fixture.namespace(), owner);
    clients.add(client);
    if (initialized) {
      client.init();
      // Controlled transport replacement after real manual initialization. No peer custody claim.
      ReflectionTestUtils.setField(client, "stub", stub);
    }
    return client;
  }

  private PlayerExecutionContext selectedContext() {
    return fixture.context().toBuilder()
        .setCharacterId(fixture.selected().getSelectedCharacterUuid())
        .build();
  }

  private static void assertUnavailable(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
  }

  private static void outside() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private record Fixture(
      String namespace,
      CanonicalGameplayRosterTarget target,
      CanonicalPlayerCreatedRosterSourceIdentity source,
      PlayerExecutionContext context,
      GetCanonicalGameplayRosterReferenceRequest original,
      CanonicalPlayerCreatedRosterResponse roster,
      CanonicalSelectedPlayerCreatedCharacterResponse selected) {}

  private static Fixture fixture() throws Exception {
    var values = ControlledPlayerCreatedRosterValues.forClient();
    return new Fixture(
        values.namespace(),
        values.target(),
        values.source(),
        values.context(),
        values.original(),
        values.roster(),
        values.selected().values().iterator().next());
  }
}
