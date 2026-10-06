package net.firedevops.firemud.accountservice.service.session;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerifiedProbeObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.PlayerBootstrapJwtProfileValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner-internal, non-authorizing verification of one exact current Account readiness probe.
 *
 * <p>The only authenticated-caller value is created by the dedicated TLS interceptor. Both trust
 * bindings are reread before recording the observation. This service creates no principal,
 * SessionContext, authorization decision, application call, or fleet-completion evidence.
 */
@Service
@ConditionalOnProperty(
    prefix = "firemud.account.jwt-readiness.validation",
    name = "enabled",
    havingValue = "true")
public final class AccountJwtReadinessValidationService {
  private static final int SCHEMA_VERSION = 1;
  private static final int MAX_COMPACT_TOKEN_BYTES = 16 * 1024;
  private static final String RESERVED_SUBJECT = "00000000-0000-4000-8000-000000000001";
  private static final String ISSUER = "firemud-account-service";
  private static final String CANARY_PROFILE = "account-jwt-readiness-canary";
  private static final String CANARY_TYPE = "account_jwt_readiness_canary";
  private static final String CANARY_AUDIENCE = "firemud-account-jwt-readiness";
  private static final String VALIDATOR_ID = "account-service";
  private static final Set<String> CANARY_CLAIMS =
      Set.of("iss", "sub", "jti", "aud", "iat", "nbf", "exp", "tokenProfile", "tokenType");
  private static final Map<String, ProfileDescriptor> REPRESENTATIVE_PROFILES =
      Map.of(
          ControlUiJwtProfileValidator.PROFILE,
          new ProfileDescriptor(
              ControlUiJwtProfileValidator.PROFILE,
              ControlUiJwtProfileValidator.AUDIENCE,
              ControlUiJwtProfileValidator.TOKEN_TYPE,
              ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
              ControlUiJwtProfileValidator.OPTIONAL_CLAIMS),
          PlayerBootstrapJwtProfileValidator.PROFILE,
          new ProfileDescriptor(
              PlayerBootstrapJwtProfileValidator.PROFILE,
              PlayerBootstrapJwtProfileValidator.AUDIENCE,
              PlayerBootstrapJwtProfileValidator.TOKEN_TYPE,
              PlayerBootstrapJwtProfileValidator.REQUIRED_CLAIMS,
              PlayerBootstrapJwtProfileValidator.OPTIONAL_CLAIMS),
          GameSessionAccountDelegationProfile.PROFILE,
          new ProfileDescriptor(
              GameSessionAccountDelegationProfile.PROFILE,
              GameSessionAccountDelegationProfile.AUDIENCE,
              GameSessionAccountDelegationProfile.TYPE,
              GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
              GameSessionAccountDelegationJwtProfileValidator.OPTIONAL_CLAIMS));

  private final AccountJwtReadinessProbeRepository repository;
  private final AccountJwtReadinessTrustBinding readinessTrustBinding;
  private final AccountJwtSignerMaterializerTrustBinding signerTrustBinding;
  private final AccountAsymmetricJwtVerifier verifier;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;
  private final int maxControlUiTenantScopes;

  @Autowired
  public AccountJwtReadinessValidationService(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessTrustBinding readinessTrustBinding,
      AccountJwtSignerMaterializerTrustBinding signerTrustBinding,
      AccountJwtJwksTrustedSource trustedJwksSource,
      PlatformTransactionManager transactionManager,
      @Value("${firemud.account.jwt-readiness.validation.max-control-ui-tenant-scopes}")
          int maxControlUiTenantScopes) {
    this(
        repository,
        readinessTrustBinding,
        signerTrustBinding,
        createVerifier(trustedJwksSource, Clock.systemUTC()),
        new TransactionTemplate(transactionManager),
        Clock.systemUTC(),
        maxControlUiTenantScopes);
  }

