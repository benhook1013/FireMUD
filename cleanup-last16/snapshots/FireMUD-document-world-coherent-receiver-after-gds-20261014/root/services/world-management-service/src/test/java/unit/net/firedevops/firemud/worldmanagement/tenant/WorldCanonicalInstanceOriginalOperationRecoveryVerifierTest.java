package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationHistoricalReadRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence.LaunchAssociation;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidenceReadClient;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceExecutionIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceOriginalOperationRecoveryVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService.PreparationDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Mocked owner-client evidence exercises composition only; it is not genuine cross-owner proof. */
class WorldCanonicalInstanceOriginalOperationRecoveryVerifierTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NAMESPACE = "gameplay";
  private static final UUID TENANT = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE = uuid("23232323-2323-4323-8323-232323232323");
  private static final UUID PLAYABLE_NAMESPACE = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID CANONICAL_REALM = uuid("88888888-8888-4888-8888-888888888888");
  private static final UUID PARTICIPATION_ID = uuid("13131313-1313-4313-8313-131313131313");
  private static final UUID ATTEMPT_ID = uuid("12121212-1212-4121-8121-121212121212");
  private static final UUID VERSION_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID VERSION_OPERATION_ID = uuid("77777777-7777-4777-8777-777777777777");
  private static final UUID COMMIT_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final long ACCOUNT_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final long GAME_TEMPLATE_ID = 91L;
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final StartSessionPostAuthorizationExecutionTuple ORIGINAL_OPERATION =
      originalOperation("start-session-control-request");
  private static final byte[] ORIGINAL_TUPLE = ORIGINAL_OPERATION.canonicalBytes();
  private static final byte[] CHANGED_TUPLE =
      originalOperation("start-session-changed-request").canonicalBytes();

  @AfterEach
  void clearAmbientTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void acceptsExactHistoricalOwnerEvidenceAndUsesFreshReadCorrelations() {
    acceptsExactHistoricalOwnerEvidenceAndUsesFreshReadCorrelations(fixture(false));
  }

  @Test
  void acceptsSerializerShapedSchemaTwoHistoricalEvidence() {
    acceptsExactHistoricalOwnerEvidenceAndUsesFreshReadCorrelations(fixture(false, true));
  }

  @Test
  void newSchemaRequiresIdentityAndGameSessionEvidenceToRetainTheSameCatalogRevision()
      throws Exception {
    Fixture fixture = fixture(false);
    assertThat(fixture.preparationInputJson()).contains("\"schemaVersion\":3");
    var changed =
        (tools.jackson.databind.node.ObjectNode) JSON.readTree(fixture.preparationInputJson());
    ((tools.jackson.databind.node.ObjectNode) changed.get("gameSessionReadEvidence"))
        .put("catalogRevision", 10L);
    String changedInput = json(changed);
    when(fixture.identity().preparationInputJson()).thenReturn(changedInput);
    when(fixture.identity().preparationInputDigest())
        .thenReturn(sha256(changedInput.getBytes(StandardCharsets.UTF_8)));

    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);

    assertDenied(verifier(account, gameSession), fixture.identity());
    verifyNoInteractions(account, gameSession);
  }

  private void acceptsExactHistoricalOwnerEvidenceAndUsesFreshReadCorrelations(Fixture fixture) {
    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
        .thenAnswer(
            invocation ->
                accountEvidence(
                    invocation.getArgument(0),
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    fixture.preparationInputJson()));
    when(gameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                gameSessionEvidence(
                    invocation.getArgument(0),
                    fixture,
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    fixture.launchAssociation()));

    verifier(account, gameSession).verifyOriginalOperation(fixture.identity());

    var accountRequest =
        org.mockito.ArgumentCaptor.forClass(
            AccountStartSessionWorldParticipationHistoricalReadRequest.class);
    verify(account).read(accountRequest.capture());
    assertThat(accountRequest.getValue().targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(accountRequest.getValue().originalPostAuthorizationTuple())
        .containsExactly(ORIGINAL_TUPLE);
    assertThat(accountRequest.getValue().readRequestId())
        .isNotEqualTo(PARTICIPATION_ID)
        .isNotEqualTo(ATTEMPT_ID)
        .isNotEqualTo(GAME_INSTANCE)
        .isNotEqualTo(RESERVATION_OWNER);

    var gameSessionRequest =
        org.mockito.ArgumentCaptor.forClass(
            HistoricalOriginalStartSessionOwnerEvidence.Request.class);
    verify(gameSession).read(gameSessionRequest.capture());
    assertThat(gameSessionRequest.getValue().associationSelector().readRequestId())
        .isNotEqualTo(GAME_INSTANCE)
        .isNotEqualTo(PARTICIPATION_ID)
        .isNotEqualTo(ATTEMPT_ID);
    assertThat(gameSessionRequest.getValue().expectedOwnerAttemptId()).isEqualTo(ATTEMPT_ID);
    assertThat(gameSessionRequest.getValue().expectedOwnerFence()).isEqualTo(GAME_SESSION_FENCE);
    assertThat(fixture.launchAssociation().capturedStartingRowVersion()).isEqualTo(9_999L);
  }

  @Test
  void rejectsAccountTupleAttemptOrFenceSubstitutionBeforeGameSessionRead() {
    for (int substitution = 0; substitution < 3; substitution++) {
      Fixture fixture = fixture(false);
      var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
      var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
      byte[] tuple = substitution == 0 ? CHANGED_TUPLE : ORIGINAL_TUPLE;
      UUID attempt = substitution == 1 ? uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") : ATTEMPT_ID;
      long fence = substitution == 2 ? GAME_SESSION_FENCE + 1L : GAME_SESSION_FENCE;
      when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
          .thenAnswer(
              invocation ->
                  accountEvidence(
                      invocation.getArgument(0),
                      tuple,
                      attempt,
                      fence,
                      fixture.preparationInputJson()));

      assertDenied(verifier(account, gameSession), fixture.identity());
      verifyNoInteractions(gameSession);
    }
  }

  @Test
  void rejectsAccountResolvedParticipationIdOrFenceBeforeGameSessionRead() {
    for (boolean substituteId : new boolean[] {true, false}) {
      Fixture fixture = fixture(false);
      var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
      var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
      when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
          .thenAnswer(
              invocation -> {
                var evidence =
                    accountEvidence(
                        invocation.getArgument(0),
                        ORIGINAL_TUPLE,
                        ATTEMPT_ID,
                        GAME_SESSION_FENCE,
                        fixture.preparationInputJson());
                if (substituteId) {
                  when(evidence.accountWorldParticipationId())
                      .thenReturn(uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
                } else {
                  when(evidence.accountWorldParticipationFence()).thenReturn(ACCOUNT_FENCE + 1L);
                }
                return evidence;
              });

      assertDenied(verifier(account, gameSession), fixture.identity());
      verifyNoInteractions(gameSession);
    }
  }

  @Test
  void rejectsGameSessionTupleAttemptOrFenceSubstitution() {
    for (int substitution = 0; substitution < 3; substitution++) {
      Fixture fixture = fixture(false);
      var account = exactAccountClient(fixture);
      var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
      byte[] tuple = substitution == 0 ? CHANGED_TUPLE : ORIGINAL_TUPLE;
      UUID attempt = substitution == 1 ? uuid("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa") : ATTEMPT_ID;
      long fence = substitution == 2 ? GAME_SESSION_FENCE + 1L : GAME_SESSION_FENCE;
      when(gameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
          .thenAnswer(
              invocation ->
                  gameSessionEvidence(
                      invocation.getArgument(0),
                      fixture,
                      tuple,
                      attempt,
                      fence,
                      fixture.launchAssociation()));

      assertDenied(verifier(account, gameSession), fixture.identity());
    }
  }

  @Test
  void rejectsAccountCaptureFenceOrLinearizationSubstitutionBeforeGameSessionRead() {
    for (byte[] substituted : changedCaptureReferences()) {
      Fixture fixture = fixture(false);
      var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
      var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
      when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
          .thenAnswer(
              invocation ->
                  accountEvidence(
                      invocation.getArgument(0),
                      substituted,
                      ATTEMPT_ID,
                      GAME_SESSION_FENCE,
                      fixture.preparationInputJson()));

      assertDenied(verifier(account, gameSession), fixture.identity());
      verifyNoInteractions(gameSession);
    }
  }

  @Test
  void rejectsGameSessionCaptureFenceOrLinearizationSubstitution() {
    for (byte[] substituted : changedCaptureReferences()) {
      Fixture fixture = fixture(false);
      var account = exactAccountClient(fixture);
      var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
      when(gameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
          .thenAnswer(
              invocation ->
                  gameSessionEvidence(
                      invocation.getArgument(0),
                      fixture,
                      substituted,
                      ATTEMPT_ID,
                      GAME_SESSION_FENCE,
                      fixture.launchAssociation()));

      assertDenied(verifier(account, gameSession), fixture.identity());
    }
  }

  private static List<byte[]> changedCaptureReferences() {
    var originalReference = ORIGINAL_OPERATION.bundleReference();
    return List.of(
        withReference(
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                originalReference.bundleVersion(),
                originalReference.sourceVersion(),
                "24",
                originalReference.linearization())),
        withReference(
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                originalReference.bundleVersion(),
                originalReference.sourceVersion(),
                originalReference.sourceFence(),
                "18446744073709551614")));
  }

  // Structurally canonical substitutions exercise World comparison of the complete original
  // bytes. Mocked owner responses do not establish independent Account capture authentication.
  private static byte[] withReference(
      StartSessionAuthorityEvidenceBundle.BundleReference reference) {
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
            ORIGINAL_OPERATION.preAuthorizationTuple(),
            ORIGINAL_OPERATION.authenticatedWorkloadIdentity(),
            ORIGINAL_OPERATION.authorizationReferenceFingerprint(),
            ORIGINAL_OPERATION.reservationOwnerId(),
            ORIGINAL_OPERATION.reservationClaimFence(),
            ORIGINAL_OPERATION.authorityEvidenceBundleBytes(),
            reference)
        .canonicalBytes();
  }

  @Test
  void rejectsDescriptorOrSourceAssociationSubstitution() {
    Fixture exact = fixture(false);
    var account = exactAccountClient(exact);
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    LaunchAssociation substitutedDescriptor = fixture(true).launchAssociation();
    when(gameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                gameSessionEvidence(
                    invocation.getArgument(0),
                    exact,
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    substitutedDescriptor));
    assertDenied(verifier(account, gameSession), exact.identity());

    Fixture substitutedSource = fixtureWithSubstitutedSource();
    var sourceAccount = exactAccountClient(substitutedSource);
    var sourceGameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(sourceGameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                gameSessionEvidence(
                    invocation.getArgument(0),
                    substitutedSource,
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    exact.launchAssociation()));
    assertDenied(verifier(sourceAccount, sourceGameSession), substitutedSource.identity());
  }

  @Test
  void rejectsPreparationInputSubstitutionAndMissingOwnerEvidence() {
    Fixture fixture = fixture(false);
    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
        .thenAnswer(
            invocation ->
                accountEvidence(
                    invocation.getArgument(0),
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    fixture.preparationInputJson() + " "));
    assertDenied(verifier(account, gameSession), fixture.identity());
    verifyNoInteractions(gameSession);

    var missingAccount = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    var missingGameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(missingAccount.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
        .thenReturn(null);
    assertDenied(verifier(missingAccount, missingGameSession), fixture.identity());
    verifyNoInteractions(missingGameSession);

    var exactAccount = exactAccountClient(fixture);
    var missingGameSessionResult =
        mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(missingGameSessionResult.read(
            any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
        .thenReturn(null);
    assertDenied(verifier(exactAccount, missingGameSessionResult), fixture.identity());
  }

  @Test
  void rejectsSchemaTwoSelectorSubstitutionAgainstTheRetainedRelease() {
    Fixture fixture = fixture(false, true);
    String selectedEvidence =
        java.util.Base64.getEncoder()
            .encodeToString(
                fixture
                    .launchAssociation()
                    .launchBindingEvidence()
                    .releaseAttestation()
                    .worldStartLocationEvidence()
                    .canonicalBytes());
    String substitutedInput = fixture.preparationInputJson().replace(selectedEvidence, "e30=");
    assertThat(substitutedInput)
        .isNotEqualTo(fixture.preparationInputJson())
        .contains("\"schemaVersion\":2", "\"worldStartLocationEvidenceBase64\":\"e30=\"");
    when(fixture.identity().preparationInputJson()).thenReturn(substitutedInput);
    when(fixture.identity().preparationInputDigest())
        .thenReturn(sha256(substitutedInput.getBytes(StandardCharsets.UTF_8)));

    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
        .thenAnswer(
            invocation ->
                accountEvidence(
                    invocation.getArgument(0),
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    substitutedInput));
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    when(gameSession.read(any(HistoricalOriginalStartSessionOwnerEvidence.Request.class)))
        .thenAnswer(
            invocation ->
                gameSessionEvidence(
                    invocation.getArgument(0),
                    fixture,
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    fixture.launchAssociation()));

    assertDenied(verifier(account, gameSession), fixture.identity());
  }

  @Test
  void ambientSqlAndSynchronizationAreRejectedBeforeOwnerReads() {
    Fixture fixture = fixture(false);
    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    var gameSession = mock(HistoricalOriginalStartSessionOwnerEvidenceReadClient.class);
    var verifier = verifier(account, gameSession);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> verifier.verifyOriginalOperation(fixture.identity()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    TransactionSynchronizationManager.clear();

    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> verifier.verifyOriginalOperation(fixture.identity()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an ambient transaction");
    verifyNoInteractions(account, gameSession);
  }

  private static Fixture fixture(boolean alternateDescriptor) {
    return fixture(alternateDescriptor, false, true);
  }

  private static Fixture fixture(boolean alternateDescriptor, boolean selectedSchema) {
    return fixture(alternateDescriptor, selectedSchema, false);
  }

  private static Fixture fixture(
      boolean alternateDescriptor, boolean selectedSchema, boolean capturedCatalog) {
    WorldPublishedStartLocationEvidence selector = selectedSchema ? retainedWorldSelector() : null;
    WorldAuthoredSourceIntakeReceipt source =
        sourceReceipt(false, selector == null ? null : selector.request().intakeRequestId());
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(source, alternateDescriptor);
    AuthoredWorldReleaseAttestationEvidence release = release(descriptor, selector);
    return fixture(source, descriptor, release, selector, capturedCatalog);
  }

  private static WorldPublishedStartLocationEvidence retainedWorldSelector() {
    try {
      return WorldCanonicalPlayerLocationGrpcServiceTest.publishedSelector(NAMESPACE);
    } catch (Exception malformedFixture) {
      throw new AssertionError(
          "Constructor-validated World selector fixture is invalid", malformedFixture);
    }
  }

  private static Fixture fixtureWithSubstitutedSource() {
    WorldAuthoredSourceIntakeReceipt originalSource = sourceReceipt(false);
    AuthoredWorldLaunchDescriptorEvidence descriptor = descriptor(originalSource, false);
    AuthoredWorldReleaseAttestationEvidence release = release(descriptor);
    WorldAuthoredSourceIntakeReceipt substitutedSource = sourceReceipt(true);
    return fixture(substitutedSource, descriptor, release, null, true);
  }

  private static Fixture fixture(
      WorldAuthoredSourceIntakeReceipt retainedSource,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence release,
      WorldPublishedStartLocationEvidence selector,
      boolean capturedCatalog) {
    String input = preparationInput(descriptor, release, retainedSource, selector, capturedCatalog);
    WorldCanonicalInstanceExecutionIdentity identity =
        mock(WorldCanonicalInstanceExecutionIdentity.class);
    when(identity.originalPostAuthorizationTuple()).thenReturn(ORIGINAL_TUPLE.clone());
    when(identity.decodedTuple()).thenReturn(ORIGINAL_OPERATION);
    when(identity.accountWorldParticipationId()).thenReturn(PARTICIPATION_ID);
    when(identity.accountWorldParticipationFence()).thenReturn(ACCOUNT_FENCE);
    when(identity.gameSessionOwnerAttemptId()).thenReturn(ATTEMPT_ID);
    when(identity.gameSessionOwnerFence()).thenReturn(GAME_SESSION_FENCE);
    when(identity.canonicalGameInstanceId()).thenReturn(GAME_INSTANCE);
    when(identity.targetNamespace()).thenReturn(NAMESPACE);
    when(identity.canonicalTenantId()).thenReturn(TENANT);
    when(identity.controlPlaneRequestId()).thenReturn("start-session-control-request");
    when(identity.gameTemplateId()).thenReturn(GAME_TEMPLATE_ID);
    when(identity.preparationInputJson()).thenReturn(input);
    when(identity.preparationInputDigest())
        .thenReturn(sha256(input.getBytes(StandardCharsets.UTF_8)));
    return new Fixture(
        identity,
        input,
        retainedSource,
        launchAssociation(new CompleteLaunchBindingEvidence(descriptor, release)));
  }

  private static WorldAuthoredSourceIntakeReceipt sourceReceipt(boolean alternate) {
    return sourceReceipt(alternate, null);
  }

  private static WorldAuthoredSourceIntakeReceipt sourceReceipt(
      boolean alternate, UUID intakeRequestId) {
    UUID registration =
        uuid(
            alternate
                ? "99999999-9999-4999-8999-999999999999"
                : "a1111111-1111-4111-8111-111111111111");
    UUID operation =
        uuid(
            alternate
                ? "a2222222-2222-4222-8222-222222222222"
                : "a3333333-3333-4333-8333-333333333333");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registration, TENANT, "tenant", "world", "A World");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            TENANT,
            "tenant",
            "world",
            "A World",
            17L,
            "tenant-key",
            "NEW_GAME_ROW");
    AuthoredWorldSourceEvidence source =
        new AuthoredWorldSourceEvidence(
            1,
            NAMESPACE,
            registration,
            operation,
            requestDigest,
            TENANT,
            "tenant",
            "world",
            "A World",
            17L,
            "tenant-key",
            "NEW_GAME_ROW",
            evidenceDigest);
    UUID intakeRequest =
        intakeRequestId == null ? uuid("a4444444-4444-4444-8444-444444444444") : intakeRequestId;
    UUID intakeOperation = uuid("a5555555-5555-4555-8555-555555555555");
    long localTenantKey = 51L;
    String intakeRequestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, intakeRequest, source);
    String receiptDigest =
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, intakeOperation, intakeRequestDigest, source, localTenantKey);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        NAMESPACE,
        intakeRequest,
        intakeOperation,
        TENANT,
        "world",
        operation,
        evidenceDigest,
        intakeRequestDigest,
        receiptDigest,
        localTenantKey,
        source);
  }

  private static AuthoredWorldLaunchDescriptorEvidence descriptor(
      WorldAuthoredSourceIntakeReceipt source, boolean alternate) {
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            "start-session-control-request",
            TENANT,
            "world",
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            alternate ? GAME_TEMPLATE_ID + 1L : GAME_TEMPLATE_ID,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    return AuthoredWorldLaunchDescriptorEvidence.create(
        request,
        alternate ? "launch-descriptor-substitute" : "launch-descriptor-original",
        42L,
        false,
        null,
        "{}",
        "generation-config-1",
        7L,
        51L,
        "release-bundle-51",
        false,
        null);
  }

  private static AuthoredWorldReleaseAttestationEvidence release(
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    return release(descriptor, null);
  }

  private static AuthoredWorldReleaseAttestationEvidence release(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      WorldPublishedStartLocationEvidence selector) {
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants = new ArrayList<>();
    for (String participant : AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder()) {
      boolean hasAbilityDigest = "GAME_LOGIC".equals(participant);
      String contentDigest =
          selector != null && "WORLD_MANAGEMENT".equals(participant)
              ? selector.request().contentDigest()
              : "a".repeat(64);
      participants.add(
          new AuthoredWorldReleaseAttestationEvidence.Participant(
              participant,
              "42",
              false,
              null,
              COMMIT_ID.toString(),
              contentDigest,
              AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                  participant,
                  selector == null
                      ? AuthoredWorldReleaseAttestationEvidence.SCHEMA_VERSION
                      : AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
              hasAbilityDigest,
              hasAbilityDigest ? sha256("ability-schema".getBytes(StandardCharsets.UTF_8)) : null));
    }
    if (selector == null) {
      return AuthoredWorldReleaseAttestationEvidence.create(
          NAMESPACE,
          descriptor.resultDigest(),
          TENANT,
          VERSION_ID,
          "world",
          descriptor.authoredWorldSourceOperationId(),
          descriptor.authoredWorldSourceEvidenceDigest(),
          descriptor.launchDescriptorId(),
          descriptor.publishedReleaseBundleRef(),
          descriptor.versionStateEpoch(),
          "publish-workflow-original",
          COMMIT_ID.toString(),
          participants,
          sha256("manifest".getBytes(StandardCharsets.UTF_8)),
          1,
          List.of(),
          List.of(),
          List.of(),
          descriptor.generationConfigRevision());
    }
    return AuthoredWorldReleaseAttestationEvidence.create(
        NAMESPACE,
        descriptor.resultDigest(),
        TENANT,
        VERSION_ID,
        "world",
        descriptor.authoredWorldSourceOperationId(),
        descriptor.authoredWorldSourceEvidenceDigest(),
        descriptor.launchDescriptorId(),
        descriptor.publishedReleaseBundleRef(),
        descriptor.versionStateEpoch(),
        selector.request().publishWorkflowId(),
        selector.request().appliedCommitId(),
        participants,
        sha256("manifest".getBytes(StandardCharsets.UTF_8)),
        1,
        List.of(),
        List.of(),
        List.of(),
        descriptor.generationConfigRevision(),
        selector);
  }

  private static String preparationInput(
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      AuthoredWorldReleaseAttestationEvidence release,
      WorldAuthoredSourceIntakeReceipt source,
      WorldPublishedStartLocationEvidence selector,
      boolean capturedCatalog) {
    Map<String, Object> identity =
        map(
            "canonicalGameInstanceId",
            GAME_INSTANCE.toString(),
            "targetNamespace",
            NAMESPACE,
            "canonicalTenantId",
            TENANT.toString(),
            "worldSlug",
            "world",
            "playableStateNamespaceId",
            PLAYABLE_NAMESPACE.toString(),
            "playableStateScope",
            "SHARED",
            "publicProduction",
            true,
            "controlPlaneRequestId",
            "start-session-control-request");
    if (capturedCatalog) {
      identity.put("canonicalRealmId", CANONICAL_REALM.toString());
      identity.put("catalogRevision", 9L);
    }
    Map<String, Object> readRequest =
        map(
            "targetNamespace", NAMESPACE,
            "canonicalTenantId", TENANT.toString(),
            "worldSlug", "world",
            "canonicalGameInstanceId", GAME_INSTANCE.toString(),
            "controlPlaneRequestId", "start-session-control-request",
            "launchDescriptorId", descriptor.launchDescriptorId(),
            "expectedDescriptorRequestDigest", descriptor.requestDigest(),
            "expectedDescriptorResultDigest", descriptor.resultDigest(),
            "expectedReleaseAttestationEvidenceDigest", release.evidenceDigest());
    Map<String, Object> readEvidence =
        map(
            "targetNamespace", NAMESPACE,
            "canonicalTenantId", TENANT.toString(),
            "worldSlug", "world",
            "canonicalGameInstanceId", GAME_INSTANCE.toString(),
            "controlPlaneRequestId", "start-session-control-request",
            "launchDescriptorId", descriptor.launchDescriptorId(),
            "descriptorRequestDigest", descriptor.requestDigest(),
            "descriptorResultDigest", descriptor.resultDigest(),
            "releaseAttestationEvidenceDigest", release.evidenceDigest(),
            "playableStateNamespaceId", PLAYABLE_NAMESPACE.toString(),
            "playableStateScope", "SHARED",
            "publicProduction", true,
            "descriptorJson", json(descriptor),
            "releaseAttestationJson", json(release));
    if (capturedCatalog) {
      readEvidence.put("canonicalRealmId", CANONICAL_REALM.toString());
      readEvidence.put("catalogRevision", 9L);
    }
    Map<String, Object> launch =
        map(
            "schemaVersion", 1,
            "operationId", "a6666666-6666-4666-8666-666666666666",
            "targetNamespace", NAMESPACE,
            "canonicalTenantId", TENANT.toString(),
            "worldSlug", "world",
            "controlPlaneRequestId", "start-session-control-request",
            "descriptorRequestDigest", descriptor.requestDigest(),
            "descriptorResultDigest", descriptor.resultDigest(),
            "releaseAttestationDigest", release.evidenceDigest(),
            "canonicalVersionId", VERSION_ID.toString(),
            "localTenantKey", source.localTenantKey(),
            "intakeOperationId", source.operationId().toString(),
            "intakeRequestId", source.intakeRequestId().toString(),
            "sourceOperationId", source.sourceOperationId().toString(),
            "sourceEvidenceDigest", source.sourceEvidenceDigest(),
            "intakeRequestDigest", source.requestDigest(),
            "intakeReceiptDigest", source.receiptDigest());
    var evidence = source.source();
    Map<String, Object> sourceEvidence =
        map(
            "schemaVersion", evidence.schemaVersion(),
            "registrationRequestId", evidence.registrationRequestId().toString(),
            "sourceOperationId", evidence.operationId().toString(),
            "requestDigest", evidence.requestDigest(),
            "canonicalTenantId", evidence.canonicalTenantId().toString(),
            "tenantSlug", evidence.tenantSlug(),
            "worldSlug", evidence.worldSlug(),
            "worldDisplayName", evidence.worldDisplayName(),
            "sourceGameRowId", evidence.sourceGameRowId(),
            "sourceGameTenantKey", evidence.sourceGameTenantKey(),
            "provenanceKind", evidence.provenanceKind(),
            "evidenceDigest", evidence.evidenceDigest());
    Map<String, Object> sourceIntake =
        map(
            "schemaVersion", source.schemaVersion(),
            "targetNamespace", source.targetNamespace(),
            "intakeRequestId", source.intakeRequestId().toString(),
            "operationId", source.operationId().toString(),
            "canonicalTenantId", source.canonicalTenantId().toString(),
            "worldSlug", source.worldSlug(),
            "sourceOperationId", source.sourceOperationId().toString(),
            "sourceEvidenceDigest", source.sourceEvidenceDigest(),
            "requestDigest", source.requestDigest(),
            "receiptDigest", source.receiptDigest(),
            "localTenantKey", source.localTenantKey(),
            "sourceEvidence", sourceEvidence);
    Map<String, Object> versionIdentity =
        map(
            "schemaVersion",
            1,
            "operationId",
            VERSION_OPERATION_ID.toString(),
            "canonicalVersionId",
            VERSION_ID.toString(),
            "localVersionKey",
            61L,
            "gameDesignVersionId",
            42L,
            "versionState",
            "VERSION_LIFECYCLE_STATE_PUBLISHED",
            "versionStateEpoch",
            5L,
            "evidenceDigest",
            sha256("version-state".getBytes(StandardCharsets.UTF_8)));
    Map<String, Object> topology =
        map(
            "captureId",
            "a7777777-7777-4777-8777-777777777777",
            "targetNamespace",
            NAMESPACE,
            "canonicalTenantId",
            TENANT.toString(),
            "canonicalVersionId",
            VERSION_ID.toString(),
            "versionIdentityOperationId",
            VERSION_OPERATION_ID.toString(),
            "requestId",
            selector == null
                ? "a8888888-8888-4888-8888-888888888889"
                : DraftAuthorizationFenceBinding.fromStored(selector.originalAccountBindingBytes())
                    .requestId()
                    .toString(),
            "commitId",
            COMMIT_ID.toString(),
            "freezeRequestId",
            selector == null ? "publication-request-1" : selector.request().publicationRequestId(),
            "publicationFence",
            selector == null
                ? "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                : selector.request().publicationFence().toString(),
            "publicationRequestDigest",
            selector == null
                ? sha256("publication".getBytes(StandardCharsets.UTF_8))
                    .substring("sha256:".length())
                : selector.request().requestDigest(),
            "appliedCommitId",
            selector == null ? COMMIT_ID.toString() : selector.request().appliedCommitId(),
            "planDigest",
            sha256("topology-plan".getBytes(StandardCharsets.UTF_8)),
            "regionCount",
            1,
            "zoneCount",
            selector == null ? 0 : 1,
            "roomCount",
            selector == null ? 0 : 1,
            "exitCount",
            0,
            "generationRuleCount",
            0,
            "spawnBindingCount",
            0);
    Map<String, Object> root =
        map(
            "schemaVersion",
            capturedCatalog ? (selector == null ? 3 : 4) : (selector == null ? 1 : 2),
            "identity",
            identity,
            "gameSessionReadRequest",
            readRequest,
            "gameSessionReadEvidence",
            readEvidence,
            "launchBinding",
            launch,
            "versionIdentity",
            versionIdentity,
            "sourceIntake",
            sourceIntake,
            "topology",
            topology);
    if (selector != null) {
      root.put(
          "worldStartLocationEvidenceBase64",
          java.util.Base64.getEncoder().encodeToString(selector.canonicalBytes()));
    }
    return json(root);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalOperation(String requestId) {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            requestId,
            uuid("a4f5f4eb-8243-4d42-903a-33495456a622"),
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(
                    GAME_TEMPLATE_ID, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic historical World recovery carrier test"));
    byte[] bundle =
        StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
            preTuple,
            new Values(
                uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
                "sha256:" + "a".repeat(64),
                "17",
                "CURRENT",
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                1L,
                2L,
                3L,
                4L,
                5L,
                23L,
                uuid("a681bba7-c215-4cf1-a35b-14348912cbdc"),
                "2",
                "3"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        RESERVATION_OWNER,
        19L,
        bundle,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static AccountStartSessionWorldParticipationHistoricalReadClient exactAccountClient(
      Fixture fixture) {
    var account = mock(AccountStartSessionWorldParticipationHistoricalReadClient.class);
    when(account.read(any(AccountStartSessionWorldParticipationHistoricalReadRequest.class)))
        .thenAnswer(
            invocation ->
                accountEvidence(
                    invocation.getArgument(0),
                    ORIGINAL_TUPLE,
                    ATTEMPT_ID,
                    GAME_SESSION_FENCE,
                    fixture.preparationInputJson()));
    return account;
  }

  private static AccountStartSessionWorldParticipationHistoricalReadEvidence accountEvidence(
      AccountStartSessionWorldParticipationHistoricalReadRequest request,
      byte[] tuple,
      UUID attempt,
      long fence,
      String preparationInput) {
    var evidence = mock(AccountStartSessionWorldParticipationHistoricalReadEvidence.class);
    var echoedRequest =
        new AccountStartSessionWorldParticipationHistoricalReadRequest(
            request.readRequestId(), request.targetNamespace(), tuple);
    when(evidence.request()).thenReturn(echoedRequest);
    when(evidence.accountWorldParticipationId()).thenReturn(PARTICIPATION_ID);
    when(evidence.accountWorldParticipationFence()).thenReturn(ACCOUNT_FENCE);
    when(evidence.gameSessionOwnerAttemptId()).thenReturn(attempt);
    when(evidence.gameSessionOwnerFence()).thenReturn(fence);
    when(evidence.canonicalGameInstanceId()).thenReturn(GAME_INSTANCE);
    when(evidence.preparationInputJson()).thenReturn(preparationInput);
    when(evidence.preparationInputDigest())
        .thenReturn(sha256(preparationInput.getBytes(StandardCharsets.UTF_8)));
    return evidence;
  }

  private static HistoricalOriginalStartSessionOwnerEvidence.Result gameSessionEvidence(
      HistoricalOriginalStartSessionOwnerEvidence.Request request,
      Fixture fixture,
      byte[] tupleBytes,
      UUID attempt,
      long fence,
      LaunchAssociation launchAssociation) {
    var result = mock(HistoricalOriginalStartSessionOwnerEvidence.Result.class);
    var tuple = mock(StartSessionPostAuthorizationExecutionTuple.class);
    when(tuple.canonicalBytes()).thenReturn(tupleBytes.clone());
    var selection = mock(StartSessionTemplateAssociationReadEvidence.Result.class);
    var selectionAssociation = mock(StartSessionTemplateAssociationReadEvidence.Association.class);
    var retainedRelease = fixture.launchAssociation().launchBindingEvidence().releaseAttestation();
    when(selectionAssociation.targetNamespace()).thenReturn(NAMESPACE);
    when(selectionAssociation.canonicalTenantId()).thenReturn(TENANT);
    when(selectionAssociation.templateId()).thenReturn(GAME_TEMPLATE_ID);
    when(selectionAssociation.worldSlug()).thenReturn("world");
    when(selectionAssociation.canonicalVersionId())
        .thenReturn(retainedRelease.canonicalVersionId());
    when(selectionAssociation.selectedCommitId())
        .thenReturn(UUID.fromString(retainedRelease.commitId()));
    when(selectionAssociation.publishWorkflowId()).thenReturn(retainedRelease.publishWorkflowId());
    when(selectionAssociation.sourceOperationId())
        .thenReturn(fixture.retainedSource().sourceOperationId());
    when(selectionAssociation.sourceEvidenceDigest())
        .thenReturn(fixture.retainedSource().sourceEvidenceDigest());
    when(selection.association()).thenReturn(selectionAssociation);
    when(result.request()).thenReturn(request);
    when(result.originalTuple()).thenReturn(tuple);
    when(result.ownerAttemptId()).thenReturn(attempt);
    when(result.ownerFence()).thenReturn(fence);
    when(result.firstSelection()).thenReturn(selection);
    when(result.launchAssociation()).thenReturn(launchAssociation);
    return result;
  }

  private static LaunchAssociation launchAssociation(CompleteLaunchBindingEvidence binding) {
    var descriptor = binding.descriptor();
    return new LaunchAssociation(
        NAMESPACE,
        TENANT,
        "world",
        GAME_INSTANCE,
        "start-session-control-request",
        descriptor.launchDescriptorId(),
        PLAYABLE_NAMESPACE,
        RealmEntryPolicy.StateScope.SHARED,
        true,
        9_999L,
        binding);
  }

  private static WorldCanonicalInstanceOriginalOperationRecoveryVerifier verifier(
      AccountStartSessionWorldParticipationHistoricalReadClient account,
      HistoricalOriginalStartSessionOwnerEvidenceReadClient gameSession) {
    return new WorldCanonicalInstanceOriginalOperationRecoveryVerifier(account, gameSession);
  }

  private static void assertDenied(
      WorldCanonicalInstanceOriginalOperationRecoveryVerifier verifier,
      WorldCanonicalInstanceExecutionIdentity identity) {
    assertThatThrownBy(() -> verifier.verifyOriginalOperation(identity))
        .isInstanceOf(PreparationDeniedException.class);
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JacksonException failure) {
      throw new IllegalStateException("Synthetic recovery input could not be serialized", failure);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record Fixture(
      WorldCanonicalInstanceExecutionIdentity identity,
      String preparationInputJson,
      WorldAuthoredSourceIntakeReceipt retainedSource,
      LaunchAssociation launchAssociation) {}
}
