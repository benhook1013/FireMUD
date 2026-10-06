package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountJwtSignerMaterializationServiceGrpc;
import net.firedevops.firemud.account.v1.ActiveSigner;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.account.v1.RecordSecretObservationRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationPhase;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationRequest;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PreparedGenerationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PublicKeyIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtSignerMaterializerTlsInterceptor;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Narrow Account-owned operation handoff to the interim mounted-key materializer.
 *
 * <p>The exact peer can read Account-selected fixed-Secret generation and PREPARED promotion
 * operations, report readbacks, and submit public key identities. It cannot select an operation,
 * environment, resource, generation, or {@code kid}; submit private material; publish JWKS; commit
 * the Account signer lifecycle; sign tokens; or establish readiness.
 */
@GrpcService(
    interceptorNames = "accountJwtSignerMaterializerTlsInterceptor",
    blendWithGlobalInterceptors = false)
public class AccountJwtSignerMaterializationService
    extends AccountJwtSignerMaterializationServiceGrpc
        .AccountJwtSignerMaterializationServiceImplBase {
  private final AccountJwtSignerDesiredStateRepository repository;
  private final AccountJwtReadinessProbeRepository readinessRepository;
  private final AccountJwtSignerMaterializerTrustBinding trustBindingProvider;
  private final TransactionTemplate accountTransaction;

  public AccountJwtSignerMaterializationService(
      AccountJwtSignerDesiredStateRepository repository,
      AccountJwtReadinessProbeRepository readinessRepository,
      AccountJwtSignerMaterializerTrustBinding trustBindingProvider,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.readinessRepository = readinessRepository;
    this.trustBindingProvider = trustBindingProvider;
    this.accountTransaction = new TransactionTemplate(transactionManager);
    this.accountTransaction.setReadOnly(false);
  }

  @Override
  public void getCurrentGenerationRequest(
      GetCurrentGenerationRequestRequest request,
      StreamObserver<GetCurrentGenerationRequestResponse> responseObserver) {
    try {
      validateRequest(request.getSchemaVersion(), request.getUnknownFields().asMap().isEmpty());
      Binding binding = requireCurrentAuthenticatedBinding();
      GenerationRequest current =
          accountTransaction.execute(
              status -> {
                GenerationRequest requested =
                    repository.ensureCurrentGenerationRequest(
                        binding.accountBinding(), trustFence(binding));
                requireUnchangedTrustBinding(binding);
                return requested;
              });
      if (current == null) {
        throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
            "JWT signer generation request is unavailable");
      }
      requireUnchangedTrustBinding(binding);
      responseObserver.onNext(toRequestResponse(current));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(statusFor(ex).asRuntimeException());
    }
  }

  @Override
  public void recordSecretObservation(
      RecordSecretObservationRequest request,
      StreamObserver<GetCurrentGenerationRequestResponse> responseObserver) {
    try {
      validateRequest(request.getSchemaVersion(), request.getUnknownFields().asMap().isEmpty());
      UUID operationId = parseOperationId(request.getOperationId());
      Binding binding = requireCurrentAuthenticatedBinding();
      GenerationRequest current =
          accountTransaction.execute(
              status -> {
                GenerationRequest observed =
                    repository.recordSecretObservation(
                        binding.accountBinding(),
                        trustFence(binding),
                        operationId,
                        request.getOperationDigest(),
                        request.getSecretUid(),
                        request.getObservedResourceVersion());
                requireUnchangedTrustBinding(binding);
                return observed;
              });
      if (current == null) {
        throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
            "JWT signer Secret observation is unavailable");
      }
      requireUnchangedTrustBinding(binding);
      responseObserver.onNext(toRequestResponse(current));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(statusFor(ex).asRuntimeException());
    }
  }

  @Override
  public void recordGenerationResult(
      RecordGenerationResultRequest request,
      StreamObserver<RecordGenerationResultResponse> responseObserver) {
    try {
      validateRequest(request.getSchemaVersion(), request.getUnknownFields().asMap().isEmpty());
      UUID operationId = parseOperationId(request.getOperationId());
      Binding binding = requireCurrentAuthenticatedBinding();
      GenerationResult result =
          accountTransaction.execute(
              status -> {
                GenerationResult persisted =
                    repository.recordGenerationResult(
                        binding.accountBinding(),
                        trustFence(binding),
                        operationId,
                        request.getGenerationRequestDigest(),
                        request.getSecretUid(),
                        request.getExpectedPriorResourceVersion(),
                        request.getObservedResourceVersion(),
                        request.getPublicJwkJson());
                requireUnchangedTrustBinding(binding);
                return persisted;
              });
      if (result == null) {
        throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
            "JWT signer generation receipt is unavailable");
      }
      requireUnchangedTrustBinding(binding);
      responseObserver.onNext(toResultResponse(result));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(statusFor(ex).asRuntimeException());
    }
  }

  /** Returns only the exact Account PREPARED operation for fixed-Secret reconciliation. */
  @Override
  public void getCurrentPromotionRequest(
      GetCurrentPromotionRequestRequest request,
      StreamObserver<GetCurrentPromotionRequestResponse> responseObserver) {
    try {
      validateRequest(request.getSchemaVersion(), request.getUnknownFields().asMap().isEmpty());
      Binding binding = requireCurrentAuthenticatedBinding();
      PreparedGenerationEvidence prepared =
          accountTransaction.execute(
              status -> {
                PreparedGenerationEvidence current =
                    repository.readPreparedGenerationForRecovery(
                        binding.accountBinding(), trustFence(binding));
                boolean completeProof =
                    readinessRepository
                        .readPromotionProof(
                            binding.accountBinding(),
                            trustFence(binding),
                            current.promotion().generationOperationId())
                        .isPresent();
                if (!completeProof) {
                  throw new AccountJwtSignerDesiredStateRepository
                      .PromotionPrerequisitesIncompleteException(
                      "Complete Account readiness proof is unavailable");
                }
                PreparedGenerationEvidence evidence =
                    repository.readAndMarkPreparedPromotionDispatched(
                        binding.accountBinding(), trustFence(binding));
                requireUnchangedTrustBinding(binding);
                return evidence;
              });
      if (prepared == null) {
        throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
            "JWT signer promotion request is unavailable");
      }
      requireUnchangedTrustBinding(binding);
      responseObserver.onNext(toPromotionRequestResponse(prepared));
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(statusFor(ex).asRuntimeException());
    }
  }

  /** Records Account's public-identity-only readback of the exact fixed-Secret CAS. */
  @Override
  public void recordPrivatePromotionResult(
      RecordPrivatePromotionResultRequest request,
      StreamObserver<RecordPrivatePromotionResultResponse> responseObserver) {
    try {
      validateRequest(request.getSchemaVersion(), request.getUnknownFields().asMap().isEmpty());
      if (request.hasCurrent() == false
          || request.getCurrent().getUnknownFields().asMap().isEmpty() == false
          || (request.hasPrevious()
              && request.getPrevious().getUnknownFields().asMap().isEmpty() == false)) {
        throw new IllegalArgumentException("Promotion result identity is malformed");
      }
      UUID promotionOperationId = parseOperationId(request.getPromotionOperationId());
      UUID generationOperationId = parseOperationId(request.getGenerationOperationId());
      Binding binding = requireCurrentAuthenticatedBinding();
      PrivatePromotionReceipt receipt =
          accountTransaction.execute(
              status -> {
                PrivatePromotionReceipt persisted =
                    repository.recordPrivatePromotionResult(
                        binding.accountBinding(),
                        trustFence(binding),
                        new PrivatePromotionObservation(
                            promotionOperationId,
                            request.getPromotionRequestDigest(),
                            generationOperationId,
                            request.getGenerationOperationDigest(),
                            request.getSecretUid(),
                            request.getExpectedPriorResourceVersion(),
                            request.getObservedResourceVersion(),
                            publicIdentity(request.getCurrent()),
                            request.hasPrevious()
                                ? java.util.Optional.of(publicIdentity(request.getPrevious()))
                                : java.util.Optional.empty(),
                            request.getResultingSlotsList()));
                requireUnchangedTrustBinding(binding);
                return persisted;
              });
      if (receipt == null) {
        throw new AccountJwtSignerDesiredStateRepository.StorageUnavailableException(
            "JWT signer private promotion receipt is unavailable");
      }
      requireUnchangedTrustBinding(binding);
      responseObserver.onNext(
          RecordPrivatePromotionResultResponse.newBuilder()
              .setSchemaVersion(1)
              .setPromotionOperationId(receipt.promotionOperationId().toString())
              .setGenerationOperationId(receipt.generationOperationId().toString())
              .setObservedResourceVersion(receipt.observedResourceVersion())
              .setPublicReceiptDigest(receipt.receiptDigest())
              .build());
      responseObserver.onCompleted();
    } catch (RuntimeException ex) {
      responseObserver.onError(statusFor(ex).asRuntimeException());
    }
  }

  private Binding requireCurrentAuthenticatedBinding() {
    Binding authenticated = AccountJwtSignerMaterializerTlsInterceptor.authenticatedBinding();
    Binding current = trustBindingProvider.current().orElse(null);
    if (authenticated == null || current == null || !authenticated.equals(current)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Authenticated JWT materializer workload is required")
          .asRuntimeException();
    }
    return current;
  }

  private void requireUnchangedTrustBinding(Binding expected) {
    if (trustBindingProvider.current().filter(expected::equals).isEmpty()) {
      throw Status.PERMISSION_DENIED
          .withDescription("JWT materializer trust binding changed during the request")
          .asRuntimeException();
    }
  }

  private static TrustFence trustFence(Binding binding) {
    return new TrustFence(
        binding.expectedClusterIncarnationUid(),
        binding.expectedNamespaceUid(),
        binding.bindingDigest(),
        binding.configRevision());
  }

  private static GetCurrentGenerationRequestResponse toRequestResponse(GenerationRequest request) {
    GetCurrentGenerationRequestResponse.Builder response =
        GetCurrentGenerationRequestResponse.newBuilder()
            .setSchemaVersion(1)
            .setPhase(toWirePhase(request.phase()))
            .setOperationId(request.operationId().toString())
            .setOperationDigest(request.operationDigest())
            .setGenerationRequestDigest(request.generationRequestDigest())
            .setDesiredStateVersion(Long.toString(request.desiredStateVersion()))
            .setEnvironmentId(request.binding().environmentId())
            .setClusterId(request.binding().clusterId())
            .setNamespace(request.binding().namespace())
            .setExpectedClusterIncarnationUid(request.trustFence().expectedClusterIncarnationUid())
            .setExpectedNamespaceUid(request.trustFence().expectedNamespaceUid())
            .setTrustBindingDigest(request.trustFence().bindingDigest())
            .setTrustConfigRevision(request.trustFence().configRevision())
            .setPrivateSecretName(request.privateSecretName())
            .setTargetGeneration(request.targetGeneration())
            .setTargetKid(request.targetKid())
            .setTargetAlgorithm(request.targetAlgorithm())
            .setOperationAction(request.operationAction())
            .addAllAllowedPrivateSlots(request.allowedPrivateSlots())
            .setSecretUid(request.secretUid())
            .setExpectedSecretResourceVersion(request.expectedSecretResourceVersion())
            .setGenerationReceiptDigest(request.generationReceiptDigest())
            .setPublicKeyFingerprint(request.publicKeyFingerprint())
            .setPublicJwkJson(request.publicJwkJson())
            .setObservedSecretResourceVersion(request.observedSecretResourceVersion());
    request.expectedActive().ifPresent(active -> response.setExpectedActive(toWireActive(active)));
    request
        .expectedPublishedActive()
        .ifPresent(active -> response.setExpectedPublishedActive(toWireActive(active)));
    return response.build();
  }

  private static ActiveSigner toWireActive(
      AccountJwtSignerDesiredStateRepository.ActiveSigner active) {
    return ActiveSigner.newBuilder()
        .setGeneration(active.generation())
        .setKid(active.kid())
        .build();
  }

  private static GetCurrentGenerationRequestResponse.Phase toWirePhase(GenerationPhase phase) {
    return switch (phase) {
      case OBSERVE_PRIVATE_SECRET ->
          GetCurrentGenerationRequestResponse.Phase.OBSERVE_PRIVATE_SECRET;
      case GENERATE_PENDING -> GetCurrentGenerationRequestResponse.Phase.GENERATE_PENDING;
      case GENERATION_RECORDED -> GetCurrentGenerationRequestResponse.Phase.GENERATION_RECORDED;
    };
  }

  private static RecordGenerationResultResponse toResultResponse(GenerationResult result) {
    return RecordGenerationResultResponse.newBuilder()
        .setSchemaVersion(1)
        .setOperationId(result.operationId().toString())
        .setOperationDigest(result.operationDigest())
        .setGenerationRequestDigest(result.generationRequestDigest())
        .setGenerationReceiptDigest(result.receiptDigest())
        .setDesiredStateVersion(Long.toString(result.desiredStateVersion()))
        .setEnvironmentId(result.binding().environmentId())
        .setClusterId(result.binding().clusterId())
        .setNamespace(result.binding().namespace())
        .setExpectedClusterIncarnationUid(result.trustFence().expectedClusterIncarnationUid())
        .setExpectedNamespaceUid(result.trustFence().expectedNamespaceUid())
        .setTrustBindingDigest(result.trustFence().bindingDigest())
        .setTrustConfigRevision(result.trustFence().configRevision())
        .setPrivateSecretName(result.privateSecretName())
        .setSecretUid(result.secretUid())
        .setExpectedPriorResourceVersion(result.expectedPriorResourceVersion())
        .setObservedResourceVersion(result.observedResourceVersion())
        .setTargetGeneration(result.targetGeneration())
        .setTargetKid(result.targetKid())
        .setTargetAlgorithm(result.targetAlgorithm())
        .setPublicKeyFingerprint(result.publicKeyFingerprint())
        .setPublicJwkJson(result.publicJwkJson())
        .build();
  }

  private static GetCurrentPromotionRequestResponse toPromotionRequestResponse(
      PreparedGenerationEvidence prepared) {
    var promotion = prepared.promotion();
    var generation = prepared.generationResult();
    GetCurrentPromotionRequestResponse.Builder response =
        GetCurrentPromotionRequestResponse.newBuilder()
            .setSchemaVersion(1)
            .setPromotionOperationId(promotion.operationId().toString())
            .setPromotionRequestDigest(promotion.requestDigest())
            .setGenerationOperationId(generation.operationId().toString())
            .setGenerationOperationDigest(generation.operationDigest())
            .setGenerationReceiptDigest(generation.receiptDigest())
            .setEnvironmentId(promotion.binding().environmentId())
            .setClusterId(promotion.binding().clusterId())
            .setNamespace(promotion.binding().namespace())
            .setExpectedClusterIncarnationUid(
                promotion.trustFence().expectedClusterIncarnationUid())
            .setExpectedNamespaceUid(promotion.trustFence().expectedNamespaceUid())
            .setTrustBindingDigest(promotion.trustFence().bindingDigest())
            .setTrustConfigRevision(promotion.trustFence().configRevision())
            .setPrivateSecretName(AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME)
            .setTargetGeneration(promotion.targetGeneration())
            .setTargetKid(promotion.targetKid())
            .setTargetAlgorithm(promotion.targetAlgorithm())
            .setTargetPublicKeyFingerprint(promotion.targetPublicKeyFingerprint())
            .setExpectedSecretUid(promotion.secretUid())
            .setExpectedSecretResourceVersion(promotion.expectedPrivateResourceVersion())
            .setOperationAction("PROMOTE_PENDING")
            .addAllowedPrivateSlots("current.key")
            .addAllowedPrivateSlots("pending.key")
            .addAllowedPrivateSlots("previous.key");
    promotion
        .expectedPreviousActive()
        .ifPresent(
            previous -> {
              response.setExpectedPreviousGeneration(previous.generation());
              response.setExpectedPreviousKid(previous.kid());
              response.setExpectedPreviousPublicKeyFingerprint(
                  promotion.expectedPreviousPublicKeyFingerprint().orElseThrow());
            });
    return response.build();
  }

  private static PublicKeyIdentity publicIdentity(PromotionPublicKeyIdentity identity) {
    return new PublicKeyIdentity(
        identity.getGeneration(), identity.getKid(), identity.getPublicKeyFingerprint());
  }

  private static void validateRequest(int schemaVersion, boolean unknownFieldsEmpty) {
    if (schemaVersion != 1 || !unknownFieldsEmpty) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Exact JWT materialization request schema is required")
          .asRuntimeException();
    }
  }

  private static UUID parseOperationId(String value) {
    try {
      UUID operationId = UUID.fromString(value);
      if (operationId.version() != 4 || !operationId.toString().equals(value)) {
        throw new IllegalArgumentException("UUIDv4 required");
      }
      return operationId;
    } catch (RuntimeException ex) {
      throw Status.INVALID_ARGUMENT
          .withDescription("JWT signer operation ID must be canonical UUIDv4")
          .asRuntimeException();
    }
  }

  private static Status statusFor(RuntimeException exception) {
    if (exception instanceof StatusRuntimeException statusException) {
      return statusException.getStatus();
    }
    if (exception instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription("JWT materialization request is malformed");
    }
    if (exception instanceof AccountJwtSignerDesiredStateRepository.IdempotencyConflictException) {
      return Status.ALREADY_EXISTS.withDescription(
          "JWT materialization operation or public result conflicts with retained Account evidence");
    }
    if (exception instanceof AccountJwtSignerDesiredStateRepository.NoPreparedPromotionException) {
      return Status.NOT_FOUND.withDescription("No Account-authorized signer promotion is pending");
    }
    if (exception instanceof AccountJwtSignerDesiredStateRepository.StorageUnavailableException
        || exception instanceof DataAccessResourceFailureException
        || hasConnectionFailure(exception)) {
      return Status.UNAVAILABLE.withDescription(
          "Account JWT materialization storage is unavailable");
    }
    if (exception instanceof AccountJwtSignerDesiredStateRepository.MissingDesiredStateException
        || exception
            instanceof AccountJwtSignerDesiredStateRepository.MissingGenerationOperationException
        || exception
            instanceof AccountJwtSignerDesiredStateRepository.MissingGenerationObservationException
        || exception
            instanceof AccountJwtSignerDesiredStateRepository.StaleGenerationOperationException
        || exception instanceof AccountJwtSignerDesiredStateRepository.BindingMismatchException
        || exception instanceof AccountJwtSignerDesiredStateRepository.VersionConflictException
        || exception instanceof AccountJwtSignerDesiredStateRepository.QuarantinedStateException
        || exception
            instanceof
            AccountJwtSignerDesiredStateRepository.PromotionPrerequisitesIncompleteException) {
      return Status.FAILED_PRECONDITION.withDescription(
          "No exact current Account JWT signer generation phase is available");
    }
    if (exception instanceof DataAccessException) {
      return Status.INTERNAL.withDescription(
          "Account JWT generation evidence could not be recorded");
    }
    return Status.INTERNAL.withDescription("Account JWT materialization could not be completed");
  }

  private static boolean hasConnectionFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException) {
        String state = sqlException.getSQLState();
        if (state != null && state.startsWith("08")) {
          return true;
        }
      }
    }
    return false;
  }
}