  AccountJwtReadinessValidationService(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessTrustBinding readinessTrustBinding,
      AccountJwtSignerMaterializerTrustBinding signerTrustBinding,
      AccountAsymmetricJwtVerifier verifier,
      TransactionTemplate accountTransaction,
      Clock clock,
      int maxControlUiTenantScopes) {
    this.repository = Objects.requireNonNull(repository);
    this.readinessTrustBinding = Objects.requireNonNull(readinessTrustBinding);
    this.signerTrustBinding = Objects.requireNonNull(signerTrustBinding);
    this.verifier = Objects.requireNonNull(verifier);
    this.accountTransaction = Objects.requireNonNull(accountTransaction);
    this.accountTransaction.setReadOnly(false);
    this.clock = Objects.requireNonNull(clock);
    if (maxControlUiTenantScopes <= 0
        || maxControlUiTenantScopes
            > GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES) {
      throw new IllegalArgumentException(
          "Control UI readiness tenant-scope bound is missing or transport-incompatible");
    }
    this.maxControlUiTenantScopes = maxControlUiTenantScopes;
  }

  /** Verifies the exact signed probe and persists only a non-authorizing receipt. */
  public VerificationReceipt validate(
      ValidateReadinessProbeRequest request,
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller) {
    Objects.requireNonNull(request, "Readiness request is required");
    Binding readinessBinding = requireAuthenticatedBinding(authenticatedCaller);
    RequestSelector selector = parseRequest(request);
    var signerBinding = requireCurrentSignerBinding(readinessBinding);
    AccountJwtReadinessProbeRepository.ProbeEntry current =
        accountTransaction.execute(
            status ->
                repository.readCurrentEntry(
                    signerBinding.accountBinding(),
                    trustFence(signerBinding),
                    selector.operationId(),
                    selector.validatorId(),
                    selector.tokenProfile(),
                    selector.audience(),
                    selector.probeKind(),
                    selector.jti()));
    if (current == null) {
      throw new ValidationUnavailableException();
    }
    long nowEpochSecond = nowEpochSecond();
    if (current.state() != AccountJwtReadinessProbeRepository.ProbeState.ISSUED
        && current.state() != AccountJwtReadinessProbeRepository.ProbeState.VERIFIED) {
      throw new ProbeNotIssuedException();
    }
    if (nowEpochSecond < current.plannedIssuedAtEpochSecond()) {
      throw new AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException();
    }
    if (nowEpochSecond >= current.expiresAtEpochSecond()) {
      throw new AccountJwtReadinessProbeRepository.ProbeExpiredException();
    }

    String compactJwt = request.getCompactJwt();
    byte[] compactBytes = compactJwt.getBytes(StandardCharsets.US_ASCII);
    String compactHash = sha256(compactBytes);
    try {
      if (!current.compactTokenSha256().equals(Optional.of(compactHash))) {
        throw new AccountJwtReadinessProbeRepository.IdempotencyConflictException(
            "Readiness probe differs from its owner-recorded issuance");
      }
      VerifiedClaims verified =
          verifier.verify(compactJwt, policy(selector.probeKind(), selector.tokenProfile()));
      validateSignedClaims(verified, selector, current);

      PeerIdentity peer = authenticatedCaller.peer();
      VerifiedProbeObservation observation =
          new VerifiedProbeObservation(
              compactHash,
              verified.keyId(),
              readinessBinding.validatorInstanceId(),
              readinessBinding.bindingDigest(),
              readinessBinding.configRevision(),
              peer.uri(),
              peer.spkiSha256(),
              nowEpochSecond());
      return accountTransaction.execute(
          status -> {
            requireBindingsUnchanged(readinessBinding, signerBinding, authenticatedCaller);
            VerificationReceipt result =
                repository
                    .recordVerified(
                        signerBinding.accountBinding(),
                        trustFence(signerBinding),
                        selector.operationId(),
                        selector.validatorId(),
                        selector.tokenProfile(),
                        selector.audience(),
                        selector.probeKind(),
                        selector.jti(),
                        observation)
                    .verificationReceipt()
                    .orElseThrow(ValidationUnavailableException::new);
            requireBindingsUnchanged(readinessBinding, signerBinding, authenticatedCaller);
            return result;
          });
    } finally {
      java.util.Arrays.fill(compactBytes, (byte) 0);
    }
  }

