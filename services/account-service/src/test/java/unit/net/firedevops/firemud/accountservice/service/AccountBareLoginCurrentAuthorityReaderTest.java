package unit.net.firedevops.firemud.accountservice.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.service.AccountBareLoginCurrentAuthorityReader;
import net.firedevops.firemud.accountservice.service.AccountBareLoginCurrentAuthorityReader.CurrentAuthorityReadback;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.OriginalSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountConnectTokenAuthorityCaptureService;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.PrivateRealmGrantVersion;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AccountBareLoginCurrentAuthorityReaderTest {
  private static final UUID ACCOUNT_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_UUID = UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID REALM_UUID = UUID.fromString("00000000-0000-4000-8000-000000000003");
  private static final UUID GAME_INSTANCE_UUID =
      UUID.fromString("00000000-0000-4000-8000-000000000004");
  private static final UUID NAMESPACE_UUID =
      UUID.fromString("00000000-0000-4000-8000-000000000005");
  private static final UUID OPERATION_ID = UUID.fromString("00000000-0000-4000-8000-000000000006");
  private static final String REQUEST_ID = "bare-login-request-1";
  private static final String CONNECT_SCOPE_ID = "connect-scope-1";
  private static final String SIGNED_CONTEXT = "signed-gateway-context-secret-marker";
  private static final Instant NOW = Instant.parse("2026-04-06T12:00:00Z");
  private static final byte[] REQUEST_DIGEST = filled(32, (byte) 0x31);
  private static final byte[] TOKEN_HASH = filled(32, (byte) 0x42);

  private AccountCommittedConnectSourceReader committedSourceReader;
  private AccountConnectTokenAuthorityCaptureService captureService;
  private AccountMembershipAuthorityEventProducer membershipProducer;
  private AccountAuthorityGenerationRepository authorityGenerationRepository;
  private AccountBareLoginCurrentAuthorityReader reader;

  @BeforeEach
  void setUp() {
    committedSourceReader = mock(AccountCommittedConnectSourceReader.class);
    captureService = mock(AccountConnectTokenAuthorityCaptureService.class);
    membershipProducer = mock(AccountMembershipAuthorityEventProducer.class);
    authorityGenerationRepository = mock(AccountAuthorityGenerationRepository.class);
    reader =
        new AccountBareLoginCurrentAuthorityReader(
            committedSourceReader,
            captureService,
            membershipProducer,
            authorityGenerationRepository);
  }

  @Test
  void composesExactCurrentEvidenceInOrderAndReturnsOnlyRedactedImmutableFacts() {
    Fixture fixture = fixture();
    installHappyPath(fixture);

    CurrentAuthorityReadback result =
        reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT);

    assertEquals(OPERATION_ID, result.operationId());
    assertEquals(ACCOUNT_UUID, result.accountUuid());
    assertEquals(TENANT_UUID, result.tenantUuid());
    assertEquals(REQUEST_ID, result.requestId());
    assertEquals(6L, result.issuanceFence());
    assertEquals(7L, result.fenceSourceVersion());
    assertEquals(Map.of(TENANT_UUID.toString(), "8"), result.membershipVersion());
    assertEquals("ACTIVE", result.membershipLifecycleState());
    assertEquals(List.of("player"), result.roles());
    assertEquals(fixture.authorityTuple(), result.authorityTuple());
    assertEquals(4, result.checkpoints().size());
    assertEquals(4, result.sourceEvents().size());
    assertArrayEquals(REQUEST_DIGEST, result.requestDigest());
    assertArrayEquals(fixture.capture().digest(), result.captureDigest());
    assertFalse(result.toString().contains(SIGNED_CONTEXT));
    assertFalse(result.toString().contains("connect-jti-1"));
    assertFalse(result.toString().contains("canonicalEventJson"));

    byte[] returnedHash = result.sourceTokenHash();
    returnedHash[0] ^= 0x7f;
    assertArrayEquals(TOKEN_HASH, result.sourceTokenHash());
    byte[] returnedRequestDigest = result.requestDigest();
    returnedRequestDigest[0] ^= 0x7f;
    assertArrayEquals(REQUEST_DIGEST, result.requestDigest());
    byte[] returnedCaptureDigest = result.captureDigest();
    returnedCaptureDigest[0] ^= 0x7f;
    assertArrayEquals(fixture.capture().digest(), result.captureDigest());
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.membershipVersion().put(TENANT_UUID.toString(), "9"));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            result
                .checkpoints()
                .add(new AccountBareLoginCurrentAuthorityReader.CheckpointReadback("x", "1")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> result.authorityTuple().tenantAuthorityGeneration().put(TENANT_UUID.toString(), "9"));

    InOrder ordered =
        inOrder(
            committedSourceReader,
            captureService,
            membershipProducer,
            authorityGenerationRepository);
    ordered.verify(committedSourceReader).read(fixture.identity(), SIGNED_CONTEXT);
    ordered
        .verify(captureService)
        .read(eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest)));
    ordered
        .verify(membershipProducer)
        .readExistingRuntimeMembershipSnapshot(ACCOUNT_UUID, TENANT_UUID);
    ordered.verify(authorityGenerationRepository).read(AuthorityScope.account(ACCOUNT_UUID));
    ordered.verify(committedSourceReader).read(fixture.identity(), SIGNED_CONTEXT);
  }

  @Test
  void strictPeerOrCurrentContextDenialStopsBeforeCaptureAndAuthorityReads() {
    Fixture fixture = fixture();
    when(committedSourceReader.read(fixture.identity(), SIGNED_CONTEXT))
        .thenThrow(new AdminAuthorizationException("exact Game Session peer required"));

    assertThrows(
        AdminAuthorizationException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

    verify(committedSourceReader).read(fixture.identity(), SIGNED_CONTEXT);
    verifyNoInteractions(captureService, membershipProducer, authorityGenerationRepository);
  }

  @Test
  void absentCaptureCannotBeInferredFromCurrentFenceOrReachMembershipRead() {
    Fixture fixture = fixture();
    when(committedSourceReader.read(fixture.identity(), SIGNED_CONTEXT))
        .thenReturn(fixture.source());
    when(captureService.read(
            eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest))))
        .thenReturn(Optional.empty());

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

    verify(captureService)
        .read(eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest)));
    verify(membershipProducer, never())
        .readExistingRuntimeMembershipSnapshot(any(UUID.class), any(UUID.class));
    verifyNoInteractions(authorityGenerationRepository);
  }

  @Test
  void captureMustMatchExactRequestDigestAndOperationBeforeCurrentReads() {
    Fixture fixture = fixture();
    AccountConnectIssuanceFenceEvidence wrongDigestCapture =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            fixture.capture().connectScopeHash(),
            REQUEST_ID,
            filled(32, (byte) 0x33),
            6L,
            7L);
    installHappyPath(fixture);
    when(captureService.read(
            eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest))))
        .thenReturn(Optional.of(wrongDigestCapture));

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

    verify(membershipProducer, never())
        .readExistingRuntimeMembershipSnapshot(any(UUID.class), any(UUID.class));
    verifyNoInteractions(authorityGenerationRepository);
  }

  @Test
  void comparesEveryAuthorityTupleMemberIncludingGrantAndBothOptionalCutoffs() {
    assertDeniedWithTuple(
        new AuthorityTuple(
            "4",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("2", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "3",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("3", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "6"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("2", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "6"),
            List.of(),
            Optional.of(accountCutoff("2", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(
                new PrivateRealmGrantVersion(
                    TENANT_UUID.toString(), "emberfall", "preview", "playtest-lifecycle-1", "7")),
            Optional.of(accountCutoff("2", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("2", "13")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("3", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(
                new AccountSecurityCutoff("2", "account:auth-authority:v1:account/other", "12")),
            Optional.empty()));
    assertDeniedWithTuple(
        new AuthorityTuple(
            "3",
            "2",
            Map.of(TENANT_UUID.toString(), "4"),
            Map.of(TENANT_UUID.toString(), "5"),
            List.of(),
            Optional.of(accountCutoff("2", "12")),
            Optional.of(
                Map.of(
                    TENANT_UUID.toString(),
                    new TenantBillingCutoff(
                        "4", "1", "account:auth-authority:v1:tenant/" + TENANT_UUID, "14")))));
  }

  @Test
  void membershipVersionIsComparedAsItsOwnExactOneTenantMap() {
    Fixture fixture = fixture();
    installHappyPath(fixture);
    when(fixture.snapshot().membershipBaseline())
        .thenReturn(new MembershipBaseline("ACTIVE", Map.of(TENANT_UUID.toString(), "9"), "5"));

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

    verify(authorityGenerationRepository).read(AuthorityScope.account(ACCOUNT_UUID));
    verify(committedSourceReader, org.mockito.Mockito.times(1))
        .read(fixture.identity(), SIGNED_CONTEXT);
  }

  @Test
  void membershipVersionAcceptsCanonicalDecimalStringsBeyondPrimitiveIntegerRange() {
    Fixture base = fixture();
    String largeVersion = "1234567890123456789012345678901234567890";
    Map<String, Object> sourceClaims = new LinkedHashMap<>(base.source().originalSourceClaims());
    sourceClaims.put("membershipVersion", Map.of(TENANT_UUID.toString(), largeVersion));
    Map<String, Object> gatewayClaims =
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            sourceClaims, NOW.getEpochSecond(), "gateway-request-1");
    Fixture fixture =
        new Fixture(
            base.identity(),
            sourceEvidence(sourceClaims, gatewayClaims),
            base.capture(),
            base.snapshot(),
            base.authorityTuple(),
            base.accountAuthority());
    installHappyPath(fixture);
    when(fixture.snapshot().membershipBaseline())
        .thenReturn(
            new MembershipBaseline("ACTIVE", Map.of(TENANT_UUID.toString(), largeVersion), "5"));

    CurrentAuthorityReadback result =
        reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT);

    assertEquals(Map.of(TENANT_UUID.toString(), largeVersion), result.membershipVersion());
    verify(committedSourceReader, org.mockito.Mockito.times(2))
        .read(fixture.identity(), SIGNED_CONTEXT);
  }

  @Test
  void membershipVersionRejectsNonStringNoncanonicalAndWrongTenantSourceValues() {
    List<Object> invalidValues =
        List.of(
            Map.of(TENANT_UUID.toString(), BigInteger.valueOf(8)),
            Map.of(TENANT_UUID.toString(), "08"),
            Map.of(TENANT_UUID.toString(), "0"),
            Map.of("00000000-0000-4000-8000-000000000099", "8"),
            Map.of(TENANT_UUID.toString(), "8", "00000000-0000-4000-8000-000000000099", "9"));

    for (Object invalidValue : invalidValues) {
      org.mockito.Mockito.clearInvocations(
          committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
      Fixture fixture = fixture();
      Map<String, Object> sourceClaims =
          new LinkedHashMap<>(fixture.source().originalSourceClaims());
      sourceClaims.put("membershipVersion", invalidValue);
      // This substitutes source-reader output to exercise defensive validation. It does not claim
      // that Gateway or the production source verifier accepts these malformed source claims.
      OriginalSourceEvidence substitutedSource =
          sourceEvidence(sourceClaims, fixture.source().gatewayContextClaims());
      installHappyPath(fixture);
      doReturn(substitutedSource)
          .when(committedSourceReader)
          .read(fixture.identity(), SIGNED_CONTEXT);

      assertThrows(
          IllegalStateException.class,
          () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

      verify(committedSourceReader, org.mockito.Mockito.times(1))
          .read(fixture.identity(), SIGNED_CONTEXT);
    }
  }

  @Test
  void captureCounterAndFenceSourceVersionMustEqualCurrentDurableAccountFence() {
    Fixture fixture = fixture();
    AccountConnectIssuanceFenceEvidence wrongCounterCapture =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            fixture.capture().connectScopeHash(),
            REQUEST_ID,
            REQUEST_DIGEST,
            7L,
            7L);
    installHappyPath(fixture);
    when(captureService.read(
            eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest))))
        .thenReturn(Optional.of(wrongCounterCapture));

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(1))
        .read(fixture.identity(), SIGNED_CONTEXT);

    org.mockito.Mockito.clearInvocations(
        committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
    Fixture wrongSourceVersion = fixture();
    AccountConnectIssuanceFenceEvidence wrongSourceVersionCapture =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            wrongSourceVersion.capture().connectScopeHash(),
            REQUEST_ID,
            REQUEST_DIGEST,
            6L,
            8L);
    installHappyPath(wrongSourceVersion);
    when(captureService.read(
            eq(wrongSourceVersion.identity()),
            argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest))))
        .thenReturn(Optional.of(wrongSourceVersionCapture));

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(wrongSourceVersion.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(1))
        .read(wrongSourceVersion.identity(), SIGNED_CONTEXT);
  }

  @Test
  void sourceIdentityMismatchFailsBeforeCaptureAndNoMembershipEnrollmentIsAttempted() {
    Fixture fixture = fixture();
    Map<String, Object> changedClaims =
        new LinkedHashMap<>(fixture.source().originalSourceClaims());
    changedClaims.put("tenantId", "00000000-0000-4000-8000-000000000099");
    OriginalSourceEvidence wrongTenantSource =
        sourceEvidence(changedClaims, fixture.source().gatewayContextClaims());
    when(committedSourceReader.read(fixture.identity(), SIGNED_CONTEXT))
        .thenReturn(wrongTenantSource);

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));

    verifyNoInteractions(captureService, membershipProducer, authorityGenerationRepository);
  }

  @Test
  void finalStrictSourceReadRejectsExpiryOrChangedSourceProvenance() {
    Fixture fixture = fixture();
    installHappyPath(fixture);
    when(committedSourceReader.read(fixture.identity(), SIGNED_CONTEXT))
        .thenReturn(fixture.source())
        .thenThrow(new IllegalArgumentException("current source expired"));

    assertThrows(
        IllegalArgumentException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(2))
        .read(fixture.identity(), SIGNED_CONTEXT);

    org.mockito.Mockito.clearInvocations(
        committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
    Fixture changedFinalSource = fixture();
    installHappyPath(changedFinalSource);
    OriginalSourceEvidence changedKeySource =
        new OriginalSourceEvidence(
            OPERATION_ID,
            "different-envelope-key",
            TOKEN_HASH,
            "gateway-key-1",
            changedFinalSource.source().originalSourceClaims(),
            changedFinalSource.source().gatewayContextClaims());
    when(committedSourceReader.read(changedFinalSource.identity(), SIGNED_CONTEXT))
        .thenReturn(changedFinalSource.source(), changedKeySource);

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(changedFinalSource.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(2))
        .read(changedFinalSource.identity(), SIGNED_CONTEXT);

    org.mockito.Mockito.clearInvocations(
        committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
    Fixture changedHashFixture = fixture();
    installHappyPath(changedHashFixture);
    OriginalSourceEvidence changedHashSource =
        new OriginalSourceEvidence(
            OPERATION_ID,
            "envelope-key-1",
            filled(32, (byte) 0x43),
            "gateway-key-1",
            changedHashFixture.source().originalSourceClaims(),
            changedHashFixture.source().gatewayContextClaims());
    when(committedSourceReader.read(changedHashFixture.identity(), SIGNED_CONTEXT))
        .thenReturn(changedHashFixture.source(), changedHashSource);

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(changedHashFixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(2))
        .read(changedHashFixture.identity(), SIGNED_CONTEXT);

    org.mockito.Mockito.clearInvocations(
        committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
    Fixture changedClaimsFixture = fixture();
    installHappyPath(changedClaimsFixture);
    Map<String, Object> changedClaims =
        new LinkedHashMap<>(changedClaimsFixture.source().originalSourceClaims());
    changedClaims.put("pointerVersion", BigInteger.valueOf(24));
    Map<String, Object> changedGatewayClaims =
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            changedClaims, NOW.getEpochSecond(), "gateway-request-1");
    OriginalSourceEvidence changedClaimsSource =
        sourceEvidence(changedClaims, changedGatewayClaims);
    when(committedSourceReader.read(changedClaimsFixture.identity(), SIGNED_CONTEXT))
        .thenReturn(changedClaimsFixture.source(), changedClaimsSource);

    assertThrows(
        IllegalStateException.class,
        () -> reader.read(changedClaimsFixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(2))
        .read(changedClaimsFixture.identity(), SIGNED_CONTEXT);
  }

  private void assertDeniedWithTuple(AuthorityTuple changedTuple) {
    org.mockito.Mockito.clearInvocations(
        committedSourceReader, captureService, membershipProducer, authorityGenerationRepository);
    Fixture fixture = fixture();
    installHappyPath(fixture);
    when(fixture.snapshot().authorityTuple()).thenReturn(changedTuple);
    assertThrows(
        IllegalStateException.class,
        () -> reader.read(fixture.identity(), REQUEST_DIGEST, SIGNED_CONTEXT));
    verify(committedSourceReader, org.mockito.Mockito.times(1))
        .read(fixture.identity(), SIGNED_CONTEXT);
  }

  private void installHappyPath(Fixture fixture) {
    doReturn(fixture.source()).when(committedSourceReader).read(fixture.identity(), SIGNED_CONTEXT);
    when(captureService.read(
            eq(fixture.identity()), argThat(digest -> Arrays.equals(REQUEST_DIGEST, digest))))
        .thenReturn(Optional.of(fixture.capture()));
    when(membershipProducer.readExistingRuntimeMembershipSnapshot(ACCOUNT_UUID, TENANT_UUID))
        .thenReturn(fixture.snapshot());
    when(authorityGenerationRepository.read(AuthorityScope.account(ACCOUNT_UUID)))
        .thenReturn(fixture.accountAuthority());
  }

  private Fixture fixture() {
    AccountConnectTokenIssuanceIdentity identity =
        new AccountConnectTokenIssuanceIdentity(101L, TENANT_UUID, CONNECT_SCOPE_ID, REQUEST_ID);
    AuthorityTuple authorityTuple = currentAuthorityTuple();
    Map<String, Object> sourceClaims = sourceClaims();
    Map<String, Object> gatewayClaims =
        GatewayConnectContextCodec.projectVerifiedAccountGameplayConnectClaims(
            sourceClaims, NOW.getEpochSecond(), "gateway-request-1");
    OriginalSourceEvidence source = sourceEvidence(sourceClaims, gatewayClaims);
    AccountConnectIssuanceFenceEvidence capture =
        AccountConnectIssuanceFenceEvidence.capture(
            OPERATION_ID,
            ACCOUNT_UUID,
            TENANT_UUID,
            net.firedevops.firemud.accountservice.dto.AccountJoinDigest.tokenHash(CONNECT_SCOPE_ID),
            REQUEST_ID,
            REQUEST_DIGEST,
            6L,
            7L);

    List<OutboxCheckpointEntry> checkpoints =
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_UUID, "3"),
            new OutboxCheckpointEntry("account:auth-authority:v1:issuer/account-service", "2"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID, "5"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_UUID, "4"));
    List<OutboxSourceEvidence> sourceEvents =
        checkpoints.stream()
            .map(
                checkpoint ->
                    new OutboxSourceEvidence(
                        checkpoint.outboxStreamKey(),
                        checkpoint.outboxSequence(),
                        "event-" + checkpoint.outboxSequence(),
                        "sha256:" + "a".repeat(64),
                        "{}"))
            .toList();
    RuntimeMembershipSnapshotDto snapshot = mock(RuntimeMembershipSnapshotDto.class);
    when(snapshot.requestAccountUuid()).thenReturn(ACCOUNT_UUID.toString());
    when(snapshot.requestTenantUuid()).thenReturn(TENANT_UUID.toString());
    when(snapshot.accountUuid()).thenReturn(ACCOUNT_UUID.toString());
    when(snapshot.tenantUuid()).thenReturn(TENANT_UUID.toString());
    when(snapshot.membershipExists()).thenReturn(true);
    when(snapshot.gameplayAdmissionAllowed()).thenReturn(true);
    when(snapshot.membershipBaseline())
        .thenReturn(new MembershipBaseline("ACTIVE", Map.of(TENANT_UUID.toString(), "8"), "5"));
    when(snapshot.roles()).thenReturn(List.of("player"));
    when(snapshot.authorityTuple()).thenReturn(authorityTuple);
    when(snapshot.issuanceFence()).thenReturn("6");
    when(snapshot.evaluatedAt()).thenReturn(NOW);
    when(snapshot.outboxCheckpoints()).thenReturn(checkpoints);
    when(snapshot.outboxSourceEvidence()).thenReturn(sourceEvents);

    ScopeState accountAuthority =
        new ScopeState(
            AuthorityScope.account(ACCOUNT_UUID), 2L, 9L, new IssuanceFence(ACCOUNT_UUID, 6L, 7L));
    return new Fixture(identity, source, capture, snapshot, authorityTuple, accountAuthority);
  }

  private static Map<String, Object> sourceClaims() {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "https://account.example.test");
    claims.put("aud", "gameplay-connect");
    claims.put("iat", BigInteger.valueOf(NOW.getEpochSecond() - 1));
    claims.put("exp", BigInteger.valueOf(NOW.getEpochSecond() + 29));
    claims.put("jti", "connect-jti-1");
    claims.put("accountId", ACCOUNT_UUID.toString());
    claims.put("tenantId", TENANT_UUID.toString());
    claims.put("realmId", REALM_UUID.toString());
    claims.put("worldSlug", "emberfall");
    claims.put("realmSlug", "public-production");
    claims.put("playableStateNamespaceId", NAMESPACE_UUID.toString());
    claims.put("playableStateScope", "PLAYABLE_STATE_SCOPE_SHARED");
    claims.put("gameInstanceId", GAME_INSTANCE_UUID.toString());
    claims.put("pointerVersion", BigInteger.valueOf(23));
    claims.put("catalogRevision", BigInteger.valueOf(41));
    claims.put("connectScopeId", CONNECT_SCOPE_ID);
    claims.put("requestId", REQUEST_ID);
    claims.put("authorityTuple", sourceAuthorityTuple());
    claims.put("membershipVersion", Map.of(TENANT_UUID.toString(), "8"));
    // This Gateway replay fence intentionally differs from the Account issuance fence below.
    claims.put("replayAdmissionFence", BigInteger.valueOf(29));
    return claims;
  }

  private static Map<String, Object> sourceAuthorityTuple() {
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", BigInteger.valueOf(3));
    tuple.put("accountAuthorityGeneration", BigInteger.valueOf(2));
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT_UUID.toString(), BigInteger.valueOf(4)));
    tuple.put(
        "membershipAuthorityGeneration", Map.of(TENANT_UUID.toString(), BigInteger.valueOf(5)));
    tuple.put("privateRealmGrantVersions", List.of());
    tuple.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration",
            BigInteger.valueOf(2),
            "outboxStreamKey",
            "account:auth-authority:v1:account/" + ACCOUNT_UUID,
            "outboxSequence",
            BigInteger.valueOf(12)));
    return tuple;
  }

  private static AuthorityTuple currentAuthorityTuple() {
    return new AuthorityTuple(
        "3",
        "2",
        Map.of(TENANT_UUID.toString(), "4"),
        Map.of(TENANT_UUID.toString(), "5"),
        List.of(),
        Optional.of(accountCutoff("2", "12")),
        Optional.empty());
  }

  private static AccountSecurityCutoff accountCutoff(String generation, String sequence) {
    return accountCutoff(generation, sequence, "account:auth-authority:v1:account/" + ACCOUNT_UUID);
  }

  private static AccountSecurityCutoff accountCutoff(
      String generation, String sequence, String outboxStreamKey) {
    return new AccountSecurityCutoff(generation, outboxStreamKey, sequence);
  }

  private static OriginalSourceEvidence sourceEvidence(
      Map<String, Object> sourceClaims, Map<String, Object> gatewayClaims) {
    return new OriginalSourceEvidence(
        OPERATION_ID, "envelope-key-1", TOKEN_HASH, "gateway-key-1", sourceClaims, gatewayClaims);
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  private record Fixture(
      AccountConnectTokenIssuanceIdentity identity,
      OriginalSourceEvidence source,
      AccountConnectIssuanceFenceEvidence capture,
      RuntimeMembershipSnapshotDto snapshot,
      AuthorityTuple authorityTuple,
      ScopeState accountAuthority) {}
}
