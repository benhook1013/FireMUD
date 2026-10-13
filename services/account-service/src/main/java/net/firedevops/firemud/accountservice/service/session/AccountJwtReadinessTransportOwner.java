package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.IssueReadinessProbesRequest;
import net.firedevops.firemud.account.v1.IssueReadinessProbesResponse;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.DeliveryClaim;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.Invocation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Account-owned transport orchestration for one-shot, non-authorizing JWT readiness probes. */
public final class AccountJwtReadinessTransportOwner {
  private static final int SCHEMA_VERSION = 1;
  private static final String VALIDATOR_ID = "account-service";
  private static final int MAX_COMPACT_TOKEN_BYTES = 16 * 1024;

  private final AccountJwtReadinessProbeRepository repository;
  private final AccountJwtReadinessProbeService probeProducer;
  private final AccountJwtReadinessValidationService validationService;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;
  private final AccountJwtReadinessReceiverInvocationPort receiverInvocationPort;

  public AccountJwtReadinessTransportOwner(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessProbeService probeProducer,
      AccountJwtReadinessValidationService validationService,
      PlatformTransactionManager transactionManager) {
    this(
        repository,
        probeProducer,
        validationService,
        writableTransaction(transactionManager),
        Clock.systemUTC(),
        AccountJwtReadinessReceiverInvocationPort.defaultDenied());
  }

  public AccountJwtReadinessTransportOwner(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessProbeService probeProducer,
      AccountJwtReadinessValidationService validationService,
      PlatformTransactionManager transactionManager,
      AccountJwtReadinessReceiverInvocationPort receiverInvocationPort) {
    this(
        repository,
        probeProducer,
        validationService,
        writableTransaction(transactionManager),
        Clock.systemUTC(),
        receiverInvocationPort);
  }

  AccountJwtReadinessTransportOwner(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessProbeService probeProducer,
      AccountJwtReadinessValidationService validationService,
      TransactionTemplate accountTransaction,
      Clock clock) {
    this(
        repository,
        probeProducer,
        validationService,
        accountTransaction,
        clock,
        AccountJwtReadinessReceiverInvocationPort.defaultDenied());
  }

  AccountJwtReadinessTransportOwner(
      AccountJwtReadinessProbeRepository repository,
      AccountJwtReadinessProbeService probeProducer,
      AccountJwtReadinessValidationService validationService,
      TransactionTemplate accountTransaction,
      Clock clock,
      AccountJwtReadinessReceiverInvocationPort receiverInvocationPort) {
    this.repository = Objects.requireNonNull(repository);
    this.probeProducer = Objects.requireNonNull(probeProducer);
    this.validationService = Objects.requireNonNull(validationService);
    this.accountTransaction = Objects.requireNonNull(accountTransaction);
    this.accountTransaction.setReadOnly(false);
    this.clock = Objects.requireNonNull(clock);
    this.receiverInvocationPort = Objects.requireNonNull(receiverInvocationPort);
  }

