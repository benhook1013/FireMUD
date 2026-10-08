package unit.net.firedevops.firemud.accountservice.authordraft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftTerminalReconciliationService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Ordering;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.Settlement;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Outcome;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.OwnerReadback;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic owner clients prove recovery composition, not peer authentication or SQL atomicity. */
class AccountDraftTerminalReconciliationServiceTest {
  private static final String NAMESPACE = "firemud-test";

  @Test
  void unknownFromBothOwnersStaysPendingAndRemoteReadsRunOutsideTransactions() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.originalAccountBinding())
                  .containsExactly(f.binding().canonicalBytes());
              return new GameDesignDraftTerminalReadEvidence(request, Optional.empty());
            });
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo(NAMESPACE);
              assertThat(request.originalAccountBinding())
                  .containsExactly(f.binding().canonicalBytes());
              return new WorldDraftTerminalReadEvidence(request, Optional.empty());
            });

    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.PENDING);
    assertThat(f.manager().commits).isEqualTo(2);
    assertThat(f.stored()).isEmpty();
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
  }

  @Test
  void storedWorldOutcomeNeverSkipsMissingGameDesignAndRemainsImmutable() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    OwnerReadback world =
        readback(f.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {7});
    f.stored().put(Owner.WORLD, world);
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.GAME_DESIGN,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {8})));
            });

    assertThat(f.service().reconcile(f.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    verify(f.gameDesignClient()).read(any());
    verifyNoInteractions(f.worldClient());
    assertThat(f.stored().get(Owner.WORLD).canonicalBytes())
        .containsExactly(world.canonicalBytes());
    assertThat(f.stored().get(Owner.GAME_DESIGN).owner()).isEqualTo(Owner.GAME_DESIGN);
  }

  @Test
  void storedGameDesignOutcomeReadsOnlyMissingWorldAndRevokeOrderNeedsBothAborts() {
    Fixture f = fixture(Ordering.REVOKE_ORDER);
    OwnerReadback gameDesign =
        readback(f.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {9});
    f.stored().put(Owner.GAME_DESIGN, gameDesign);
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new WorldDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.WORLD,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {10})));
            });

    assertThat(f.service().reconcile(f.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    verify(f.worldClient()).read(any());
    verifyNoInteractions(f.gameDesignClient());
    assertThat(f.stored().get(Owner.GAME_DESIGN).canonicalBytes())
        .containsExactly(gameDesign.canonicalBytes());
  }

  @Test
  void storedGameDesignCorruptionDeniesEvenWhenWorldResultAlreadyExists() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    f.stored()
        .put(
            Owner.WORLD,
            readback(f.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {24}));
    when(f.repository().readOwnerResult(any(), eq(Owner.GAME_DESIGN)))
        .thenThrow(new IllegalArgumentException("synthetic strict persisted-readback rejection"));

    assertThatThrownBy(() -> f.service().reconcile(f.binding().operationId()))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.gameDesignClient(), f.worldClient());
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
  }

  @Test
  void changedRequestWrongOwnerAndWrongBindingAreRejected() {
    for (int mismatch = 0; mismatch < 3; mismatch++) {
      Fixture f = fixture(Ordering.COMMIT_ORDER);
      int selectedMismatch = mismatch;
      when(f.gameDesignClient().read(any()))
          .thenAnswer(
              invocation -> {
                var expected =
                    (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
                var response = mock(GameDesignDraftTerminalReadEvidence.class);
                var returnedRequest =
                    selectedMismatch == 0
                        ? new GameDesignDraftTerminalReadEvidence.Request(
                            expected.schemaVersion(),
                            "other-namespace",
                            expected.readRequestId(),
                            expected.originalAccountBinding())
                        : expected;
                var wrongBinding = binding();
                var resultBinding = selectedMismatch == 2 ? wrongBinding : f.binding();
                OwnerReadback value =
                    readback(
                        resultBinding,
                        selectedMismatch == 1 ? Owner.WORLD : Owner.GAME_DESIGN,
                        Outcome.DEFINITIVELY_ABORTED,
                        new byte[] {11});
                when(response.request()).thenReturn(returnedRequest);
                when(response.ownerReadback()).thenReturn(Optional.of(value));
                return response;
              });

      assertThatThrownBy(() -> f.service().reconcile(f.binding().operationId()))
          .isInstanceOf(IllegalArgumentException.class);
      verify(f.repository(), never()).recordOwnerReadback(any(), any());
      verifyNoInteractions(f.worldClient());
    }
  }

  @Test
  void orderingDriftDuringOwnerReadsPreventsAnyReadbackWrite() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.repository().read(any()))
        .thenAnswer(
            new org.mockito.stubbing.Answer<>() {
              private int reads;

              @Override
              public DraftAuthorizationFenceRepository.FenceSnapshot answer(
                  org.mockito.invocation.InvocationOnMock invocation) {
                Ordering current = reads++ == 0 ? Ordering.COMMIT_ORDER : Ordering.REVOKE_ORDER;
                return fenceSnapshot(f.binding(), current);
              }
            });
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.GAME_DESIGN,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {12})));
            });
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new WorldDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.WORLD,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {13})));
            });

    assertThatThrownBy(() -> f.service().reconcile(f.binding().operationId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Original Draft ordering changed during recovery");
    assertThat(f.stored()).isEmpty();
    assertThat(f.manager().rollbacks).isEqualTo(1);
  }

  @Test
  void disappearedOrChangedOriginalDuringOwnerReadsIsDeniedAndRollsBack() {
    for (boolean absent : List.of(false, true)) {
      Fixture f = fixture(Ordering.COMMIT_ORDER);
      DraftAuthorizationFenceBinding original = f.binding();
      DraftAuthorizationFenceBinding changed =
          new DraftAuthorizationFenceBinding(
              original.operationId(),
              original.requestId(),
              original.commitId(),
              original.fenceId(),
              original.actorAccountId(),
              original.tenantId(),
              original.versionId(),
              original.baseCommitId(),
              "1",
              original.gameDesignBinding(),
              original.normalizedInput(),
              original.inputDigest(),
              original.sources());
      assertThat(changed.canonicalBytes()).isNotEqualTo(original.canonicalBytes());
      when(f.gameDesignClient().read(any()))
          .thenAnswer(
              invocation -> {
                assertOutsideTransaction(f);
                var request =
                    (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
                doAnswer(
                        originalRead -> {
                          assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                              .isTrue();
                          assertThat(
                                  TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                              .isFalse();
                          return absent ? Optional.empty() : Optional.of(changed);
                        })
                    .when(f.repository())
                    .readOriginalBinding(any());
                return new GameDesignDraftTerminalReadEvidence(request, Optional.empty());
              });
      when(f.worldClient().read(any()))
          .thenAnswer(
              invocation -> {
                assertOutsideTransaction(f);
                return new WorldDraftTerminalReadEvidence(
                    invocation.getArgument(0), Optional.empty());
              });

      assertThatThrownBy(() -> f.service().reconcile(original.operationId()))
          .isInstanceOf(IllegalStateException.class);
      verify(f.repository(), never()).recordOwnerReadback(any(), any());
      assertThat(f.manager().rollbacks).isEqualTo(1);
    }
  }

  @Test
  void lostResponseDoesNotInventAbortAndExactRetrySettlesFromBothImmutableResults() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.GAME_DESIGN,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {14})));
            });
    when(f.worldClient().read(any()))
        .thenThrow(new IllegalStateException("synthetic lost response"));
    assertThatThrownBy(() -> f.service().reconcile(f.binding().operationId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic lost response");
    assertThat(f.stored()).isEmpty();

    doAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new WorldDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.WORLD,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {15})));
            })
        .when(f.worldClient())
        .read(any());
    assertThat(f.service().reconcile(f.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    assertThat(f.stored()).containsKeys(Owner.GAME_DESIGN, Owner.WORLD);
    assertThat(f.manager().commits).isEqualTo(3);
  }

  @Test
  void unknownOwnerResponsesObserveConcurrentGenuineSettlementWithoutInventingResults() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    OwnerReadback gameDesignAbort =
        readback(f.binding(), Owner.GAME_DESIGN, Outcome.DEFINITIVELY_ABORTED, new byte[] {25});
    OwnerReadback worldAbort =
        readback(f.binding(), Owner.WORLD, Outcome.DEFINITIVELY_ABORTED, new byte[] {26});
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              f.stored().put(Owner.GAME_DESIGN, gameDesignAbort);
              f.stored().put(Owner.WORLD, worldAbort);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(request, Optional.empty());
            });
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new WorldDraftTerminalReadEvidence(request, Optional.empty());
            });

    assertThat(f.service().reconcile(f.binding().operationId()))
        .contains(Settlement.FAILED_NONPUBLICATION);
    verify(f.repository(), never()).recordOwnerReadback(any(), any());
    assertThat(f.stored().get(Owner.GAME_DESIGN).canonicalBytes())
        .containsExactly(gameDesignAbort.canonicalBytes());
    assertThat(f.stored().get(Owner.WORLD).canonicalBytes())
        .containsExactly(worldAbort.canonicalBytes());
  }

  @Test
  void mixedCommitOrderPreservesOriginalWorldCommitAndTerminalRetry() {
    Fixture f = fixture(Ordering.COMMIT_ORDER);
    OwnerReadback originalWorld =
        readback(f.binding(), Owner.WORLD, Outcome.COMMITTED, new byte[] {16});
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(),
                          Owner.GAME_DESIGN,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {17})));
            });
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return worldEvidence(request, originalWorld);
            });

    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.PENDING);
    assertThat(f.stored().get(Owner.WORLD).canonicalBytes())
        .containsExactly(originalWorld.canonicalBytes());
    byte[] originalGameDesignBytes = f.stored().get(Owner.GAME_DESIGN).canonicalBytes();
    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.PENDING);
    assertThat(f.stored().get(Owner.WORLD).canonicalBytes())
        .containsExactly(originalWorld.canonicalBytes());
    assertThat(f.stored().get(Owner.GAME_DESIGN).canonicalBytes())
        .containsExactly(originalGameDesignBytes);
    verify(f.gameDesignClient()).read(any());
    verify(f.worldClient()).read(any());
  }

  @Test
  void bothCommitOrderCommitsSettleAndRevokeOrderNeverAcceptsACommit() {
    Fixture committed = fixture(Ordering.COMMIT_ORDER);
    when(committed.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(committed);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          committed.binding(),
                          Owner.GAME_DESIGN,
                          Outcome.COMMITTED,
                          new byte[] {20})));
            });
    when(committed.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(committed);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return worldEvidence(
                  request,
                  readback(committed.binding(), Owner.WORLD, Outcome.COMMITTED, new byte[] {21}));
            });
    assertThat(committed.service().reconcile(committed.binding().operationId()))
        .contains(Settlement.COMMITTED);

    Fixture revoked = fixture(Ordering.REVOKE_ORDER);
    revoked
        .stored()
        .put(
            Owner.GAME_DESIGN,
            readback(revoked.binding(), Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {22}));
    when(revoked.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(revoked);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new WorldDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          revoked.binding(),
                          Owner.WORLD,
                          Outcome.DEFINITIVELY_ABORTED,
                          new byte[] {23})));
            });
    assertThat(revoked.service().reconcile(revoked.binding().operationId()))
        .contains(Settlement.PENDING);
    assertThat(revoked.stored().get(Owner.GAME_DESIGN).outcome()).isEqualTo(Outcome.COMMITTED);
  }

  @Test
  void absentReservedTerminalAndAmbientCasesNeverCallOwners() {
    Fixture missing = fixture(Ordering.COMMIT_ORDER);
    when(missing.repository().readOriginalBinding(any())).thenReturn(Optional.empty());
    assertThat(missing.service().reconcile(missing.binding().operationId())).isEmpty();
    verifyNoInteractions(missing.gameDesignClient(), missing.worldClient());

    Fixture reserved = fixture(Ordering.RESERVED);
    assertThat(reserved.service().reconcile(reserved.binding().operationId()))
        .contains(Settlement.PENDING);
    verifyNoInteractions(reserved.gameDesignClient(), reserved.worldClient());

    Fixture terminal = fixture(Ordering.COMMIT_ORDER);
    terminal
        .stored()
        .put(
            Owner.GAME_DESIGN,
            readback(terminal.binding(), Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {18}));
    terminal
        .stored()
        .put(
            Owner.WORLD,
            readback(terminal.binding(), Owner.WORLD, Outcome.COMMITTED, new byte[] {19}));
    assertThat(terminal.service().reconcile(terminal.binding().operationId()))
        .contains(Settlement.COMMITTED);
    verifyNoInteractions(terminal.gameDesignClient(), terminal.worldClient());

    Fixture ambient = fixture(Ordering.COMMIT_ORDER);
    var ambientTransaction = new TransactionTemplate(ambient.manager());
    ambientTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ambientTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    ambientTransaction.execute(
        status -> {
          assertThatThrownBy(() -> ambient.service().reconcile(ambient.binding().operationId()))
              .isInstanceOf(IllegalStateException.class);
          verifyNoInteractions(
              ambient.repository(), ambient.gameDesignClient(), ambient.worldClient());
          return null;
        });
  }

  @Test
  void v2WithoutWorldNeverReadsLooksUpOrRetainsWorldEvidence() {
    Fixture f =
        fixture(
            Ordering.COMMIT_ORDER,
            requiredBinding(List.of(DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)));
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(), Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {31})));
            });

    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.COMMITTED);
    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.COMMITTED);
    verifyNoInteractions(f.worldClient());
    verify(f.repository(), never()).readOwnerResult(any(), eq(Owner.WORLD));
    verify(f.repository()).recordOwnerReadback(eq(f.binding()), any());
    assertThat(f.stored()).containsOnlyKeys(Owner.GAME_DESIGN);
    verify(f.gameDesignClient()).read(any());
  }

  @Test
  void v2WorldAndGameDesignResultsCannotSettleMissingIndependentEntity() {
    Fixture f =
        fixture(
            Ordering.COMMIT_ORDER,
            requiredBinding(
                List.of(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    DraftCommitBinding.Owner.ENTITY_MANAGEMENT)));
    when(f.gameDesignClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (GameDesignDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return new GameDesignDraftTerminalReadEvidence(
                  request,
                  Optional.of(
                      readback(
                          f.binding(), Owner.GAME_DESIGN, Outcome.COMMITTED, new byte[] {32})));
            });
    when(f.worldClient().read(any()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction(f);
              var request = (WorldDraftTerminalReadEvidence.Request) invocation.getArgument(0);
              return worldEvidence(
                  request, readback(f.binding(), Owner.WORLD, Outcome.COMMITTED, new byte[] {33}));
            });

    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.PENDING);
    byte[] originalWorld = f.stored().get(Owner.WORLD).canonicalBytes();
    byte[] originalGameDesign = f.stored().get(Owner.GAME_DESIGN).canonicalBytes();
    assertThat(f.service().reconcile(f.binding().operationId())).contains(Settlement.PENDING);
    assertThat(f.stored()).containsOnlyKeys(Owner.GAME_DESIGN, Owner.WORLD);
    assertThat(f.stored().get(Owner.WORLD).canonicalBytes()).containsExactly(originalWorld);
    assertThat(f.stored().get(Owner.GAME_DESIGN).canonicalBytes())
        .containsExactly(originalGameDesign);
    verify(f.gameDesignClient()).read(any());
    verify(f.worldClient()).read(any());
    verify(f.repository(), never()).readOwnerResult(any(), eq(Owner.ENTITY));
  }

  private static DraftAuthorizationFenceBinding requiredBinding(
      List<DraftCommitBinding.Owner> owners) {
    var original = binding();
    var prior =
        DraftCommitBinding.fromStored(
            new String(original.gameDesignBinding(), java.nio.charset.StandardCharsets.UTF_8),
            original.inputDigest());
    var revisions =
        java.util.stream.IntStream.range(0, owners.size())
            .mapToObj(
                index ->
                    new DraftCommitBinding.RevisionPayload(
                        Integer.toString(index),
                        UUID.randomUUID(),
                        owners.get(index),
                        "payload-" + index))
            .toList();
    var affected =
        owners.stream()
            .map(
                owner ->
                    new DraftCommitBinding.AffectedUnit(
                        owner, "aggregate", owner.name(), "aggregate", owner.name(), "0"))
            .toList();
    var complete =
        DraftCommitBinding.create(
            prior.target(),
            prior.requestId(),
            prior.commitId(),
            prior.baseCommitId(),
            revisions,
            affected);
    return new DraftAuthorizationFenceBinding(
            original.operationId(),
            original.requestId(),
            original.commitId(),
            original.fenceId(),
            original.actorAccountId(),
            original.tenantId(),
            original.versionId(),
            original.baseCommitId(),
            original.expectedDraftEpoch(),
            complete.canonicalBytes(),
            complete.canonicalBytes(),
            complete.digest(),
            original.sources())
        .withRequiredOwners();
  }

  private static Fixture fixture(Ordering ordering) {
    return fixture(ordering, binding());
  }

  private static Fixture fixture(Ordering ordering, DraftAuthorizationFenceBinding binding) {
    DraftAuthorizationFenceRepository repository = mock(DraftAuthorizationFenceRepository.class);
    GameDesignDraftTerminalReadClient gameDesignClient =
        mock(GameDesignDraftTerminalReadClient.class);
    WorldDraftTerminalReadClient worldClient = mock(WorldDraftTerminalReadClient.class);
    TestTransactionManager manager = new TestTransactionManager();
    Map<Owner, OwnerReadback> stored = new EnumMap<>(Owner.class);
    when(repository.readOriginalBinding(any())).thenReturn(Optional.of(binding));
    when(repository.read(any())).thenAnswer(invocation -> fenceSnapshot(binding, ordering));
    when(repository.readOwnerResult(any(), any()))
        .thenAnswer(
            invocation -> {
              Owner owner = invocation.getArgument(1);
              OwnerReadback value = stored.get(owner);
              return value == null
                  ? Optional.empty()
                  : Optional.of(
                      new DraftAuthorizationFenceRepository.OwnerResultSnapshot(
                          value.outcome(), value.canonicalBytes(), OffsetDateTime.now()));
            });
    when(repository.readSettlement(any()))
        .thenAnswer(
            invocation -> {
              if (ordering == Ordering.RESERVED
                  || !stored.keySet().equals(java.util.Set.copyOf(binding.requiredOwners()))) {
                return Settlement.PENDING;
              }
              if (ordering == Ordering.COMMIT_ORDER
                  && stored.values().stream()
                      .allMatch(value -> value.outcome() == Outcome.COMMITTED)) {
                return Settlement.COMMITTED;
              }
              return stored.values().stream()
                      .allMatch(value -> value.outcome() == Outcome.DEFINITIVELY_ABORTED)
                  ? Settlement.FAILED_NONPUBLICATION
                  : Settlement.PENDING;
            });
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              OwnerReadback value = invocation.getArgument(1);
              OwnerReadback prior = stored.putIfAbsent(value.owner(), value);
              if (prior != null
                  && !java.util.Arrays.equals(prior.canonicalBytes(), value.canonicalBytes())) {
                throw new IllegalArgumentException("Changed owner terminal evidence");
              }
              return null;
            })
        .when(repository)
        .recordOwnerReadback(any(), any());
    return new Fixture(
        repository,
        gameDesignClient,
        worldClient,
        manager,
        binding,
        stored,
        new AccountDraftTerminalReconciliationService(
            repository, manager, gameDesignClient, worldClient, NAMESPACE));
  }

  private static DraftAuthorizationFenceRepository.FenceSnapshot fenceSnapshot(
      DraftAuthorizationFenceBinding binding, Ordering ordering) {
    return new DraftAuthorizationFenceRepository.FenceSnapshot(
        ordering,
        binding.canonicalBytes(),
        OffsetDateTime.parse("2026-10-06T00:00:00Z"),
        ordering == Ordering.RESERVED ? null : OffsetDateTime.parse("2026-10-06T00:00:01Z"));
  }

  private static void assertOutsideTransaction(Fixture f) {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(f.manager().commits).isGreaterThanOrEqualTo(1);
  }

  private static OwnerReadback readback(
      DraftAuthorizationFenceBinding binding, Owner owner, Outcome outcome, byte[] result) {
    return new OwnerReadback(
        owner,
        outcome,
        binding.operationId(),
        binding.commitId(),
        binding.fenceId(),
        binding.inputDigest(),
        binding.canonicalBytes(),
        result);
  }

  private static WorldDraftTerminalReadEvidence worldEvidence(
      WorldDraftTerminalReadEvidence.Request request, OwnerReadback readback) {
    if (readback.outcome() != Outcome.COMMITTED) {
      return new WorldDraftTerminalReadEvidence(request, Optional.of(readback));
    }
    WorldDraftTerminalReadEvidence response = mock(WorldDraftTerminalReadEvidence.class);
    when(response.request()).thenReturn(request);
    when(response.ownerReadback()).thenReturn(Optional.of(readback));
    return response;
  }

  private static DraftAuthorizationFenceBinding binding() {
    UUID account = UUID.randomUUID();
    var gameDesign =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "tenant-key",
                2,
                "tenant-key",
                "NEW_GAME_ROW"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0", UUID.randomUUID(), DraftCommitBinding.Owner.WORLD_MANAGEMENT, "payload")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.WORLD_MANAGEMENT,
                    "region",
                    "region-1",
                    "aggregate",
                    "region-1",
                    "0")));
    return new DraftAuthorizationFenceBinding(
        UUID.randomUUID(),
        gameDesign.requestId(),
        gameDesign.commitId(),
        UUID.randomUUID(),
        account,
        gameDesign.target().canonicalTenantId(),
        gameDesign.target().canonicalVersionId(),
        "base",
        "0",
        gameDesign.canonicalBytes(),
        gameDesign.canonicalBytes(),
        gameDesign.digest(),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT, account.toString(), "1", "1", "stream", "0", new byte[] {1})));
  }

  private record Fixture(
      DraftAuthorizationFenceRepository repository,
      GameDesignDraftTerminalReadClient gameDesignClient,
      WorldDraftTerminalReadClient worldClient,
      TestTransactionManager manager,
      DraftAuthorizationFenceBinding binding,
      Map<Owner, OwnerReadback> stored,
      AccountDraftTerminalReconciliationService service) {}

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private int commits;
    private int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      assertThat(definition.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
      assertThat(definition.getPropagationBehavior())
          .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThat(definition.isReadOnly()).isFalse();
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }
}