  Binding requireAuthenticatedBinding(
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller) {
    if (authenticatedCaller == null) {
      throw new ReadinessCallerRejectedException();
    }
    Binding current =
        readinessTrustBinding.current().orElseThrow(ReadinessCallerRejectedException::new);
    if (!current.equals(authenticatedCaller.binding())
        || !current.isCurrentAt(nowEpochSecond())
        || !current.matches(authenticatedCaller.peer())) {
      throw new ReadinessCallerRejectedException();
    }
    return current;
  }

  AccountJwtSignerMaterializerTrustBinding.Binding requireCurrentSignerBinding(
      Binding readinessBinding) {
    var signerBinding =
        signerTrustBinding.current().orElseThrow(ValidationUnavailableException::new);
    if (!readinessBinding.environmentId().equals(signerBinding.environmentId())
        || !readinessBinding.clusterId().equals(signerBinding.clusterId())
        || !readinessBinding.namespace().equals(signerBinding.namespace())
        || !readinessBinding
            .expectedClusterIncarnationUid()
            .equals(signerBinding.expectedClusterIncarnationUid())
        || !readinessBinding.expectedNamespaceUid().equals(signerBinding.expectedNamespaceUid())) {
      throw new ReadinessCallerRejectedException();
    }
    return signerBinding;
  }

  void requireBindingsUnchanged(
      Binding readinessBinding,
      AccountJwtSignerMaterializerTrustBinding.Binding signerBinding,
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller) {
    Binding latestReadiness =
        readinessTrustBinding.current().orElseThrow(ReadinessCallerRejectedException::new);
    var latestSigner =
        signerTrustBinding.current().orElseThrow(ValidationUnavailableException::new);
    if (!readinessBinding.equals(latestReadiness)
        || !signerBinding.equals(latestSigner)
        || !latestReadiness.equals(authenticatedCaller.binding())
        || !latestReadiness.matches(authenticatedCaller.peer())
        || !latestReadiness.isCurrentAt(nowEpochSecond())) {
      throw new ReadinessCallerRejectedException();
    }
    requireCurrentSignerBinding(latestReadiness);
  }

  private RequestSelector parseRequest(ValidateReadinessProbeRequest request) {
    if (request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getCompactJwt().isEmpty()
        || request.getCompactJwt().length() > MAX_COMPACT_TOKEN_BYTES
        || !isAscii(request.getCompactJwt())) {
      throw new InvalidReadinessRequestException();
    }
    UUID operationId = canonicalUuid(request.getRotationOperationId());
    UUID jti = canonicalUuid(request.getJti());
    ProbeKind kind;
    try {
      kind = ProbeKind.valueOf(request.getProbeKind());
    } catch (RuntimeException invalidKind) {
      throw new InvalidReadinessRequestException();
    }
    String profile;
    String audience;
    if (kind == ProbeKind.CANARY) {
      profile = CANARY_PROFILE;
      audience = CANARY_AUDIENCE;
    } else {
      profile = request.getTokenProfile();
      ProfileDescriptor descriptor = REPRESENTATIVE_PROFILES.get(profile);
      if (descriptor == null) {
        throw new InvalidReadinessRequestException();
      }
      audience = descriptor.audience();
    }
    if (!VALIDATOR_ID.equals(request.getValidatorId())
        || !profile.equals(request.getTokenProfile())
        || !audience.equals(request.getAudience())) {
      throw new InvalidReadinessRequestException();
    }
    return new RequestSelector(
        operationId,
        request.getValidatorId(),
        request.getTokenProfile(),
        request.getAudience(),
        kind,
        jti);
  }