  /** Plans and delivers one exact readiness batch after durably claiming its only delivery. */
  public IssueReadinessProbesResponse issue(
      IssueReadinessProbesRequest request,
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller) {
    requireExactIssueRequest(request);
    Binding readinessBinding = validationService.requireAuthenticatedBinding(authenticatedCaller);
    var signerBinding = validationService.requireCurrentSignerBinding(readinessBinding);
    validationService.requireBindingsUnchanged(
        readinessBinding, signerBinding, authenticatedCaller);

    var binding = signerBinding.accountBinding();
    TrustFence trustFence = AccountJwtReadinessValidationService.trustFence(signerBinding);
    ReadinessProbePlan plan = probeProducer.planCurrent(binding, trustFence);
    if (plan.validatorInventoryComplete()) {
      if (plan.planVersion() != AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION) {
        throw new TransportUnavailableException();
      }
      // Do not sign, claim, or transiently deliver a complete-fleet plan until the real Pod-bound
      // receiver client is composed. The default implementation is explicitly unavailable.
      receiverInvocationPort.requireAvailable();
    }

    // This is deliberately a separate short SQL transaction. No signer or file work occurs while
    // the durable, irreversible one-shot claim is being committed.
    Instant claimedAt = clock.instant();
    DeliveryClaim claim =
        accountTransaction.execute(
            status ->
                repository.claimSingleDelivery(
                    binding,
                    trustFence,
                    plan,
                    readinessBinding,
                    authenticatedCaller.peer(),
                    claimedAt));
    if (claim == null) {
      throw new TransportUnavailableException();
    }

    validationService.requireBindingsUnchanged(
        readinessBinding, signerBinding, authenticatedCaller);

    Map<UUID, TransientProbe> transientProbes = new HashMap<>();
    List<ProbeEntry> issuedEntries =
        probeProducer.issueCurrentPlan(
            binding,
            trustFence,
            plan.operationId(),
            claim,
            (metadata, exactCompactJwt) -> {
              TransientProbe previous =
                  transientProbes.putIfAbsent(
                      metadata.jti(), new TransientProbe(metadata, compactJwt(exactCompactJwt)));
              if (previous != null) {
                throw new TransportUnavailableException();
              }
            });

    boolean perPodClosureComplete = false;
    if (plan.validatorInventoryComplete()) {
      invokeEveryExpectedPod(binding, trustFence, plan, issuedEntries, transientProbes);
      ReadinessProbePlan latest =
          probeProducer.readCurrentPlan(binding, trustFence, plan.operationId());
      perPodClosureComplete = exactPerPodClosure(plan, latest);
      if (!perPodClosureComplete) {
        throw new TransportUnavailableException();
      }
    }

    validationService.requireBindingsUnchanged(
        readinessBinding, signerBinding, authenticatedCaller);
    return response(plan, issuedEntries, transientProbes, perPodClosureComplete);
  }

