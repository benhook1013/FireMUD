package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerifiedProbeObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
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
  private static final String CANARY_PROFILE = AccountJwtReadinessProbeCrypto.CANARY_PROFILE;
  private static final String CANARY_AUDIENCE = AccountJwtReadinessProbeCrypto.CANARY_AUDIENCE;
  private static final String VALIDATOR_ID = AccountJwtReadinessProbeCrypto.VALIDATOR_ID;
  private static final Map<String, AccountJwtReadinessProbeCrypto.ProfileDescriptor>
      REPRESENTATIVE_PROFILES = AccountJwtReadinessProbeCrypto.representativeProfiles();

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
          AccountJwtReadinessProbeCrypto.verify(
              verifier,
              compactJwt,
              AccountJwtReadinessProbeCrypto.expectedProbe(
                  selector.probeKind(),
                  selector.tokenProfile(),
                  selector.audience(),
                  selector.jti().toString(),
                  current.targetKid(),
                  current.plannedIssuedAtEpochSecond(),
                  current.expiresAtEpochSecond()),
              maxControlUiTenantScopes);

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
      AccountJwtReadinessProbeCrypto.ProfileDescriptor descriptor =
          REPRESENTATIVE_PROFILES.get(profile);
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
    return AccountJwtReadinessProbeCrypto.createVerifier(trustedSource, clock);
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
}
