package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class AccountJwtReadinessValidationServiceTest {
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final String HARNESS_URI =
      "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
  private static final String MATERIALIZER_URI =
      "spiffe://firemud/ns/firemud-prod/sa/jwt-signer-materializer";
  private static final String SPKI_TEXT = "mock authenticated readiness leaf key";
  private static final String KID = "pending-key-7";
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<String, String> REPRESENTATIVE_PROFILES =
      Map.of(
          ControlUiJwtProfileValidator.PROFILE,
          ControlUiJwtProfileValidator.AUDIENCE,
          PlayerBootstrapJwtProfileValidator.PROFILE,
          PlayerBootstrapJwtProfileValidator.AUDIENCE,
          GameSessionAccountDelegationProfile.PROFILE,
          GameSessionAccountDelegationProfile.AUDIENCE);

  @Test
  void representativeUsesActualRs256VerifierAndRecordsOnlyTheExactNonAuthorizingReceipt()
      throws Exception {
    Fixture fixture = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE);
    VerificationReceipt result = fixture.service.validate(fixture.request, fixture.authenticated);

    assertThat(result).isEqualTo(fixture.receipt);
    assertThat(result.receiptSha256()).matches("[0-9a-f]{64}");
    verify(fixture.repository)
        .recordVerified(
            eq(fixture.signerBinding.accountBinding()),
            eq(fixture.trustFence),
            eq(fixture.operationId),
            eq("account-service"),
            eq(GameSessionAccountDelegationProfile.PROFILE),
            eq(GameSessionAccountDelegationProfile.AUDIENCE),
            eq(AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE),
            eq(fixture.jti),
            any(AccountJwtReadinessProbeRepository.VerifiedProbeObservation.class));
  }

  @Test
  void eachAccountProfileUsesItsExactAudienceAndCanonicalShapeValidator() throws Exception {
    for (Map.Entry<String, String> profile : REPRESENTATIVE_PROFILES.entrySet()) {
      Fixture fixture =
          new Fixture(
              AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
              profile.getKey(),
              profile.getValue());

      VerificationReceipt result = fixture.service.validate(fixture.request, fixture.authenticated);

      assertThat(result).isEqualTo(fixture.receipt);
      verify(fixture.repository)
          .recordVerified(
              eq(fixture.signerBinding.accountBinding()),
              eq(fixture.trustFence),
              eq(fixture.operationId),
              eq("account-service"),
              eq(profile.getKey()),
              eq(profile.getValue()),
              eq(AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE),
              eq(fixture.jti),
              any(AccountJwtReadinessProbeRepository.VerifiedProbeObservation.class));
    }
  }

  @Test
  void oppositeCounterWireTypesAreRejectedForEveryProfileWithoutRecording() throws Exception {
    for (Map.Entry<String, String> profile : REPRESENTATIVE_PROFILES.entrySet()) {
      boolean decimalCounters =
          GameSessionAccountDelegationProfile.PROFILE.equals(profile.getKey());
      Object oppositeCounter = decimalCounters ? 1L : "1";
      Fixture fixture =
          new Fixture(
              AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
              profile.getKey(),
              profile.getValue(),
              Map.of(
                  "tokenGeneration",
                  oppositeCounter,
                  "issuanceFence",
                  oppositeCounter,
                  "authorityTuple",
                  authorityTuple(oppositeCounter, oppositeCounter)),
              false,
              8);

      assertRejectedWithoutRecording(fixture);
    }
  }

  @Test
  void malformedProfileCountersAreRejectedBeforeRecording() throws Exception {
    for (Map.Entry<String, String> profile : REPRESENTATIVE_PROFILES.entrySet()) {
      Object malformedCounter =
          GameSessionAccountDelegationProfile.PROFILE.equals(profile.getKey()) ? "01" : 1.5d;
      Fixture fixture =
          new Fixture(
              AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
              profile.getKey(),
              profile.getValue(),
              Map.of("tokenGeneration", malformedCounter),
              false,
              8);

      assertRejectedWithoutRecording(fixture);
    }
  }

  @Test
  void onlyExactInitialCountersAreAcceptedAfterSignedProfileVerification() throws Exception {
    for (Map.Entry<String, String> profile : REPRESENTATIVE_PROFILES.entrySet()) {
      boolean decimalCounters =
          GameSessionAccountDelegationProfile.PROFILE.equals(profile.getKey());
      Object nonInitialCounter = decimalCounters ? "2" : 2L;
      for (Map<String, Object> nonInitialClaims :
          List.<Map<String, Object>>of(
              Map.of("tokenGeneration", nonInitialCounter),
              Map.of("issuanceFence", nonInitialCounter),
              Map.of(
                  "authorityTuple",
                  authorityTuple(nonInitialCounter, decimalCounters ? "1" : 1L)))) {
        Fixture fixture =
            new Fixture(
                AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
                profile.getKey(),
                profile.getValue(),
                nonInitialClaims,
                false,
                8);

        assertRejectedWithoutRecording(fixture);
      }
    }
  }

  @Test
  void largeCanonicalDelegationCounterIsComparedWithoutNumericRounding() throws Exception {
    String largeCounter = "9007199254740993";
    Fixture fixture =
        new Fixture(
            AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
            GameSessionAccountDelegationProfile.PROFILE,
            GameSessionAccountDelegationProfile.AUDIENCE,
            Map.of(
                "issuanceFence", largeCounter, "authorityTuple", authorityTuple(largeCounter, "1")),
            false,
            8);

    assertRejectedWithoutRecording(fixture);
  }

  @Test
  void inapplicableAudienceIsRejectedBeforeReadingOwnerProbeState() throws Exception {
    Fixture fixture =
        new Fixture(
            AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
            ControlUiJwtProfileValidator.PROFILE,
            ControlUiJwtProfileValidator.AUDIENCE);
    ValidateReadinessProbeRequest wrongAudience =
        fixture.request.toBuilder()
            .setAudience(PlayerBootstrapJwtProfileValidator.AUDIENCE)
            .build();

    assertThatThrownBy(() -> fixture.service.validate(wrongAudience, fixture.authenticated))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    verify(fixture.repository, never())
        .readCurrentEntry(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void enabledValidationRejectsMissingOrTransportIncompatibleControlUiScopeCap() {
    assertThatThrownBy(
            () ->
                new Fixture(
                    AccountMountedJwtSignerBundle.ProbeKind.CANARY,
                    "account-jwt-readiness-canary",
                    "firemud-account-jwt-readiness",
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope bound");
    assertThatThrownBy(
            () ->
                new Fixture(
                    AccountMountedJwtSignerBundle.ProbeKind.CANARY,
                    "account-jwt-readiness-canary",
                    "firemud-account-jwt-readiness",
                    GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES + 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope bound");
  }

  @Test
  void validationBeanIsOffByDefaultAndEnabledConstructionRequiresFiniteTransportSafeCap() {
    validationContextRunner()
        .run(
            context ->
                assertThat(context).doesNotHaveBean(AccountJwtReadinessValidationService.class));

    validationContextRunner()
        .withPropertyValues("firemud.account.jwt-readiness.validation.enabled=true")
        .run(context -> assertThat(context).hasFailed());

    for (String invalidCap : List.of("NaN", "Infinity", "-Infinity", "0", "4097")) {
      validationContextRunner()
          .withPropertyValues(
              "firemud.account.jwt-readiness.validation.enabled=true",
              "firemud.account.jwt-readiness.validation.max-control-ui-tenant-scopes=" + invalidCap)
          .run(context -> assertThat(context).hasFailed());
    }

    for (int boundaryCap :
        List.of(1, GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES)) {
      validationContextRunner()
          .withPropertyValues(
              "firemud.account.jwt-readiness.validation.enabled=true",
              "firemud.account.jwt-readiness.validation.max-control-ui-tenant-scopes="
                  + boundaryCap)
          .run(
              context ->
                  assertThat(context)
                      .hasNotFailed()
                      .hasSingleBean(AccountJwtReadinessValidationService.class));
    }
  }

  @Test
  void dedicatedCanaryMustHaveReservedSubjectAndNoApplicationAuthorityClaims() throws Exception {
    Fixture fixture = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.CANARY);

    VerificationReceipt result = fixture.service.validate(fixture.request, fixture.authenticated);

    assertThat(result).isEqualTo(fixture.receipt);
    verify(fixture.repository)
        .recordVerified(
            any(),
            any(),
            eq(fixture.operationId),
            eq("account-service"),
            eq("account-jwt-readiness-canary"),
            eq("firemud-account-jwt-readiness"),
            eq(AccountMountedJwtSignerBundle.ProbeKind.CANARY),
            eq(fixture.jti),
            any(AccountJwtReadinessProbeRepository.VerifiedProbeObservation.class));
  }

  @Test
  void signatureFailureAndUnexpectedAuthorityClaimNeverRecordVerification() throws Exception {
    Fixture tampered = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.CANARY, Map.of(), true);
    assertThatThrownBy(() -> tampered.service.validate(tampered.request, tampered.authenticated))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    verify(tampered.repository, never())
        .recordVerified(any(), any(), any(), any(), any(), any(), any(), any(), any());

    Fixture withRoles =
        new Fixture(
            AccountMountedJwtSignerBundle.ProbeKind.CANARY, Map.of("roles", List.of("ADMIN")));
    assertThatThrownBy(() -> withRoles.service.validate(withRoles.request, withRoles.authenticated))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    verify(withRoles.repository, never())
        .recordVerified(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void noTlsInterceptorContextCannotBeSuppliedByRequestOrDirectServiceCall() throws Exception {
    Fixture fixture = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.CANARY);

    assertThatThrownBy(() -> fixture.service.validate(fixture.request, null))
        .isInstanceOf(AccountJwtReadinessValidationService.ReadinessCallerRejectedException.class);
    verify(fixture.repository, never())
        .readCurrentEntry(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void changedProtectedHarnessBindingBetweenReadAndReceiptRefusesVerification() throws Exception {
    Fixture fixture = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.CANARY);
    Binding rotatedBinding = readinessBinding(sha256(fixture.spki), "readiness-r2");
    when(fixture.readinessProvider.current())
        .thenReturn(Optional.of(fixture.readinessBinding), Optional.of(rotatedBinding));

    assertThatThrownBy(() -> fixture.service.validate(fixture.request, fixture.authenticated))
        .isInstanceOf(AccountJwtReadinessValidationService.ReadinessCallerRejectedException.class);
    verify(fixture.repository, never())
        .recordVerified(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void unknownRequestFieldsAndOversizedTokensAreRejectedBeforeOwnerStateReads() throws Exception {
    Fixture fixture = new Fixture(AccountMountedJwtSignerBundle.ProbeKind.CANARY);
    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    ValidateReadinessProbeRequest unknownFieldRequest =
        fixture.request.toBuilder().setUnknownFields(unknown).build();

    assertThatThrownBy(() -> fixture.service.validate(unknownFieldRequest, fixture.authenticated))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    ValidateReadinessProbeRequest oversized =
        fixture.request.toBuilder().setCompactJwt("x".repeat(16 * 1024 + 1)).build();
    assertThatThrownBy(() -> fixture.service.validate(oversized, fixture.authenticated))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    verify(fixture.repository, never())
        .readCurrentEntry(any(), any(), any(), any(), any(), any(), any(), any());
  }

  private static final class Fixture {
    private final AccountJwtReadinessProbeRepository repository =
        mock(AccountJwtReadinessProbeRepository.class);
    private final AccountJwtReadinessTrustBinding readinessProvider;
    private final UUID operationId = UUID.randomUUID();
    private final UUID jti = UUID.randomUUID();
    private final long issuedAt = NOW.getEpochSecond() - 1L;
    private final long expiresAt = NOW.getEpochSecond() + 299L;
    private final KeyPair keyPair = rsa3072();
    private final byte[] spki = SPKI_TEXT.getBytes(StandardCharsets.US_ASCII);
    private final Binding readinessBinding = readinessBinding(sha256(spki));
    private final AccountJwtSignerMaterializerTrustBinding.Binding signerBinding = signerBinding();
    private final TrustFence trustFence =
        new TrustFence(
            signerBinding.expectedClusterIncarnationUid(),
            signerBinding.expectedNamespaceUid(),
            signerBinding.bindingDigest(),
            signerBinding.configRevision());
    private final String compactJwt;
    private final AccountJwtReadinessValidationService service;
    private final AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticated;
    private final ValidateReadinessProbeRequest request;
    private final VerificationReceipt receipt;
    private final ProbeEntry issued;
    private final ProbeEntry verified;

    private Fixture(AccountMountedJwtSignerBundle.ProbeKind kind) throws Exception {
      this(
          kind,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "account-jwt-readiness-canary"
              : GameSessionAccountDelegationProfile.PROFILE,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "firemud-account-jwt-readiness"
              : GameSessionAccountDelegationProfile.AUDIENCE,
          Map.of(),
          false);
    }

    private Fixture(AccountMountedJwtSignerBundle.ProbeKind kind, Map<String, Object> extras)
        throws Exception {
      this(
          kind,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "account-jwt-readiness-canary"
              : GameSessionAccountDelegationProfile.PROFILE,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "firemud-account-jwt-readiness"
              : GameSessionAccountDelegationProfile.AUDIENCE,
          extras,
          false);
    }

    private Fixture(
        AccountMountedJwtSignerBundle.ProbeKind kind,
        Map<String, Object> extras,
        boolean invalidSignature)
        throws Exception {
      this(
          kind,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "account-jwt-readiness-canary"
              : GameSessionAccountDelegationProfile.PROFILE,
          kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY
              ? "firemud-account-jwt-readiness"
              : GameSessionAccountDelegationProfile.AUDIENCE,
          extras,
          invalidSignature);
    }

    private Fixture(AccountMountedJwtSignerBundle.ProbeKind kind, String profile, String audience)
        throws Exception {
      this(kind, profile, audience, Map.of(), false, 8);
    }

    private Fixture(
        AccountMountedJwtSignerBundle.ProbeKind kind,
        String profile,
        String audience,
        int maxControlUiTenantScopes)
        throws Exception {
      this(kind, profile, audience, Map.of(), false, maxControlUiTenantScopes);
    }

    private Fixture(
        AccountMountedJwtSignerBundle.ProbeKind kind,
        String profile,
        String audience,
        Map<String, Object> extras,
        boolean invalidSignature)
        throws Exception {
      this(kind, profile, audience, extras, invalidSignature, 8);
    }

    private Fixture(
        AccountMountedJwtSignerBundle.ProbeKind kind,
        String profile,
        String audience,
        Map<String, Object> extras,
        boolean invalidSignature,
        int maxControlUiTenantScopes)
        throws Exception {
      String signedToken = createToken(kind, profile, audience, extras, jti, keyPair);
      this.compactJwt = invalidSignature ? corruptSignature(signedToken) : signedToken;
      this.issued =
          entry(kind, profile, audience, sha256(compactJwt.getBytes(StandardCharsets.US_ASCII)));
      this.receipt =
          new VerificationReceipt(
              1,
              4,
              5,
              NOW.getEpochSecond(),
              "e".repeat(64),
              KID,
              readinessBinding.validatorInstanceId(),
              readinessBinding.bindingDigest(),
              readinessBinding.configRevision(),
              HARNESS_URI,
              sha256(spki));
      this.verified =
          new ProbeEntry(
              operationId,
              "b".repeat(64),
              "account-service",
              profile,
              audience,
              kind,
              jti,
              "7",
              KID,
              Optional.empty(),
              1,
              5,
              issuedAt,
              expiresAt,
              ProbeState.VERIFIED,
              Optional.empty(),
              Optional.of(issuedAt),
              Optional.of(sha256(compactJwt.getBytes(StandardCharsets.US_ASCII))),
              Optional.of(receipt));
      readinessProvider = mock(AccountJwtReadinessTrustBinding.class);
      AccountJwtSignerMaterializerTrustBinding signerProvider =
          mock(AccountJwtSignerMaterializerTrustBinding.class);
      when(readinessProvider.current()).thenReturn(Optional.of(readinessBinding));
      when(signerProvider.current()).thenReturn(Optional.of(signerBinding));
      when(repository.readCurrentEntry(any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(issued);
      when(repository.recordVerified(any(), any(), any(), any(), any(), any(), any(), any(), any()))
          .thenReturn(verified);
      AccountPublicJwksCache.SourceIdentity sourceIdentity = sourceIdentity();
      String jwks = jwks(KID, (RSAPublicKey) keyPair.getPublic());
      AccountPublicJwksCache cache =
          new AccountPublicJwksCache(
              () ->
                  new AccountPublicJwksCache.PublicJwksSnapshot(
                      sourceIdentity, jwks.getBytes(StandardCharsets.UTF_8)),
              sourceIdentity,
              Clock.fixed(NOW, ZoneId.of("UTC")),
              Duration.ofMinutes(5));
      AccountAsymmetricJwtVerifier verifier =
          new AccountAsymmetricJwtVerifier(cache, Clock.fixed(NOW, ZoneId.of("UTC")));
      service =
          new AccountJwtReadinessValidationService(
              repository,
              readinessProvider,
              signerProvider,
              verifier,
              new TransactionTemplate(new NoopTransactionManager()),
              Clock.fixed(NOW, ZoneId.of("UTC")),
              maxControlUiTenantScopes);
      authenticated = authenticatedCaller(readinessBinding, spki);
      request =
          ValidateReadinessProbeRequest.newBuilder()
              .setSchemaVersion(1)
              .setRotationOperationId(operationId.toString())
              .setValidatorId("account-service")
              .setTokenProfile(profile)
              .setAudience(audience)
              .setProbeKind(kind.name())
              .setJti(jti.toString())
              .setCompactJwt(compactJwt)
              .build();
    }

    private ProbeEntry entry(
        AccountMountedJwtSignerBundle.ProbeKind kind,
        String profile,
        String audience,
        String tokenHash) {
      return new ProbeEntry(
          operationId,
          "b".repeat(64),
          "account-service",
          profile,
          audience,
          kind,
          jti,
          "7",
          KID,
          Optional.empty(),
          1,
          4,
          issuedAt,
          expiresAt,
          ProbeState.ISSUED,
          Optional.empty(),
          Optional.of(issuedAt),
          Optional.of(tokenHash),
          Optional.empty());
    }
  }

  private static ApplicationContextRunner validationContextRunner() {
    AccountJwtJwksTrustedSource trustedSource = mock(AccountJwtJwksTrustedSource.class);
    when(trustedSource.sourceIdentity())
        .thenReturn(
            new AccountPublicJwksCache.SourceIdentity(
                "prod",
                "prod-cluster-1",
                "11111111-1111-4111-8111-111111111111",
                "firemud-prod",
                "22222222-2222-4222-8222-222222222222",
                "33333333-3333-4333-8333-333333333333",
                "api-r1",
                "https://kubernetes.example:6443",
                "a".repeat(64)));
    return new ApplicationContextRunner()
        .withUserConfiguration(ReadinessValidationContext.class)
        .withBean(
            AccountJwtReadinessProbeRepository.class,
            () -> mock(AccountJwtReadinessProbeRepository.class))
        .withBean(
            AccountJwtReadinessTrustBinding.class,
            () -> new AccountJwtReadinessTrustBinding(false, ""))
        .withBean(
            AccountJwtSignerMaterializerTrustBinding.class,
            () -> new AccountJwtSignerMaterializerTrustBinding(false, ""))
        .withBean(AccountJwtJwksTrustedSource.class, () -> trustedSource)
        .withBean(PlatformTransactionManager.class, NoopTransactionManager::new);
  }

  @Configuration(proxyBeanMethods = false)
  @Import(AccountJwtReadinessValidationService.class)
  static class ReadinessValidationContext {}

  private static Binding readinessBinding(String pin) {
    return readinessBinding(pin, "readiness-r1");
  }

  private static Binding readinessBinding(String pin, String revision) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    List<String> pins = List.of(pin);
    String digest =
        AccountJwtReadinessTrustBinding.computeBindingDigest(
            revision,
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            clusterUid,
            namespaceUid,
            HARNESS_URI,
            pins,
            "account-validator-instance-7",
            NOW.getEpochSecond() + 600L);
    return new Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        clusterUid,
        namespaceUid,
        HARNESS_URI,
        pins,
        revision,
        "account-service",
        "account-validator-instance-7",
        NOW.getEpochSecond() + 600L,
        digest);
  }

  private static AccountJwtSignerMaterializerTrustBinding.Binding signerBinding() {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            "trust-r1",
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            clusterUid,
            namespaceUid,
            MATERIALIZER_URI,
            pins);
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        "prod",
        "prod-cluster-1",
        "firemud-prod",
        clusterUid,
        namespaceUid,
        MATERIALIZER_URI,
        pins,
        "trust-r1",
        digest);
  }

  private static AccountPublicJwksCache.SourceIdentity sourceIdentity() {
    return new AccountPublicJwksCache.SourceIdentity(
        "prod",
        "prod-cluster-1",
        "11111111-1111-4111-8111-111111111111",
        "firemud-prod",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
        "account-api-r1",
        "https://kubernetes.example:6443",
        "9".repeat(64));
  }

  private static AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller(
      Binding binding, byte[] spki) throws Exception {
    AccountJwtReadinessTrustBinding trustBinding = mock(AccountJwtReadinessTrustBinding.class);
    when(trustBinding.current()).thenReturn(Optional.of(binding));
    AccountJwtReadinessTlsInterceptor interceptor =
        new AccountJwtReadinessTlsInterceptor(trustBinding);
    @SuppressWarnings("unchecked")
    ServerCall<String, String> call = mock(ServerCall.class);
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
    SSLSession session = mock(SSLSession.class);
    java.security.cert.X509Certificate certificate = mock(java.security.cert.X509Certificate.class);
    java.security.PublicKey publicKey = mock(java.security.PublicKey.class);
    when(certificate.getPublicKey()).thenReturn(publicKey);
    when(publicKey.getEncoded()).thenReturn(spki);
    when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, HARNESS_URI)));
    when(session.getPeerCertificates())
        .thenReturn(new java.security.cert.Certificate[] {certificate});
    when(call.getAttributes())
        .thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session).build());
    AtomicReference<AccountJwtReadinessTlsInterceptor.AuthenticatedCaller> result =
        new AtomicReference<>();
    when(next.startCall(any(), any()))
        .thenAnswer(
            invocation -> {
              result.set(AccountJwtReadinessTlsInterceptor.authenticatedCaller());
              return new ServerCall.Listener<>() {};
            });
    interceptor.interceptCall(call, new Metadata(), next);
    return result.get();
  }

  private static Map<String, Object> authorityTuple(
      Object issuerAuthGeneration, Object accountAuthorityGeneration) {
    return Map.of(
        "issuerAuthGeneration",
        issuerAuthGeneration,
        "accountAuthorityGeneration",
        accountAuthorityGeneration,
        "tenantAuthorityGeneration",
        Map.of(),
        "membershipAuthorityGeneration",
        Map.of(),
        "privateRealmGrantVersions",
        List.of());
  }

  private static void assertRejectedWithoutRecording(Fixture fixture) {
    assertThatThrownBy(() -> fixture.service.validate(fixture.request, fixture.authenticated))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);
    verify(fixture.repository, never())
        .recordVerified(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  private static String createToken(
      AccountMountedJwtSignerBundle.ProbeKind kind,
      String profile,
      String audience,
      Map<String, Object> extraClaims,
      UUID jti,
      KeyPair keyPair)
      throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", KID, "typ", "JWT");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", "firemud-account-service");
    claims.put("sub", RESERVED_SUBJECT);
    claims.put("jti", jti.toString());
    claims.put("aud", audience);
    claims.put("iat", NOW.getEpochSecond() - 1L);
    claims.put("nbf", NOW.getEpochSecond() - 1L);
    claims.put("exp", NOW.getEpochSecond() + 299L);
    if (kind == AccountMountedJwtSignerBundle.ProbeKind.CANARY) {
      claims.put("tokenProfile", profile);
      claims.put("tokenType", "account_jwt_readiness_canary");
    } else {
      if (ControlUiJwtProfileValidator.PROFILE.equals(profile)
          || PlayerBootstrapJwtProfileValidator.PROFILE.equals(profile)) {
        claims.put("scopedRoles", Map.of());
      }
      claims.put("accountId", RESERVED_SUBJECT);
      boolean decimalCounters = GameSessionAccountDelegationProfile.PROFILE.equals(profile);
      Object initialCounter = decimalCounters ? "1" : 1L;
      claims.put("tokenGeneration", initialCounter);
      claims.put(
          "authorityTuple",
          Map.of(
              "issuerAuthGeneration", initialCounter,
              "accountAuthorityGeneration", initialCounter,
              "tenantAuthorityGeneration", Map.of(),
              "membershipAuthorityGeneration", Map.of(),
              "privateRealmGrantVersions", List.of()));
      claims.put("membershipVersion", Map.of());
      claims.put("issuanceFence", initialCounter);
    }
    claims.putAll(extraClaims);
    String signingInput =
        encode(JSON.writeValueAsBytes(header))
            + "."
            + encode(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(claims)));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + encode(signature.sign());
  }

  private static String corruptSignature(String compactJwt) {
    String[] parts = compactJwt.split("\\.", -1);
    byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] ^= 0x01;
    return parts[0] + "." + parts[1] + "." + encode(signature);
  }

  private static String jwks(String kid, RSAPublicKey key) {
    String n = encode(unsigned(key.getModulus()));
    String e = encode(unsigned(key.getPublicExponent()));
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\""
        + kid
        + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
        + n
        + "\",\"e\":\""
        + e
        + "\"}]}";
  }

  private static KeyPair rsa3072() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(new RSAKeyGenParameterSpec(3_072, RSAKeyGenParameterSpec.F4));
      return generator.generateKeyPair();
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
    }
    return bytes;
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
  }

  private static final class NoopTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