  /** Records only the production verifier's exact, non-authorizing receipt. */
  public ValidateReadinessProbeResponse validate(
      ValidateReadinessProbeRequest request,
      AccountJwtReadinessTlsInterceptor.AuthenticatedCaller authenticatedCaller) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new AccountJwtReadinessValidationService.InvalidReadinessRequestException();
    }
    VerificationReceipt receipt = validationService.validate(request, authenticatedCaller);
    return ValidateReadinessProbeResponse.newBuilder()
        .setSchemaVersion(SCHEMA_VERSION)
        .setResult(ValidateReadinessProbeResponse.Result.NON_AUTHORIZING_VERIFICATION_RECORDED)
        .setReceiptVersion(receipt.receiptVersion())
        .setReceiptSha256(receipt.receiptSha256())
        .setObservedAtEpochSeconds(receipt.observedAtEpochSecond())
        .setValidatorId(VALIDATOR_ID)
        .setValidatorInstanceId(receipt.validatorInstanceId())
        .build();
  }

  private static void requireExactIssueRequest(IssueReadinessProbesRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()) {
      throw new AccountJwtReadinessValidationService.InvalidReadinessRequestException();
    }
  }

  private static IssueReadinessProbesResponse response(
      ReadinessProbePlan plan,
      List<ProbeEntry> issuedEntries,
      Map<UUID, TransientProbe> transientProbes,
      boolean perPodClosureComplete) {
    if (plan.validatorInventoryComplete() != perPodClosureComplete
        || issuedEntries.size() != plan.entries().size()
        || transientProbes.size() != plan.entries().size()) {
      throw new TransportUnavailableException();
    }
    Map<UUID, ProbeEntry> issuedByJti = new HashMap<>();
    for (ProbeEntry entry : issuedEntries) {
      if (issuedByJti.putIfAbsent(entry.jti(), entry) != null) {
        throw new TransportUnavailableException();
      }
    }

    var response =
        IssueReadinessProbesResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setRotationOperationId(plan.operationId().toString())
            .setPlanDigest(plan.planDigest())
            .setPlanVersion(plan.planVersion())
            .setPlannedAtEpochSeconds(plan.plannedAtEpochSecond())
            .setNotBeforeEpochSeconds(plan.notBeforeEpochSecond())
            .setExpiresAtEpochSeconds(plan.expiresAtEpochSecond())
            .setTargetGeneration(plan.targetGeneration())
            .setTargetKid(plan.targetKid())
            .setValidatorInventoryComplete(plan.validatorInventoryComplete());
    plan.expectedFence()
        .durableActive()
        .ifPresent(
            active ->
                response.setExpectedActive(
                    IssueReadinessProbesResponse.ActiveSignerFence.newBuilder()
                        .setGeneration(active.generation())
                        .setKid(active.kid())
                        .build()));

    for (ProbeEntry planned : plan.entries()) {
      ProbeEntry issued = issuedByJti.get(planned.jti());
      TransientProbe transientProbe = transientProbes.get(planned.jti());
      if (issued == null
          || transientProbe == null
          || issued.state() != ProbeState.ISSUED
          || !matchesPlanEntry(plan, planned, issued)
          || !matchesDelivery(plan, issued, transientProbe.metadata())
          || !issued
              .compactTokenSha256()
              .equals(java.util.Optional.of(transientProbe.metadata().compactTokenSha256()))) {
        throw new TransportUnavailableException();
      }
      response.addProbes(toResponseProbe(issued, transientProbe));
    }
    return response.build();
  }

  private void invokeEveryExpectedPod(
      net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository
              .Binding
          binding,
      TrustFence trustFence,
      ReadinessProbePlan plan,
      List<ProbeEntry> issuedEntries,
      Map<UUID, TransientProbe> transientProbes) {
    for (ProbeEntry entry : issuedEntries) {
      TransientProbe transientProbe = transientProbes.get(entry.jti());
      if (transientProbe == null || entry.state() != ProbeState.ISSUED) {
        throw new TransportUnavailableException();
      }
      List<ExpectedPod> targets = probeProducer.readCurrentExpectedPods(binding, trustFence, entry);
      if (targets.isEmpty()
          || targets.size() > AccountJwtReadinessProbeRepository.MAX_POD_RECEIPTS_PER_OPERATION) {
        throw new TransportUnavailableException();
      }
      for (ExpectedPod expectedPod : targets) {
        var target = expectedPod.target();
        target.requireRoutablePodIdentity();
        Invocation invocation =
            new Invocation(
                plan.operationId(),
                plan.operationDigest(),
                plan.planDigest(),
                plan.planVersion(),
                plan.expiresAtEpochSecond(),
                entry.validatorId(),
                entry.tokenProfile(),
                entry.audience(),
                entry.probeKind(),
                target.expectation(),
                entry.registryVersion(),
                entry.entryVersion(),
                entry.jti(),
                transientProbe.compactJwt(),
                transientProbe.metadata().compactTokenSha256(),
                entry.targetGeneration(),
                entry.targetKid(),
                entry.expectedActive().map(value -> value.generation()),
                entry.expectedActive().map(value -> value.kid()),
                entry.plannedIssuedAtEpochSecond(),
                entry.expiresAtEpochSecond(),
                target);
        AuthenticatedAcceptance acceptance = receiverInvocationPort.invoke(invocation);
        probeProducer.recordPodAcceptance(binding, trustFence, entry, expectedPod, acceptance);
      }
    }
  }

  private static boolean exactPerPodClosure(
      ReadinessProbePlan expected, ReadinessProbePlan actual) {
    return actual != null
        && actual.planVersion() == AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION
        && actual.validatorInventoryComplete()
        && actual.operationId().equals(expected.operationId())
        && actual.operationDigest().equals(expected.operationDigest())
        && actual.planDigest().equals(expected.planDigest())
        && actual.entries().size() == expected.entries().size()
        && actual.entries().stream()
            .allMatch(
                entry ->
                    entry.state() == ProbeState.VERIFIED
                        && entry
                            .verificationReceipt()
                            .filter(receipt -> receipt.receiptVersion() == 2)
                            .filter(
                                receipt ->
                                    receipt.podReceiptCount() > 0
                                        && receipt
                                            .podReceiptClosureSha256()
                                            .equals(receipt.receiptSha256()))
                            .isPresent());
  }

  private static boolean matchesPlanEntry(
      ReadinessProbePlan plan, ProbeEntry planned, ProbeEntry issued) {
    return issued.rotationOperationId().equals(plan.operationId())
        && issued.planDigest().equals(plan.planDigest())
        && issued.validatorId().equals(planned.validatorId())
        && issued.tokenProfile().equals(planned.tokenProfile())
        && issued.audience().equals(planned.audience())
        && issued.probeKind() == planned.probeKind()
        && issued.jti().equals(planned.jti())
        && issued.targetGeneration().equals(plan.targetGeneration())
        && issued.targetKid().equals(plan.targetKid())
        && issued.expectedActive().equals(planned.expectedActive())
        && issued.registryVersion() == planned.registryVersion()
        && issued.plannedIssuedAtEpochSecond() == planned.plannedIssuedAtEpochSecond()
        && issued.expiresAtEpochSecond() == planned.expiresAtEpochSecond();
  }

  private static boolean matchesDelivery(
      ReadinessProbePlan plan,
      ProbeEntry issued,
      AccountJwtReadinessProbeService.DeliveryMetadata metadata) {
    return metadata.operationId().equals(issued.rotationOperationId())
        && metadata.planDigest().equals(issued.planDigest())
        && metadata.planVersion() == plan.planVersion()
        && metadata.validatorId().equals(issued.validatorId())
        && metadata.tokenProfile().equals(issued.tokenProfile())
        && metadata.audience().equals(issued.audience())
        && metadata.probeKind() == issued.probeKind()
        && metadata.jti().equals(issued.jti())
        && metadata.targetGeneration().equals(issued.targetGeneration())
        && metadata.targetKid().equals(issued.targetKid())
        && metadata.registryVersion() == issued.registryVersion()
        && metadata.entryVersion() == issued.entryVersion()
        && metadata.issuedAtEpochSecond() == issued.plannedIssuedAtEpochSecond()
        && metadata.expiresAtEpochSecond() == issued.expiresAtEpochSecond()
        && metadata
            .expectedActiveGeneration()
            .equals(issued.expectedActive().map(value -> value.generation()))
        && metadata.expectedActiveKid().equals(issued.expectedActive().map(value -> value.kid()));
  }

  private static IssueReadinessProbesResponse.Probe toResponseProbe(
      ProbeEntry issued, TransientProbe transientProbe) {
    var builder =
        IssueReadinessProbesResponse.Probe.newBuilder()
            .setValidatorId(issued.validatorId())
            .setTokenProfile(issued.tokenProfile())
            .setAudience(issued.audience())
            .setProbeKind(issued.probeKind().name())
            .setJti(issued.jti().toString())
            .setTargetGeneration(issued.targetGeneration())
            .setTargetKid(issued.targetKid())
            .setRegistryVersion(issued.registryVersion())
            .setEntryVersion(issued.entryVersion())
            .setIssuedAtEpochSeconds(issued.plannedIssuedAtEpochSecond())
            .setExpiresAtEpochSeconds(issued.expiresAtEpochSecond())
            .setCompactTokenSha256(transientProbe.metadata().compactTokenSha256())
            .setCompactJwt(transientProbe.compactJwt());
    return builder.build();
  }

  private static String compactJwt(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMPACT_TOKEN_BYTES) {
      throw new TransportUnavailableException();
    }
    for (byte value : bytes) {
      if (value <= 0 || value > 0x7f) {
        throw new TransportUnavailableException();
      }
    }
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  private static TransactionTemplate writableTransaction(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(false);
    return transaction;
  }

  private record TransientProbe(
      AccountJwtReadinessProbeService.DeliveryMetadata metadata, String compactJwt) {}

  public static final class TransportUnavailableException extends IllegalStateException {
    public TransportUnavailableException() {
      super("Account JWT readiness transport is unavailable");
    }
  }
}
