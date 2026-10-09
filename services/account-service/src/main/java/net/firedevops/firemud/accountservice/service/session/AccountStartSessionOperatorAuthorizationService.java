package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient;
import net.firedevops.firemud.accountservice.client.StartSessionReservationEvidenceClient.Purpose;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceCandidate;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.IssuanceRecord;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionRequest;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionResult;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.Status;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService.Current;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository.OwnerLinearization;
import net.firedevops.firemud.accountservice.service.session.AccountOperatorAuthorizationReferenceFingerprint.ReferenceKind;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.loggingadmin.v1.ReadCurrentClaimEvidenceResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conditionally composed Account owner service for the approved human StartSession path. Its Spring
 * composition and receiving transport are disabled by default; every public operation authenticates
 * the immediate certificate-derived peer before parsing request data or opening Account storage.
 */
public final class AccountStartSessionOperatorAuthorizationService {
  private static final String ENVELOPE_OPERATION = "human-start-session-authorization/v1";
  private static final int REFERENCE_RANDOM_BYTES = 32;
  private static final int MAX_REFERENCE_TEXT_BYTES = 128;
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Set<String> RESPONSE_FIELDS =
      Set.of(
          "operatorAuthorizationReference",
          "authorizationReferenceFingerprint",
          "expiresAt",
          "authorityEvidenceBundle",
          "bundleReference");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final AccountControlUiActorService actors;
  private final AccountControlUiIssuanceRepository issuanceOperations;
  private final StartSessionReservationEvidenceClient reservationEvidence;
  private final AccountStartSessionOperatorAuthorizationRepository repository;
  private final AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys;
  private final AccountResponseEnvelopeCryptography responseCryptography;
  private final AccountHostedTermsService hostedTerms;
  private final TransactionTemplate lookupTransaction;
  private final Clock clock;
  private final SecureRandom secureRandom;
  private final String loggingPeerUri;
  private final String gameSessionPeerUri;
  private final String gameDesignPeerUri;
  private final long referenceLifetimeMillis;
  private final long responseRecoveryWindowMillis;

  /**
   * All custody and timing inputs are explicit. In particular, this service does not reuse the
   * control-ui envelope instance, create or discover HMAC keys, or provide policy defaults.
   */
  public AccountStartSessionOperatorAuthorizationService(
      AccountControlUiActorService actors,
      AccountControlUiIssuanceRepository issuanceOperations,
      StartSessionReservationEvidenceClient reservationEvidence,
      AccountStartSessionOperatorAuthorizationRepository repository,
      AccountOperatorAuthorizationFingerprintKeyring fingerprintKeys,
      AccountResponseEnvelopeCryptography dedicatedOperatorResponseCryptography,
      AccountHostedTermsService hostedTerms,
      PlatformTransactionManager transactions,
      Clock clock,
      SecureRandom secureRandom,
      String exactLoggingPeerUri,
      String exactGameSessionPeerUri,
      Duration referenceLifetime,
      Duration responseRecoveryWindow) {
    this.actors = Objects.requireNonNull(actors, "current Account actor service is required");
    this.issuanceOperations =
        Objects.requireNonNull(issuanceOperations, "Account control-ui issuance owner is required");
    this.reservationEvidence =
        Objects.requireNonNull(reservationEvidence, "Logging claim evidence client is required");
    this.repository = Objects.requireNonNull(repository, "Account owner repository is required");
    this.fingerprintKeys =
        Objects.requireNonNull(fingerprintKeys, "explicit Account fingerprint keyring is required");
    responseCryptography =
        Objects.requireNonNull(
            dedicatedOperatorResponseCryptography,
            "separate operator response-envelope cryptography is required");
    this.hostedTerms =
        Objects.requireNonNull(hostedTerms, "Account environment source is required");
    this.clock = Objects.requireNonNull(clock, "clock is required");
    this.secureRandom = Objects.requireNonNull(secureRandom, "secure random is required");
    loggingPeerUri = exactPeer(exactLoggingPeerUri, "logging-admin-service");
    gameSessionPeerUri = exactPeer(exactGameSessionPeerUri, "game-session-service");
    gameDesignPeerUri = sameNamespacePeer(gameSessionPeerUri, "game-design-service");
    referenceLifetimeMillis = policyMillis(referenceLifetime, "reference lifetime", 5 * 60_000L);
    responseRecoveryWindowMillis =
        policyMillis(responseRecoveryWindow, "response recovery window", 60_000L);
    lookupTransaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    lookupTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * ISSUE: current exact Logging peer, fresh original claim, current actor, one owner transaction.
   */
  public AuthorizationResponse issue(IssueRequest request) {
    requirePeer(loggingPeerUri);
    Objects.requireNonNull(request, "issue request is required");
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(request.canonicalTupleBytes());
    requirePositiveFence(request.reservationClaimFence(), "reservationClaimFence");
    requireNonNilUuid(request.reservationOwnerId(), "reservationOwnerId");
    ReadCurrentClaimEvidenceResponse claim =
        reservationEvidence.readCurrentClaimEvidence(
            tuple,
            request.reservationOwnerId(),
            request.reservationClaimFence(),
            request.claimOwnerId(),
            request.claimFence(),
            Purpose.ISSUE);
    requireClaimEcho(
        claim,
        tuple,
        request.reservationOwnerId(),
        request.reservationClaimFence(),
        request.claimOwnerId(),
        request.claimFence());

    var environment = hostedTerms.captureCurrentEnvironmentBoundary();
    IssuePreparation preparation =
        actors.withCurrent(
            request.controlUiJwt(),
            tuple.action().scope().tenantId(),
            environment,
            current -> {
              IssuanceRecord existing = findRecord(tuple.controlPlaneRequestId()).orElse(null);
              OwnerLinearization linearization =
                  existing == null ? issuanceOperations.captureOwnerLinearization() : null;
              return new IssuePreparation(current, existing, linearization);
            });

    if (preparation.existing() != null) {
      IssuanceRecord original = preparation.existing();
      requireOriginalBinding(
          original,
          tuple,
          request.canonicalTupleBytes(),
          request.reservationOwnerId(),
          request.reservationClaimFence());
      AuthorizationResponse response = openOriginalResponse(tuple, original);
      return actors.withCurrent(
          request.controlUiJwt(),
          tuple.action().scope().tenantId(),
          environment,
          current -> {
            requireSameCurrent(preparation.current(), current);
            IssuanceRecord exact =
                findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
            requireSameStoredIssuance(original, exact);
            requireOriginalBinding(
                exact,
                tuple,
                request.canonicalTupleBytes(),
                request.reservationOwnerId(),
                request.reservationClaimFence());
            AccountStartSessionOperatorAuthorityBundle.decode(exact.authorityEvidenceBundle())
                .requireCurrent(
                    current.source(),
                    current.stored(),
                    tuple,
                    AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                        original.bundleReference()),
                    clock.instant());
            return response;
          });
    }

    PreparedIssuance candidate =
        prepareIssuance(preparation.current(), tuple, request, preparation.linearization());
    IssueOutcome outcome =
        actors.withCurrent(
            request.controlUiJwt(),
            tuple.action().scope().tenantId(),
            environment,
            current -> {
              requireSameCurrent(preparation.current(), current);
              candidate
                  .bundle()
                  .requireCurrent(
                      current.source(),
                      current.stored(),
                      tuple,
                      AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                          candidate.candidate().bundleReference()),
                      clock.instant());
              var created = repository.createOrReadExact(candidate.candidate());
              requireOriginalBinding(
                  created.issuance(),
                  tuple,
                  request.canonicalTupleBytes(),
                  request.reservationOwnerId(),
                  request.reservationClaimFence());
              if (created.created()) {
                requireCandidateRecord(candidate.candidate(), created.issuance());
              }
              return new IssueOutcome(created.created(), created.issuance());
            });
    if (outcome.created()) {
      return candidate.response();
    }