  private ExplicitRouteProfilePolicy policy(ProbeKind kind, String profile) {
    if (kind == ProbeKind.CANARY) {
      return new ExplicitRouteProfilePolicy(
          "account-jwt-readiness-canary",
          CANARY_PROFILE,
          CANARY_TYPE,
          ISSUER,
          CANARY_AUDIENCE,
          CANARY_CLAIMS,
          Set.of(),
          300L,
          5L,
          MAX_COMPACT_TOKEN_BYTES,
          AccountJwtReadinessValidationService::validateCanaryShape);
    }
    ProfileDescriptor descriptor = REPRESENTATIVE_PROFILES.get(profile);
    if (descriptor == null) {
      throw new InvalidReadinessRequestException();
    }
    return new ExplicitRouteProfilePolicy(
        "account-jwt-readiness-" + descriptor.profile(),
        descriptor.profile(),
        descriptor.tokenType(),
        "firemud-account-service",
        descriptor.audience(),
        descriptor.requiredClaims(),
        descriptor.optionalClaims(),
        GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
        5L,
        GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
        claimShapeValidator(descriptor.profile()));
  }

  private AccountAsymmetricJwtVerifier.ClaimShapeValidator claimShapeValidator(String profile) {
    return switch (profile) {
      case ControlUiJwtProfileValidator.PROFILE ->
          claims -> ControlUiJwtProfileValidator.validateClaims(claims, maxControlUiTenantScopes);
      case PlayerBootstrapJwtProfileValidator.PROFILE ->
          PlayerBootstrapJwtProfileValidator::validateClaims;
      case GameSessionAccountDelegationProfile.PROFILE ->
          GameSessionAccountDelegationJwtProfileValidator::validateClaims;
      default -> throw new InvalidReadinessRequestException();
    };
  }

  private static void validateSignedClaims(
      VerifiedClaims verified, RequestSelector selector, ProbeEntry entry) {
    Map<String, Object> claims = verified.claims();
    boolean canary = selector.probeKind() == ProbeKind.CANARY;
    ProfileDescriptor profile =
        canary ? null : REPRESENTATIVE_PROFILES.get(selector.tokenProfile());
    if (profile == null && !canary) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    String expectedPolicyType = canary ? CANARY_TYPE : profile.tokenType();
    if (!verified.profile().equals(selector.tokenProfile())
        || !verified.tokenType().equals(expectedPolicyType)
        || !verified.keyId().equals(entry.targetKid())
        || !ISSUER.equals(claims.get("iss"))
        || !selector.audience().equals(claims.get("aud"))
        || !RESERVED_SUBJECT.equals(claims.get("sub"))
        || !selector.jti().toString().equals(claims.get("jti"))
        || exactLong(claims.get("iat")) != entry.plannedIssuedAtEpochSecond()
        || exactLong(claims.get("nbf")) != entry.plannedIssuedAtEpochSecond()
        || exactLong(claims.get("exp")) != entry.expiresAtEpochSecond()) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    if (canary) {
      if (!CANARY_CLAIMS.equals(claims.keySet())
          || !CANARY_PROFILE.equals(claims.get("tokenProfile"))
          || !CANARY_TYPE.equals(claims.get("tokenType"))) {
        throw new AccountAsymmetricJwtVerifier.VerificationException();
      }
      return;
    }
    if (!RESERVED_SUBJECT.equals(claims.get("accountId"))
        || !exactOneCounter(claims.get("tokenGeneration"), profile)
        || !exactOneCounter(claims.get("issuanceFence"), profile)
        || !exactAuthorityTuple(claims.get("authorityTuple"), profile)
        || !(claims.get("membershipVersion") instanceof Map<?, ?> membershipVersion)
        || !membershipVersion.isEmpty()
        || !noRoleClaims(claims)
        || !noScopeClaims(profile, claims)) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static void validateCanaryShape(Map<String, Object> claims) {
    if (!CANARY_CLAIMS.equals(claims.keySet())
        || !ISSUER.equals(claims.get("iss"))
        || !CANARY_PROFILE.equals(claims.get("tokenProfile"))
        || !CANARY_TYPE.equals(claims.get("tokenType"))) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static boolean noRoleClaims(Map<String, Object> claims) {
    if (claims.containsKey("globalRoles")) {
      if (!(claims.get("globalRoles") instanceof java.util.List<?> roles) || !roles.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private static boolean noScopeClaims(ProfileDescriptor profile, Map<String, Object> claims) {
    if (profile.profile().equals(GameSessionAccountDelegationProfile.PROFILE)) {
      if (!claims.containsKey("scopedRoles")) {
        return true;
      }
    }
    return claims.get("scopedRoles") instanceof Map<?, ?> scopedRoles && scopedRoles.isEmpty();
  }

  private static boolean exactOneCounter(Object value, ProfileDescriptor profile) {
    if (GameSessionAccountDelegationProfile.PROFILE.equals(profile.profile())) {
      return "1".equals(value);
    }
    return exactLong(value) == 1L;
  }

  private static boolean exactAuthorityTuple(Object value, ProfileDescriptor profile) {
    if (!(value instanceof Map<?, ?> tuple)
        || !tuple
            .keySet()
            .equals(
                Set.of(
                    "issuerAuthGeneration",
                    "accountAuthorityGeneration",
                    "tenantAuthorityGeneration",
                    "membershipAuthorityGeneration",
                    "privateRealmGrantVersions"))) {
      return false;
    }
    return exactOneCounter(tuple.get("issuerAuthGeneration"), profile)
        && exactOneCounter(tuple.get("accountAuthorityGeneration"), profile)
        && tuple.get("tenantAuthorityGeneration") instanceof Map<?, ?> tenantAuthority
        && tenantAuthority.isEmpty()
        && tuple.get("membershipAuthorityGeneration") instanceof Map<?, ?> membershipAuthority
        && membershipAuthority.isEmpty()
        && tuple.get("privateRealmGrantVersions") instanceof java.util.List<?> grants
        && grants.isEmpty();
  }

  private static long exactLong(Object value) {
    if (!(value instanceof Number number)) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
    try {
      return new BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException invalidNumber) {
      throw new AccountAsymmetricJwtVerifier.VerificationException();
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID uuid = UUID.fromString(value);
      if (!uuid.toString().equals(value) || uuid.version() != 4 || uuid.variant() != 2) {
        throw new InvalidReadinessRequestException();
      }
      return uuid;
    } catch (RuntimeException invalidUuid) {
      throw new InvalidReadinessRequestException();
    }
  }

  private static boolean isAscii(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) {
        return false;
      }
    }
    return true;
  }

  long nowEpochSecond() {
    long value = clock.instant().getEpochSecond();
    if (value <= 0L) {
      throw new ValidationUnavailableException();
    }
    return value;
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new ValidationUnavailableException();
    }
  }

  static TrustFence trustFence(AccountJwtSignerMaterializerTrustBinding.Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static AccountAsymmetricJwtVerifier createVerifier(
      AccountJwtJwksTrustedSource trustedSource, Clock clock) {
    Objects.requireNonNull(trustedSource, "Protected Account JWKS source is required");
    var sourceIdentity = trustedSource.sourceIdentity();
    var cache =
        new AccountPublicJwksCache(trustedSource, sourceIdentity, clock, Duration.ofSeconds(300));
    return new AccountAsymmetricJwtVerifier(cache, clock);
  }

  public static final class InvalidReadinessRequestException extends IllegalArgumentException {
    public InvalidReadinessRequestException() {
      super("Readiness validation request is invalid");
    }
  }

  public static final class ReadinessCallerRejectedException extends SecurityException {
    public ReadinessCallerRejectedException() {
      super("Authenticated readiness caller is unavailable");
    }
  }

  public static final class ProbeNotIssuedException extends IllegalStateException {
    public ProbeNotIssuedException() {
      super("Readiness probe is not owner-recorded as issued");
    }
  }

  public static final class ValidationUnavailableException extends IllegalStateException {
    public ValidationUnavailableException() {
      super("Readiness validation evidence is unavailable");
    }
  }

  private record RequestSelector(
      UUID operationId,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti) {}

  private record ProfileDescriptor(
      String profile,
      String audience,
      String tokenType,
      Set<String> requiredClaims,
      Set<String> optionalClaims) {}
}