    AuthorizationResponse response = openOriginalResponse(tuple, outcome.issuance());
    return actors.withCurrent(
        request.controlUiJwt(),
        tuple.action().scope().tenantId(),
        environment,
        current -> {
          requireSameCurrent(preparation.current(), current);
          IssuanceRecord exact =
              findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
          requireSameStoredIssuance(outcome.issuance(), exact);
          AccountStartSessionOperatorAuthorityBundle.decode(exact.authorityEvidenceBundle())
              .requireCurrent(
                  current.source(),
                  current.stored(),
                  tuple,
                  AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                      exact.bundleReference()),
                  clock.instant());
          return response;
        });
  }

  /**
   * RECOVER: lookup-only, fresh recovery claim, current original actor, exact original response.
   */
  public AuthorizationResponse recover(RecoverRequest request) {
    requirePeer(loggingPeerUri);
    Objects.requireNonNull(request, "recovery request is required");
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(request.canonicalTupleBytes());
    requireNonNilUuid(request.reservationOwnerId(), "reservationOwnerId");
    requirePositiveFence(request.reservationClaimFence(), "reservationClaimFence");
    requireNonNilUuid(request.claimOwnerId(), "claimOwnerId");
    requirePositiveFence(request.claimFence(), "claimFence");
    ReadCurrentClaimEvidenceResponse claim =
        reservationEvidence.readCurrentClaimEvidence(
            tuple,
            request.reservationOwnerId(),
            request.reservationClaimFence(),
            request.claimOwnerId(),
            request.claimFence(),
            Purpose.RECOVER);
    requireClaimEcho(
        claim,
        tuple,
        request.reservationOwnerId(),
        request.reservationClaimFence(),
        request.claimOwnerId(),
        request.claimFence());

    IssuanceRecord observed =
        findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
    requireOriginalBinding(
        observed,
        tuple,
        request.canonicalTupleBytes(),
        request.reservationOwnerId(),
        request.reservationClaimFence());
    AccountStartSessionOperatorAuthorityBundle bundle =
        AccountStartSessionOperatorAuthorityBundle.decode(observed.authorityEvidenceBundle());
    bundle.requireTupleBinding(tuple);
    UUID tokenJti = bundle.controlUiTokenJti();
    var environment = hostedTerms.captureCurrentEnvironmentBoundary();
    Current preparation =
        actors.withCurrentCommitted(
            tuple.actor().accountId(),
            tuple.action().scope().tenantId(),
            tokenJti,
            environment,
            current -> {
              IssuanceRecord exact =
                  findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
              requireSameStoredIssuance(observed, exact);
              requireOriginalBinding(
                  exact,
                  tuple,
                  request.canonicalTupleBytes(),
                  request.reservationOwnerId(),
                  request.reservationClaimFence());
              return current;
            });
    AuthorizationResponse response = openOriginalResponse(tuple, observed);
    return actors.withCurrentCommitted(
        tuple.actor().accountId(),
        tuple.action().scope().tenantId(),
        tokenJti,
        environment,
        current -> {
          requireSameCurrent(preparation, current);
          IssuanceRecord exact =
              findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
          requireSameStoredIssuance(observed, exact);
          requireOriginalBinding(
              exact,
              tuple,
              request.canonicalTupleBytes(),
              request.reservationOwnerId(),
              request.reservationClaimFence());
          AccountStartSessionOperatorAuthorityBundle.decode(exact.authorityEvidenceBundle())
              .requireCurrent(
                  current.source(),
                  current.stored(),
                  tuple,
                  AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                      exact.bundleReference()),
                  clock.instant());
          return response;
        });
  }

  /** REDEEM: exact Game Session peer before tuple parsing or durable reference lookup. */
  public RedemptionResult redeem(RedeemRequest request) {
    requirePeer(gameSessionPeerUri);
    Objects.requireNonNull(request, "redemption request is required");
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(request.canonicalTupleBytes());
    String reference = requireReference(request.operatorAuthorizationReference());
    byte[] referenceBytes = reference.getBytes(StandardCharsets.US_ASCII);
    try {
      requireFingerprint(request.authorizationReferenceFingerprint());
      requireNonNilUuid(request.reservationOwnerId(), "reservationOwnerId");
      requirePositiveFence(request.reservationClaimFence(), "reservationClaimFence");
      requireNonNilUuid(request.ownerAttemptId(), "ownerAttemptId");
      requirePositiveFence(request.ownerFence(), "ownerFence");

      IssuanceRecord observed =
          findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
      requireOriginalBinding(
          observed,
          tuple,
          request.canonicalTupleBytes(),
          request.reservationOwnerId(),
          request.reservationClaimFence());
      boolean sameAttemptReplay =
          observed.status() == Status.REDEEMED
              && gameSessionPeerUri.equals(observed.redemptionRedeemerWorkloadUri())
              && request.ownerAttemptId().equals(observed.redemptionOwnerAttemptId())
              && Objects.equals(
                  Long.valueOf(request.ownerFence()), observed.redemptionOwnerFence());
      if (observed.status() == Status.REDEEMED && !sameAttemptReplay) {
        throw denied("A consumed StartSession reference cannot authorize another owner attempt");
      }
      if (!fingerprintKeys.matchesOriginal(
          ReferenceKind.HUMAN_OPERATOR,
          referenceBytes,
          observed.authorizationReferenceFingerprint(),
          observed.referenceExpiresAt(),
          observed.responseEnvelopeExpiresAt())) {
        throw denied("Original Account authorization reference does not match");
      }
      if (!constantTimeEquals(
          observed.authorizationReferenceFingerprint(),
          request.authorizationReferenceFingerprint())) {
        throw denied("Forwarded Account authorization fingerprint changed");
      }

      AccountStartSessionOperatorAuthorityBundle bundle =
          AccountStartSessionOperatorAuthorityBundle.decode(observed.authorityEvidenceBundle());
      bundle.requireTupleBinding(tuple);
      var originalResponse = tryOpenOriginalResponse(observed, bundle, tuple);
      if (originalResponse.isEmpty() && !sameAttemptReplay) {
        throw denied("Original Account issuance response is no longer recoverable");
      }
      if (originalResponse.isPresent()
          && !constantTimeEquals(
              reference, originalResponse.orElseThrow().operatorAuthorizationReference())) {
        throw denied("Redeemed opaque reference differs from the original Account response");
      }
      var environment = hostedTerms.captureCurrentEnvironmentBoundary();
      return actors.withCurrentCommitted(
          tuple.actor().accountId(),
          tuple.action().scope().tenantId(),
          bundle.controlUiTokenJti(),
          environment,
          current -> {
            IssuanceRecord exact =
                findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
            requireSameStoredIssuance(observed, exact);
            requireOriginalBinding(
                exact,
                tuple,
                request.canonicalTupleBytes(),
                request.reservationOwnerId(),
                request.reservationClaimFence());
            if (sameAttemptReplay && originalResponse.isEmpty()) {
              bundle.requireCurrentSnapshotForExactReplay(
                  current.source(),
                  current.stored(),
                  tuple,
                  AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                      observed.bundleReference()));
            } else {
              bundle.requireCurrent(
                  current.source(),
                  current.stored(),
                  tuple,
                  AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                      observed.bundleReference()),
                  clock.instant());
            }
            RedemptionResult result =
                repository.redeemExact(
                    new RedemptionRequest(
                        tuple.controlPlaneRequestId(),
                        request.canonicalTupleBytes(),
                        observed.authorizationReferenceFingerprint(),
                        observed.reservationOwnerId(),
                        observed.reservationClaimFence(),
                        gameSessionPeerUri,
                        request.ownerAttemptId(),
                        request.ownerFence(),
                        observed.authorityEvidenceBundle(),
                        Instant.ofEpochMilli(clock.millis())));
            requireRedemptionProjection(result, observed);
            if (sameAttemptReplay != result.replay()) {
              throw denied("Account returned a changed one-time redemption outcome");
            }
            return result;
          });
    } finally {
      Arrays.fill(referenceBytes, (byte) 0);
    }
  }

  /**
   * Returns only the immutable original projection of an already-redeemed operation to the exact
   * same-namespace Game Design peer. This read neither exposes a credential nor changes owner
   * state; Account independently revalidates the original committed actor and source.
   */
  public RedeemedOperationProjection readRedeemedOperationProjection(
      ReadRedeemedOperationProjectionRequest request) {
    requirePeer(gameDesignPeerUri);
    Objects.requireNonNull(request, "redeemed operation projection request is required");
    byte[] exactTupleBytes = request.canonicalPreAuthorizationTuple();
    StartSessionPreAuthorizationReservationTuple tuple = decodeTuple(exactTupleBytes);
    requireFingerprint(request.authorizationReferenceFingerprint());
    requireNonNilUuid(request.reservationOwnerId(), "reservationOwnerId");
    requirePositiveFence(request.reservationClaimFence(), "reservationClaimFence");
    requireNonNilUuid(request.ownerAttemptId(), "ownerAttemptId");
    requirePositiveFence(request.ownerFence(), "ownerFence");
    if (!"game-session-service".equals(tuple.targetOwner())) {
      throw denied("Operation projection is limited to the Game Session owner");
    }

    IssuanceRecord observed =
        findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
    requireOriginalBinding(
        observed,
        tuple,
        exactTupleBytes,
        request.reservationOwnerId(),
        request.reservationClaimFence());
    AccountStartSessionOperatorAuthorityBundle bundle =
        AccountStartSessionOperatorAuthorityBundle.decode(observed.authorityEvidenceBundle());
    bundle.requireTupleBinding(tuple);
    requireRedeemedProjectionBinding(observed, tuple, request, bundle, clock.instant());

    var environment = hostedTerms.captureCurrentEnvironmentBoundary();
    return actors.withCurrentCommitted(
        tuple.actor().accountId(),
        tuple.action().scope().tenantId(),
        bundle.controlUiTokenJti(),
        environment,
        current -> {
          IssuanceRecord exact =
              findRecord(tuple.controlPlaneRequestId()).orElseThrow(NotFoundException::new);
          requireSameStoredRedemption(observed, exact);
          requireOriginalBinding(
              exact,
              tuple,
              exactTupleBytes,
              request.reservationOwnerId(),
              request.reservationClaimFence());
          AccountStartSessionOperatorAuthorityBundle exactBundle =
              AccountStartSessionOperatorAuthorityBundle.decode(exact.authorityEvidenceBundle());
          exactBundle.requireTupleBinding(tuple);
          requireRedeemedProjectionBinding(exact, tuple, request, exactBundle, clock.instant());
          exactBundle.requireCurrent(
              current.source(),
              current.stored(),
              tuple,
              AccountStartSessionOperatorAuthorityBundle.fromSharedReference(
                  exact.bundleReference()),
              clock.instant());
          return projection(exact);
        });
  }

  private PreparedIssuance prepareIssuance(
      Current current,
      StartSessionPreAuthorizationReservationTuple tuple,
      IssueRequest request,
      OwnerLinearization linearization) {
    Objects.requireNonNull(linearization, "Account owner source linearization is required");
    byte[] exactTuple = request.canonicalTupleBytes();
    Instant issuedAt = Instant.ofEpochMilli(clock.millis());
    Instant referenceExpiresAt = addMillis(issuedAt, referenceLifetimeMillis);
    Instant responseExpiresAt = addMillis(referenceExpiresAt, responseRecoveryWindowMillis);
    UUID issuanceOperationId = UUID.randomUUID();
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.create(
            tuple,
            current.source(),
            current.stored(),
            issuanceOperationId,
            linearization,
            issuedAt,
            referenceExpiresAt);
    var bundleReference = bundle.referenceForSource(current.source(), linearization);

    byte[] random = new byte[REFERENCE_RANDOM_BYTES];
    secureRandom.nextBytes(random);
    String authorizationReference = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    Arrays.fill(random, (byte) 0);
    byte[] referenceBytes = authorizationReference.getBytes(StandardCharsets.US_ASCII);
    byte[] responseBytes = null;
    try {
      String fingerprint =
          fingerprintKeys.fingerprintForIssue(ReferenceKind.HUMAN_OPERATOR, referenceBytes);
      AuthorizationResponse response =
          new AuthorizationResponse(
              authorizationReference,
              fingerprint,
              referenceExpiresAt,
              bundle.canonicalBytes(),
              bundleReference);
      responseBytes = encodeResponse(response);
      EncryptedResponseEnvelope envelope =
          responseCryptography.encrypt(
              responseBinding(
                  issuanceOperationId,
                  tuple,
                  exactTuple,
                  loggingPeerUri,
                  linearization.sourceFence(),
                  bundle),
              responseBytes,
              responseExpiresAt);
      IssuanceCandidate candidate =
          new IssuanceCandidate(
              tuple.controlPlaneRequestId(),
              exactTuple,
              tuple.mutationDigest(),
              loggingPeerUri,
              request.reservationOwnerId(),
              request.reservationClaimFence(),
              issuanceOperationId,
              linearization.sourceFence(),
              AccountStartSessionOperatorAuthorityBundle.toSharedReference(bundleReference),
              bundle.canonicalBytes(),
              fingerprint,
              envelope.bytes(),
              issuedAt,
              referenceExpiresAt,
              responseExpiresAt);
      return new PreparedIssuance(bundle, response, candidate);
    } finally {
      Arrays.fill(referenceBytes, (byte) 0);
      if (responseBytes != null) Arrays.fill(responseBytes, (byte) 0);
    }
  }

  private AuthorizationResponse openOriginalResponse(
      StartSessionPreAuthorizationReservationTuple tuple, IssuanceRecord record) {
    AccountStartSessionOperatorAuthorityBundle bundle =
        AccountStartSessionOperatorAuthorityBundle.decode(record.authorityEvidenceBundle());
    bundle.requireTupleBinding(tuple);
    byte[] plaintext = null;
    try {
      plaintext =
          responseCryptography.decrypt(
              new EncryptedResponseEnvelope(record.encryptedResponseEnvelope()),
              responseBinding(
                  record.issuanceOperationId(),
                  tuple,
                  record.preAuthorizationTuple(),
                  record.issuanceWorkloadUri(),
                  record.issuanceFence(),
                  bundle),
              record.responseEnvelopeExpiresAt());
      AuthorizationResponse response = decodeResponse(plaintext);
      requireResponseMatchesRecord(response, record);
      return response;
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
    }
  }

  private Optional<AuthorizationResponse> tryOpenOriginalResponse(
      IssuanceRecord record,
      AccountStartSessionOperatorAuthorityBundle bundle,
      StartSessionPreAuthorizationReservationTuple tuple) {
    if (!clock.instant().isBefore(record.responseEnvelopeExpiresAt())) {
      return Optional.empty();
    }
    byte[] plaintext = null;
    try {
      plaintext =
          responseCryptography.decrypt(
              new EncryptedResponseEnvelope(record.encryptedResponseEnvelope()),
              responseBinding(
                  record.issuanceOperationId(),
                  tuple,
                  record.preAuthorizationTuple(),
                  record.issuanceWorkloadUri(),
                  record.issuanceFence(),
                  bundle),
              record.responseEnvelopeExpiresAt());
      AuthorizationResponse response = decodeResponse(plaintext);
      requireResponseMatchesRecord(response, record);
      return Optional.of(response);
    } catch (AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException expired) {
      return Optional.empty();
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
    }
  }

  private Binding responseBinding(
      UUID operationId,
      StartSessionPreAuthorizationReservationTuple tuple,
      byte[] exactTuple,
      String callerWorkload,
      long issuanceFence,
      AccountStartSessionOperatorAuthorityBundle bundle) {
    return new Binding(
        ENVELOPE_OPERATION,
        tuple.controlPlaneRequestId(),
        sha256(exactTuple),
        callerWorkload,
        operationId.toString(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        issuanceFence,
        AccountControlUiAuthority.canonical(bundle.authorityTuple()),
        AccountControlUiAuthority.canonical(bundle.membershipVersion()),
        bundle.canonicalBytes());
  }

  private static byte[] encodeResponse(AuthorizationResponse response) {
    return AccountControlUiAuthority.canonical(
        Map.of(
            "operatorAuthorizationReference", response.operatorAuthorizationReference(),
            "authorizationReferenceFingerprint", response.authorizationReferenceFingerprint(),
            "expiresAt", response.expiresAt().toString(),
            "authorityEvidenceBundle",
                AccountStartSessionOperatorAuthorityBundle.decode(
                        response.authorityEvidenceBundle())
                    .jsonValue(),
            "bundleReference", response.bundleReference().asJsonValue()));
  }

  private static AuthorizationResponse decodeResponse(byte[] exactBytes) {
    try {
      String json = new String(exactBytes, StandardCharsets.UTF_8);
      Map<String, Object> root = JSON.readValue(json, new TypeReference<>() {});
      if (!root.keySet().equals(RESPONSE_FIELDS)) {
        throw denied("Original response envelope has changed fields");
      }
      String reference = requireReference(root.get("operatorAuthorizationReference"));
      String fingerprint = requireFingerprint(root.get("authorizationReferenceFingerprint"));
      Instant expiresAt = canonicalInstant(root.get("expiresAt"));
      byte[] bundleBytes = AccountControlUiAuthority.canonical(root.get("authorityEvidenceBundle"));
      AccountStartSessionOperatorAuthorityBundle.decode(bundleBytes);
      var sourceReference =
          AccountStartSessionOperatorAuthorityBundle.BundleReference.fromJsonValue(
              root.get("bundleReference"));
      byte[] canonical =
          encodeResponse(
              new AuthorizationResponse(
                  reference, fingerprint, expiresAt, bundleBytes, sourceReference));
      if (!MessageDigest.isEqual(canonical, exactBytes)) {
        throw denied("Original response envelope is not canonical");
      }
      return new AuthorizationResponse(
          reference, fingerprint, expiresAt, bundleBytes, sourceReference);
    } catch (IllegalStateException failure) {
      throw failure;
    } catch (RuntimeException malformed) {
      throw denied("Original response envelope is malformed");
    }
  }

  private void requireResponseMatchesRecord(AuthorizationResponse response, IssuanceRecord record) {
    var bundle =
        AccountStartSessionOperatorAuthorityBundle.decode(record.authorityEvidenceBundle());
    if (!response
            .authorizationReferenceFingerprint()
            .equals(record.authorizationReferenceFingerprint())
        || !response.expiresAt().equals(record.referenceExpiresAt())
        || !Arrays.equals(response.authorityEvidenceBundle(), record.authorityEvidenceBundle())
        || !response
            .bundleReference()
            .bundleVersion()
            .equals(AccountStartSessionOperatorAuthorityBundle.BUNDLE_VERSION)
        || !response.bundleReference().sourceVersion().equals(bundleSourceVersion(bundle))
        || !AccountStartSessionOperatorAuthorityBundle.toSharedReference(response.bundleReference())
            .equals(record.bundleReference())
        || !response
            .bundleReference()
            .sourceFence()
            .equals(Long.toString(record.issuanceFence()))) {
      throw denied("Original issuance response differs from the immutable Account record");
    }
    byte[] referenceBytes =
        response.operatorAuthorizationReference().getBytes(StandardCharsets.US_ASCII);
    try {
      if (!fingerprintKeys.matchesOriginal(
          ReferenceKind.HUMAN_OPERATOR,
          referenceBytes,
          record.authorizationReferenceFingerprint(),
          record.referenceExpiresAt(),
          record.responseEnvelopeExpiresAt())) {
        throw denied("Original Account response reference does not match its stored fingerprint");
      }
    } finally {
      Arrays.fill(referenceBytes, (byte) 0);
    }
  }

  private static void requireSameCurrent(Current expected, Current observed) {
    if (!sameCurrentSource(expected.source(), observed.source())
        || !sameControlUiIssuance(expected.stored(), observed.stored())) {
      throw denied("Account actor or source changed between preparation and owner commit");
    }
  }

  private static boolean sameCurrentSource(
      AccountControlUiAuthority.Snapshot first, AccountControlUiAuthority.Snapshot second) {
    return first.actor().equals(second.actor())
        && first.tenant().equals(second.tenant())
        && Arrays.equals(first.evidence(), second.evidence())
        && Arrays.equals(
            AccountControlUiAuthority.canonical(first.authorityTuple()),
            AccountControlUiAuthority.canonical(second.authorityTuple()))
        && Arrays.equals(
            AccountControlUiAuthority.canonical(first.membershipVersion()),
            AccountControlUiAuthority.canonical(second.membershipVersion()));
  }

  private static boolean sameControlUiIssuance(
      AccountControlUiIssuanceRepository.Stored first,
      AccountControlUiIssuanceRepository.Stored second) {
    return first != null
        && second != null
        && Objects.equals(first.requestId, second.requestId)
        && Objects.equals(first.operationId, second.operationId)
        && Objects.equals(first.jti, second.jti)
        && Objects.equals(first.accountId, second.accountId)
        && Objects.equals(first.tenantId, second.tenantId)
        && Objects.equals(first.callerContextId, second.callerContextId)
        && Objects.equals(first.caller, second.caller)
        && Objects.equals(first.requestMacKeyId, second.requestMacKeyId)
        && Objects.equals(first.requestDigest, second.requestDigest)
        && Objects.equals(first.status, second.status)
        && Objects.equals(first.tokenHash, second.tokenHash)
        && Arrays.equals(first.claims, second.claims)
        && Arrays.equals(first.sources, second.sources)
        && Arrays.equals(first.bundle, second.bundle)
        && Arrays.equals(first.signerReceipt, second.signerReceipt)
        && Arrays.equals(first.pendingRegistry, second.pendingRegistry)
        && Arrays.equals(first.activeRegistry, second.activeRegistry)
        && Objects.equals(first.issuedAt, second.issuedAt)
        && Objects.equals(first.expiresAt, second.expiresAt)
        && Objects.equals(first.recoveryExpiry, second.recoveryExpiry);
  }

  private static void requireCandidateRecord(IssuanceCandidate candidate, IssuanceRecord record) {
    if (record.status() != Status.ISSUED
        || !candidate.controlPlaneRequestId().equals(record.controlPlaneRequestId())
        || !Arrays.equals(candidate.preAuthorizationTuple(), record.preAuthorizationTuple())
        || !candidate.mutationDigest().equals(record.mutationDigest())
        || !candidate.issuanceWorkloadUri().equals(record.issuanceWorkloadUri())
        || !candidate.reservationOwnerId().equals(record.reservationOwnerId())
        || candidate.reservationClaimFence() != record.reservationClaimFence()
        || !candidate.issuanceOperationId().equals(record.issuanceOperationId())
        || candidate.issuanceFence() != record.issuanceFence()
        || !candidate.bundleReference().equals(record.bundleReference())
        || !Arrays.equals(candidate.authorityEvidenceBundle(), record.authorityEvidenceBundle())
        || !candidate
            .authorizationReferenceFingerprint()
            .equals(record.authorizationReferenceFingerprint())
        || !Arrays.equals(candidate.encryptedResponseEnvelope(), record.encryptedResponseEnvelope())
        || !candidate.issuedAt().equals(record.issuedAt())
        || !candidate.referenceExpiresAt().equals(record.referenceExpiresAt())
        || !candidate.responseEnvelopeExpiresAt().equals(record.responseEnvelopeExpiresAt())) {
      throw denied("New Account issuance differs from its exact durable candidate");
    }
  }

  private static String bundleSourceVersion(AccountStartSessionOperatorAuthorityBundle bundle) {
    return bundle.sourceEvidenceVersion();
  }

  private static void requireRedemptionProjection(
      RedemptionResult result, IssuanceRecord issuance) {
    if (!issuance
            .authorizationReferenceFingerprint()
            .equals(result.authorizationReferenceFingerprint())
        || !Arrays.equals(issuance.authorityEvidenceBundle(), result.authorityEvidenceBundle())
        || !issuance.issuanceOperationId().equals(result.issuanceOperationId())
        || issuance.issuanceFence() != result.issuanceFence()) {
      throw denied("Account redemption returned a changed authority projection");
    }
  }

  private Optional<IssuanceRecord> findRecord(String controlPlaneRequestId) {
    return Objects.requireNonNull(
        lookupTransaction.execute(
            ignored -> repository.findByControlPlaneRequestId(controlPlaneRequestId)),
        "Account lookup transaction result");
  }

  private static void requireClaimEcho(
      ReadCurrentClaimEvidenceResponse evidence,
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID currentClaimOwnerId,
      long currentClaimFence) {
    if (!tuple.controlPlaneRequestId().equals(evidence.getControlPlaneRequestId())
        || !Arrays.equals(
            tuple.canonicalJson().getBytes(StandardCharsets.UTF_8),
            evidence.getPreAuthorizationTupleJson().toByteArray())
        || !tuple.mutationDigest().equals(evidence.getMutationDigest())
        || !reservationOwnerId.toString().equals(evidence.getReservationOwnerId())
        || reservationClaimFence != evidence.getReservationClaimFence()
        || !currentClaimOwnerId.toString().equals(evidence.getClaimOwnerId())
        || currentClaimFence != evidence.getClaimFence()) {
      throw denied("Logging/Admin current reservation claim differs from the exact request");
    }
  }

  private static StartSessionPreAuthorizationReservationTuple decodeTuple(byte[] exactTupleBytes) {
    if (exactTupleBytes == null || exactTupleBytes.length == 0) {
      throw new IllegalArgumentException("Canonical StartSession reservation tuple is required");
    }
    String json = new String(exactTupleBytes, StandardCharsets.UTF_8);
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(json);
    if (!Arrays.equals(exactTupleBytes, tuple.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("StartSession reservation tuple bytes are not canonical");
    }
    return tuple;
  }

  private void requireOriginalBinding(
      IssuanceRecord record,
      StartSessionPreAuthorizationReservationTuple tuple,
      byte[] exactTuple,
      UUID reservationOwnerId,
      long reservationClaimFence) {
    if (!tuple.controlPlaneRequestId().equals(record.controlPlaneRequestId())
        || !Arrays.equals(exactTuple, record.preAuthorizationTuple())
        || !tuple.mutationDigest().equals(record.mutationDigest())
        || !loggingPeerUri.equals(record.issuanceWorkloadUri())
        || !reservationOwnerId.equals(record.reservationOwnerId())
        || reservationClaimFence != record.reservationClaimFence()) {
      throw denied("StartSession request conflicts with the original Account issuance");
    }
  }

  private void requireRedeemedProjectionBinding(
      IssuanceRecord record,
      StartSessionPreAuthorizationReservationTuple tuple,
      ReadRedeemedOperationProjectionRequest request,
      AccountStartSessionOperatorAuthorityBundle bundle,
      Instant now) {
    if (record.status() != Status.REDEEMED
        || !gameSessionPeerUri.equals(record.redemptionRedeemerWorkloadUri())
        || !request.ownerAttemptId().equals(record.redemptionOwnerAttemptId())
        || !Objects.equals(Long.valueOf(request.ownerFence()), record.redemptionOwnerFence())
        || !constantTimeEquals(
            record.authorizationReferenceFingerprint(), request.authorizationReferenceFingerprint())
        || !constantTimeEquals(
            record.authorizationReferenceFingerprint(), record.redemptionReferenceFingerprint())
        || !Arrays.equals(
            record.authorityEvidenceBundle(), record.redemptionAuthorityEvidenceBundle())
        || !tuple.controlPlaneRequestId().equals(bundle.controlPlaneRequestId())
        || !tuple.mutationDigest().equals(record.mutationDigest())
        || !bundle.issuanceOperationId().equals(record.issuanceOperationId())
        || !bundle.sourceEvidenceVersion().equals(record.bundleReference().sourceVersion())
        || !bundle.authorizationExpiresAt().equals(record.referenceExpiresAt().toString())
        || record.redeemedAt().isBefore(record.issuedAt())
        || now.isBefore(record.redeemedAt())
        || !record.redeemedAt().isBefore(record.referenceExpiresAt())
        || !now.isBefore(record.referenceExpiresAt())) {
      throw denied("Stored redeemed operation does not match the exact current read request");
    }
  }

  private static RedeemedOperationProjection projection(IssuanceRecord record) {
    return new RedeemedOperationProjection(
        record.controlPlaneRequestId(),
        record.preAuthorizationTuple(),
        record.mutationDigest(),
        record.authorizationReferenceFingerprint(),
        record.reservationOwnerId(),
        record.reservationClaimFence(),
        record.redemptionRedeemerWorkloadUri(),
        record.redemptionOwnerAttemptId(),
        record.redemptionOwnerFence(),
        record.referenceExpiresAt(),
        record.redeemedAt(),
        record.issuanceOperationId(),
        record.issuanceFence(),
        AccountStartSessionOperatorAuthorityBundle.fromSharedReference(record.bundleReference()),
        record.authorityEvidenceBundle());
  }

  private static void requireSameStoredIssuance(IssuanceRecord original, IssuanceRecord observed) {
    if (!original.controlPlaneRequestId().equals(observed.controlPlaneRequestId())
        || !Arrays.equals(original.preAuthorizationTuple(), observed.preAuthorizationTuple())
        || !original.mutationDigest().equals(observed.mutationDigest())
        || !original.issuanceWorkloadUri().equals(observed.issuanceWorkloadUri())
        || !original.reservationOwnerId().equals(observed.reservationOwnerId())
        || original.reservationClaimFence() != observed.reservationClaimFence()
        || !original.issuanceOperationId().equals(observed.issuanceOperationId())
        || original.issuanceFence() != observed.issuanceFence()
        || !original.bundleReference().equals(observed.bundleReference())
        || !Arrays.equals(original.authorityEvidenceBundle(), observed.authorityEvidenceBundle())
        || !original
            .authorizationReferenceFingerprint()
            .equals(observed.authorizationReferenceFingerprint())
        || !Arrays.equals(
            original.encryptedResponseEnvelope(), observed.encryptedResponseEnvelope())
        || !original.issuedAt().equals(observed.issuedAt())
        || !original.referenceExpiresAt().equals(observed.referenceExpiresAt())
        || !original.responseEnvelopeExpiresAt().equals(observed.responseEnvelopeExpiresAt())) {
      throw denied("Immutable Account StartSession issuance changed during owner revalidation");
    }
  }

  private static void requireSameStoredRedemption(
      IssuanceRecord original, IssuanceRecord observed) {
    requireSameStoredIssuance(original, observed);
    if (original.status() != Status.REDEEMED
        || observed.status() != Status.REDEEMED
        || !Objects.equals(
            original.redemptionRedeemerWorkloadUri(), observed.redemptionRedeemerWorkloadUri())
        || !Objects.equals(original.redemptionOwnerAttemptId(), observed.redemptionOwnerAttemptId())
        || !Objects.equals(original.redemptionOwnerFence(), observed.redemptionOwnerFence())
        || !Objects.equals(original.redeemedAt(), observed.redeemedAt())
        || !constantTimeEquals(
            original.redemptionReferenceFingerprint(), observed.redemptionReferenceFingerprint())
        || !Arrays.equals(
            original.redemptionAuthorityEvidenceBundle(),
            observed.redemptionAuthorityEvidenceBundle())) {
      throw denied("Original Account redemption changed during current-source revalidation");
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static Instant canonicalInstant(Object value) {
    if (!(value instanceof String text)) throw denied("Original response expiry must be text");
    Instant instant;
    try {
      instant = Instant.parse(text);
    } catch (RuntimeException malformed) {
      throw denied("Original response expiry is malformed");
    }
    if (!instant.toString().equals(text)) throw denied("Original response expiry is not canonical");
    return instant;
  }

  private static String requireReference(Object value) {
    if (!(value instanceof String text)
        || text.getBytes(StandardCharsets.US_ASCII).length > MAX_REFERENCE_TEXT_BYTES
        || !REFERENCE.matcher(text).matches()) {
      throw denied("Opaque StartSession authorization reference is malformed");
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(text);
    } catch (RuntimeException malformed) {
      throw denied("Opaque StartSession authorization reference is malformed");
    }
    try {
      if (decoded.length != REFERENCE_RANDOM_BYTES
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(text)) {
        throw denied("Opaque StartSession authorization reference is not canonical");
      }
    } finally {
      Arrays.fill(decoded, (byte) 0);
    }
    return text;
  }

  private static String requireFingerprint(Object value) {
    if (!(value instanceof String text)
        || !text.matches("arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}")) {
      throw denied("Account authorization-reference fingerprint is malformed");
    }
    return text;
  }

  private static boolean constantTimeEquals(String first, String second) {
    return second != null
        && MessageDigest.isEqual(
            first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
  }

  private static void requirePeer(String exactUri) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !exactUri.equals(peer.uri())) {
      throw denied("Exact authenticated internal workload identity is required");
    }
  }

  private static String exactPeer(String value, String expectedService) {
    return GrpcPeerIdentity.parseUri(value)
        .map(GrpcPeerIdentity::uri)
        .filter(value::equals)
        .filter(uri -> uri.endsWith("/sa/" + expectedService))
        .orElseThrow(
            () -> new IllegalArgumentException("Exact configured workload URI is required"));
  }

  private static String sameNamespacePeer(String exactPeerUri, String service) {
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri(exactPeerUri)
            .orElseThrow(
                () -> new IllegalArgumentException("Exact configured workload URI is required"));
    return exactPeer("spiffe://firemud/ns/" + peer.namespace() + "/sa/" + service, service);
  }

  private static long policyMillis(Duration value, String field, long maximum) {
    Objects.requireNonNull(value, field + " policy is required");
    long millis;
    try {
      millis = value.toMillis();
    } catch (ArithmeticException outOfRange) {
      throw new IllegalArgumentException(field + " is outside the supported range");
    }
    if (millis <= 0 || millis > maximum || !Duration.ofMillis(millis).equals(value)) {
      throw new IllegalArgumentException(
          field + " must be an explicit bounded millisecond duration");
    }
    return millis;
  }

  private static Instant addMillis(Instant value, long millis) {
    try {
      return Instant.ofEpochMilli(Math.addExact(value.toEpochMilli(), millis));
    } catch (RuntimeException overflow) {
      throw new IllegalArgumentException(
          "Operator reference expiry is outside the supported range");
    }
  }

  private static void requireNonNilUuid(UUID value, String field) {
    if (value == null || new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical nonnil UUID");
    }
  }

  private static void requirePositiveFence(long value, String field) {
    if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
  }

  private static IllegalStateException denied(String reason) {
    return new IllegalStateException(reason);
  }

  private record IssuePreparation(
      Current current, IssuanceRecord existing, OwnerLinearization linearization) {
    private IssuePreparation {
      Objects.requireNonNull(current, "current Account actor is required");
      if ((existing == null) != (linearization != null)) {
        throw denied("Issue preparation must contain either an original record or its fence");
      }
    }
  }

  private record PreparedIssuance(
      AccountStartSessionOperatorAuthorityBundle bundle,
      AuthorizationResponse response,
      IssuanceCandidate candidate) {
    private PreparedIssuance {
      Objects.requireNonNull(bundle, "authority bundle is required");
      Objects.requireNonNull(response, "exact response is required");
      Objects.requireNonNull(candidate, "durable issuance candidate is required");
    }
  }

  private record IssueOutcome(boolean created, IssuanceRecord issuance) {
    private IssueOutcome {
      Objects.requireNonNull(issuance, "durable issuance result is required");
    }
  }

  public record IssueRequest(
      String controlUiJwt,
      byte[] canonicalTupleBytes,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID claimOwnerId,
      long claimFence) {
    public IssueRequest {
      canonicalTupleBytes = copy(canonicalTupleBytes);
    }

    @Override
    public byte[] canonicalTupleBytes() {
      return copy(canonicalTupleBytes);
    }

    @Override
    public String toString() {
      return "IssueRequest[control-ui JWT and tuple redacted]";
    }
  }

  public record RecoverRequest(
      byte[] canonicalTupleBytes,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID claimOwnerId,
      long claimFence) {
    public RecoverRequest {
      canonicalTupleBytes = copy(canonicalTupleBytes);
    }

    @Override
    public byte[] canonicalTupleBytes() {
      return copy(canonicalTupleBytes);
    }

    @Override
    public String toString() {
      return "RecoverRequest[tuple redacted]";
    }
  }

  public record RedeemRequest(
      byte[] canonicalTupleBytes,
      String operatorAuthorizationReference,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID ownerAttemptId,
      long ownerFence) {
    public RedeemRequest {
      canonicalTupleBytes = copy(canonicalTupleBytes);
    }

    @Override
    public byte[] canonicalTupleBytes() {
      return copy(canonicalTupleBytes);
    }

    @Override
    public String toString() {
      return "RedeemRequest[tuple, reference, fingerprint redacted]";
    }
  }

  /** The exact original tuple, fingerprint and owner bindings; contains no JWT or opaque ref. */
  public record ReadRedeemedOperationProjectionRequest(
      byte[] canonicalPreAuthorizationTuple,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      UUID ownerAttemptId,
      long ownerFence) {
    public ReadRedeemedOperationProjectionRequest {
      canonicalPreAuthorizationTuple = copy(canonicalPreAuthorizationTuple);
    }

    @Override
    public byte[] canonicalPreAuthorizationTuple() {
      return copy(canonicalPreAuthorizationTuple);
    }

    @Override
    public String toString() {
      return "ReadRedeemedOperationProjectionRequest[tuple and fingerprint redacted]";
    }
  }

  /** Immutable, credential-free Account projection of the original exact redeemed operation. */
  public record RedeemedOperationProjection(
      String controlPlaneRequestId,
      byte[] canonicalPreAuthorizationTuple,
      String mutationDigest,
      String authorizationReferenceFingerprint,
      UUID reservationOwnerId,
      long reservationClaimFence,
      String redeemerWorkloadUri,
      UUID ownerAttemptId,
      long ownerFence,
      Instant referenceExpiresAt,
      Instant redeemedAt,
      UUID issuanceOperationId,
      long issuanceFence,
      AccountStartSessionOperatorAuthorityBundle.BundleReference bundleReference,
      byte[] authorityEvidenceBundle) {
    public RedeemedOperationProjection {
      canonicalPreAuthorizationTuple = copy(canonicalPreAuthorizationTuple);
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
      Objects.requireNonNull(bundleReference, "original bundle reference is required");
    }

    @Override
    public byte[] canonicalPreAuthorizationTuple() {
      return copy(canonicalPreAuthorizationTuple);
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public String toString() {
      return "RedeemedOperationProjection[authority evidence redacted]";
    }
  }

  /** Exact response bytes are only stored in the encrypted owner envelope. */
  public record AuthorizationResponse(
      String operatorAuthorizationReference,
      String authorizationReferenceFingerprint,
      Instant expiresAt,
      byte[] authorityEvidenceBundle,
      AccountStartSessionOperatorAuthorityBundle.BundleReference bundleReference) {
    public AuthorizationResponse {
      requireReference(operatorAuthorizationReference);
      requireFingerprint(authorizationReferenceFingerprint);
      Objects.requireNonNull(expiresAt, "reference expiry is required");
      authorityEvidenceBundle = copy(authorityEvidenceBundle);
      Objects.requireNonNull(bundleReference, "source bundle reference is required");
    }

    @Override
    public byte[] authorityEvidenceBundle() {
      return copy(authorityEvidenceBundle);
    }

    @Override
    public String toString() {
      return "AuthorizationResponse[reference, bundle, and fingerprint redacted]";
    }
  }

  public static final class NotFoundException extends RuntimeException {
    public NotFoundException() {
      super("No original StartSession operator authorization exists");
    }
  }

  private static byte[] copy(byte[] value) {
    return value == null ? null : value.clone();
  }
}
