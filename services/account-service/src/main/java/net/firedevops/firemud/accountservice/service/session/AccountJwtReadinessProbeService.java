package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.DeliveryClaim;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ExpectedIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ReadinessProbeSigningSpec;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.SignedProbeDigest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dormant, owner-internal producer for durable Account readiness-probe plans and constrained
 * pending-key signatures.
 *
 * <p>This class is intentionally not a Spring component. Its per-invocation callback receives the
 * exact compact probe only for the duration of the call; this producer persists only its hash. The
 * caller must supply the already-persisted one-shot delivery claim. The separate readiness route
 * may record an authenticated non-authorizing observation, but neither path can promote a signer.
 */
public final class AccountJwtReadinessProbeService {
  private static final Path PRIVATE_MOUNT = privateSignerMount();

  private static final Path PRIVATE_PENDING_BUNDLE = Path.of("pending.key");

  private static final Path PUBLIC_MOUNT = publicJwksMount();

  private static final Path PUBLIC_JWKS = Path.of("jwks.json");

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The interim private signer projection is intentionally fixed to Account's protected read-only mount.")
  private static Path privateSignerMount() {
    return Path.of("/var/run/secrets/firemud/jwt");
  }

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The public JWKS projection is intentionally fixed to Account's read-only validator-consumed mount.")
  private static Path publicJwksMount() {
    return Path.of("/var/run/secrets/firemud/jwks");
  }

  private final AccountJwtReadinessProbeRepository repository;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;
  private final AccountJwtValidatorInventorySource inventorySource;
  private final Path privateMount;
  private final Path privateBundle;
  private final Path publicMount;
  private final Path publicJwks;

  public AccountJwtReadinessProbeService(
      AccountJwtReadinessProbeRepository repository,
      TransactionTemplate accountTransaction,
      Clock clock) {
    this(
        repository,
        accountTransaction,
        clock,
        PRIVATE_MOUNT,
        PRIVATE_PENDING_BUNDLE,
        PUBLIC_MOUNT,
        PUBLIC_JWKS);
  }

  AccountJwtReadinessProbeService(
      AccountJwtReadinessProbeRepository repository,
      TransactionTemplate accountTransaction,
      Clock clock,
      Path privateMount,
      Path privateBundle,
      Path publicMount,
      Path publicJwks) {
    this(
        repository,
        accountTransaction,
        clock,
        privateMount,
        privateBundle,
        publicMount,
        publicJwks,
        null);
  }

  public AccountJwtReadinessProbeService(
      AccountJwtReadinessProbeRepository repository,
      TransactionTemplate accountTransaction,
      Clock clock,
      AccountJwtValidatorInventorySource inventorySource) {
    this(
        repository,
        accountTransaction,
        clock,
        PRIVATE_MOUNT,
        PRIVATE_PENDING_BUNDLE,
        PUBLIC_MOUNT,
        PUBLIC_JWKS,
        Objects.requireNonNull(inventorySource));
  }

  private AccountJwtReadinessProbeService(
      AccountJwtReadinessProbeRepository repository,
      TransactionTemplate accountTransaction,
      Clock clock,
      Path privateMount,
      Path privateBundle,
      Path publicMount,
      Path publicJwks,
      AccountJwtValidatorInventorySource inventorySource) {
    this.repository = Objects.requireNonNull(repository);
    this.accountTransaction = Objects.requireNonNull(accountTransaction);
    this.clock = Objects.requireNonNull(clock);
    this.privateMount = Objects.requireNonNull(privateMount);
    this.privateBundle = Objects.requireNonNull(privateBundle);
    this.publicMount = Objects.requireNonNull(publicMount);
    this.publicJwks = Objects.requireNonNull(publicJwks);
    this.inventorySource = inventorySource;
  }

  /** Plans the current Account-selected generation operation; no signing occurs in this method. */
  public ReadinessProbePlan planCurrent(Binding binding, TrustFence trust) {
    if (inventorySource == null) {
      return inTransaction(
          () -> repository.planCurrent(binding, trust, Instant.ofEpochSecond(nowEpochSecond())));
    }
    ObservationContext observationContext =
        inTransaction(() -> repository.readCurrentInventoryObservationContext(binding, trust));
    AccountJwtValidatorInventorySource.InventorySnapshot snapshot =
        inventorySource.observe(observationContext);
    return inTransaction(
        () ->
            repository.planCurrent(
                binding, trust, Instant.ofEpochSecond(nowEpochSecond()), snapshot));
  }

  /** Rereads protected live inventory before opening the exact receiver target-read transaction. */
  public List<ExpectedPod> readCurrentExpectedPods(
      Binding binding, TrustFence trust, ProbeEntry entry) {
    AccountJwtValidatorInventorySource.InventorySnapshot snapshot =
        observeCurrentInventory(binding, trust, entry.rotationOperationId());
    return inTransaction(() -> repository.readCurrentExpectedPods(binding, trust, entry, snapshot));
  }

  /**
   * Reads one exact V2 receiver owner row after observing protected Kubernetes inventory outside
   * the short SQL transaction.
   */
  public OwnerProbeEvidence readCurrentProbeOwner(
      Binding binding,
      TrustFence trust,
      AccountJwtReadinessProbeOwnerSelector selector,
      String authenticatedWorkloadNamespace) {
    AccountJwtValidatorInventorySource.InventorySnapshot snapshot =
        observeCurrentInventory(binding, trust, selector.rotationOperationId());
    if (!snapshot.namespace().equals(authenticatedWorkloadNamespace)) {
      throw new AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException();
    }
    return inTransaction(
        () ->
            repository.readCurrentProbeOwner(
                binding, trust, selector, snapshot, authenticatedWorkloadNamespace));
  }

  /** Rereads protected live inventory before committing one immutable per-Pod receipt. */
  public ProbeEntry recordPodAcceptance(
      Binding binding,
      TrustFence trust,
      ProbeEntry entry,
      ExpectedPod expectedPod,
      AuthenticatedAcceptance acceptance) {
    AccountJwtValidatorInventorySource.InventorySnapshot snapshot =
        observeCurrentInventory(binding, trust, entry.rotationOperationId());
    return inTransaction(
        () ->
            repository.recordPodAcceptance(
                binding, trust, entry, expectedPod, acceptance, snapshot));
  }

  /**
   * Fresh owner observation for promotion callers; never performs I/O while SQL rows are locked.
   */
  public AccountJwtValidatorInventorySource.InventorySnapshot observeCurrentInventory() {
    if (inventorySource == null) {
      throw new AccountJwtValidatorInventorySource.InventoryUnavailableException();
    }
    return inventorySource.observe();
  }

  /** Reads only an owner-derived current V2 operation context for the Pod-local receiver proof. */
  ObservationContext readCurrentInventoryObservationContext(
      Binding binding, TrustFence trust, UUID operationId) {
    return inTransaction(
        () -> repository.readCurrentInventoryObservationContext(binding, trust, operationId));
  }

  private AccountJwtValidatorInventorySource.InventorySnapshot observeCurrentInventory(
      Binding binding, TrustFence trust, UUID operationId) {
    if (inventorySource == null) {
      throw new AccountJwtValidatorInventorySource.InventoryUnavailableException();
    }
    ObservationContext observationContext =
        readCurrentInventoryObservationContext(binding, trust, operationId);
    return inventorySource.observe(observationContext);
  }

  /** Reads the exact durable operation and latest receipt state without creating evidence. */
  ReadinessProbePlan readCurrentPlan(Binding binding, TrustFence trust, UUID operationId) {
    return inTransaction(() -> repository.readCurrentPlan(binding, trust, operationId));
  }

  /**
   * Delivers each exact current planned probe once. The compact-token hash and ISSUED transition
   * are durably read back before transient delivery, so an authenticated validator can consume the
   * current entry synchronously. A persisted signing-attempt hash without ISSUED is ambiguous and
   * causes same-operation quarantine rather than token remint or replay.
   */
  List<ProbeEntry> issueCurrentPlan(
      Binding binding,
      TrustFence trust,
      UUID operationId,
      DeliveryClaim claim,
      OwnerInternalProbeDelivery delivery) {
    ReadinessProbePlan plan =
        inTransaction(() -> repository.readCurrentPlan(binding, trust, operationId));
    if (nowEpochSecond() < plan.notBeforeEpochSecond()) {
      throw new AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException();
    }
    if (nowEpochSecond() >= plan.expiresAtEpochSecond()) {
      return expireAndClean(binding, trust, operationId);
    }
    Objects.requireNonNull(claim, "Persisted one-shot delivery claim is required");
    Objects.requireNonNull(delivery, "Per-invocation internal delivery is required");
    DeliveryClaim persistedClaim =
        inTransaction(
            () ->
                repository.requireCurrentDeliveryClaim(
                    binding, trust, plan, claim, Instant.ofEpochSecond(nowEpochSecond())));
    if (persistedClaim == null || !persistedClaim.equals(claim)) {
      throw new AccountJwtReadinessProbeRepository.DeliveryAlreadyClaimedException();
    }
    List<ProbeEntry> output = new ArrayList<>();
    for (ProbeEntry planned : plan.entries()) {
      ProbeEntry current = currentEntry(binding, trust, operationId, planned);
      if (current.state() == ProbeState.ISSUED) {
        output.add(current);
        continue;
      }
      if (current.state() == ProbeState.PLANNED && current.compactTokenSha256().isPresent()) {
        output.addAll(quarantineCurrent(binding, trust, operationId));
        break;
      }
      if (current.state() != ProbeState.PLANNED) {
        output.add(current);
        continue;
      }

      long now = nowEpochSecond();
      if (now < current.plannedIssuedAtEpochSecond()) {
        throw new AccountJwtReadinessProbeRepository.CacheAgeNotElapsedException();
      }
      if (now >= current.expiresAtEpochSecond()) {
        output.addAll(expireAndClean(binding, trust, operationId));
        break;
      }

      ExpectedIdentity identity =
          new ExpectedIdentity(
              binding.environmentId(),
              binding.clusterId(),
              binding.namespace(),
              operationId.toString(),
              current.targetGeneration(),
              current.targetKid(),
              plan.targetPublicKeyFingerprint());
      ReadinessProbeSigningSpec spec =
          new ReadinessProbeSigningSpec(
              current.validatorId(),
              current.probeKind(),
              current.tokenProfile(),
              current.audience(),
              current.jti(),
              current.targetGeneration(),
              current.targetKid(),
              current.plannedIssuedAtEpochSecond(),
              current.expiresAtEpochSecond());

      SignedProbeDigest signed;
      try {
        signed =
            AccountMountedJwtSignerBundle.signReadinessProbeDigest(
                privateMount,
                privateBundle,
                publicMount,
                publicJwks,
                identity,
                spec,
                (digest, exactCompactJwt) -> {
                  try {
                    pinBeforeDelivery(binding, trust, digest);
                    ProbeEntry issued =
                        inTransaction(
                            () ->
                                repository.recordIssued(
                                    binding,
                                    trust,
                                    digest,
                                    Instant.ofEpochSecond(nowEpochSecond())));
                    DeliveryMetadata metadata = DeliveryMetadata.from(plan, issued, digest);
                    delivery.deliver(metadata, exactCompactJwt);
                    if (!sha256(exactCompactJwt).equals(metadata.compactTokenSha256())) {
                      throw new IllegalStateException(
                          "Transient readiness probe bytes changed in delivery");
                    }
                  } catch (RuntimeException ignored) {
                    abortAfterDeliveryFailure(binding, trust, operationId);
                    throw new DeliveryQuarantinedException();
                  }
                });
      } catch (DeliveryQuarantinedException ex) {
        throw ex;
      }
      try {
        ProbeEntry delivered = currentEntry(binding, trust, operationId, current);
        if (!exactSignedReadback(plan, current, delivered, signed)
            || (delivered.state() != ProbeState.ISSUED
                && delivered.state() != ProbeState.VERIFIED)) {
          throw new IllegalStateException("Readiness delivery result did not read back exactly");
        }
        output.add(delivered);
      } catch (RuntimeException ignored) {
        abortAfterDeliveryFailure(binding, trust, operationId);
        throw new DeliveryQuarantinedException();
      }
    }
    return List.copyOf(output);
  }

  /** Aborts only the named current operation and retains hashes/evidence. */
  public List<ProbeEntry> abortCurrentPlan(Binding binding, TrustFence trust, UUID operationId) {
    return quarantineCurrent(binding, trust, operationId);
  }

  /** Expires due entries and performs bounded cleanup for only the named operation. */
  public List<ProbeEntry> expireAndClean(Binding binding, TrustFence trust, UUID operationId) {
    return inTransaction(
        () -> {
          repository.expireCurrent(
              binding, trust, operationId, Instant.ofEpochSecond(nowEpochSecond()));
          return repository.cleanTerminalEntries(
              operationId, AccountJwtReadinessProbeRepository.MAX_ENTRIES_PER_OPERATION);
        });
  }

  /** Bounded terminal cleanup never scans outside this explicit operation ID. */
  public List<ProbeEntry> cleanTerminalEntries(UUID operationId) {
    return inTransaction(
        () ->
            repository.cleanTerminalEntries(
                operationId, AccountJwtReadinessProbeRepository.MAX_ENTRIES_PER_OPERATION));
  }

  private void pinBeforeDelivery(Binding binding, TrustFence trust, SignedProbeDigest digest) {
    try {
      inTransaction(
          () ->
              repository.recordSigningAttempt(
                  binding, trust, digest, Instant.ofEpochSecond(nowEpochSecond())));
    } catch (RuntimeException failure) {
      throw new SigningAttemptConflict();
    }
  }

  private void abortAfterDeliveryFailure(Binding binding, TrustFence trust, UUID operationId) {
    try {
      quarantineCurrent(binding, trust, operationId);
    } catch (RuntimeException ignored) {
      // The durable pre-delivery hash remains a quarantine marker for same-operation recovery.
    }
  }

  private static boolean exactSignedReadback(
      ReadinessProbePlan plan, ProbeEntry expected, ProbeEntry readback, SignedProbeDigest signed) {
    long issuedEntryVersion = expected.entryVersion() + 2L;
    long resultEntryVersion =
        readback.state() == ProbeState.VERIFIED ? issuedEntryVersion + 1L : issuedEntryVersion;
    return readback.rotationOperationId().equals(plan.operationId())
        && readback.planDigest().equals(plan.planDigest())
        && expected.planDigest().equals(plan.planDigest())
        && signed.operationId().equals(plan.operationId().toString())
        && readback.jti().equals(expected.jti())
        && signed.jti().equals(expected.jti())
        && readback.validatorId().equals(expected.validatorId())
        && signed.validatorId().equals(expected.validatorId())
        && readback.tokenProfile().equals(expected.tokenProfile())
        && signed.tokenProfile().equals(expected.tokenProfile())
        && readback.audience().equals(expected.audience())
        && signed.audience().equals(expected.audience())
        && readback.probeKind() == expected.probeKind()
        && signed.probeKind() == expected.probeKind()
        && readback.targetGeneration().equals(plan.targetGeneration())
        && signed.targetGeneration().equals(plan.targetGeneration())
        && readback.targetKid().equals(plan.targetKid())
        && signed.targetKid().equals(plan.targetKid())
        && signed.publicKeyFingerprint().equals(plan.targetPublicKeyFingerprint())
        && readback.plannedIssuedAtEpochSecond() == signed.issuedAtEpochSecond()
        && readback.expiresAtEpochSecond() == signed.expiresAtEpochSecond()
        && readback.signingAttemptedAtEpochSecond().isPresent()
        && readback.entryVersion() == resultEntryVersion
        && (readback.state() != ProbeState.VERIFIED
            || readback
                .verificationReceipt()
                .filter(
                    receipt ->
                        receipt.sourceEntryVersion() == issuedEntryVersion
                            && receipt.resultEntryVersion() == resultEntryVersion)
                .isPresent())
        && readback.compactTokenSha256().equals(java.util.Optional.of(signed.compactTokenSha256()));
  }

  private List<ProbeEntry> quarantineCurrent(Binding binding, TrustFence trust, UUID operationId) {
    return inTransaction(
        () -> {
          repository.abortCurrent(
              binding, trust, operationId, Instant.ofEpochSecond(nowEpochSecond()));
          return repository.cleanTerminalEntries(
              operationId, AccountJwtReadinessProbeRepository.MAX_ENTRIES_PER_OPERATION);
        });
  }

  private ProbeEntry currentEntry(
      Binding binding, TrustFence trust, UUID operationId, ProbeEntry expected) {
    ReadinessProbePlan plan =
        inTransaction(() -> repository.readCurrentPlan(binding, trust, operationId));
    return plan.entries().stream()
        .filter(entry -> entry.jti().equals(expected.jti()))
        .findFirst()
        .orElseThrow(
            () ->
                new AccountJwtReadinessProbeRepository.QuarantinedStateException(
                    "Readiness probe entry disappeared from its immutable plan"));
  }

  private long nowEpochSecond() {
    long value = clock.instant().getEpochSecond();
    if (value <= 0L) {
      throw new IllegalStateException("Readiness producer clock is invalid");
    }
    return value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private <T> T inTransaction(java.util.function.Supplier<T> operation) {
    return accountTransaction.execute(status -> operation.get());
  }

  /**
   * Per-invocation owner-internal sink. This is not a Spring component contract or an authority
   * input; callback success is not validation evidence. Only an exact owner readback of a receipt
   * independently recorded by the authenticated validator is.
   */
  @FunctionalInterface
  interface OwnerInternalProbeDelivery {
    void deliver(DeliveryMetadata metadata, byte[] exactCompactJwt);
  }

  /** Non-secret operation identity passed alongside the transient probe bytes. */
  public record DeliveryMetadata(
      UUID operationId,
      String planDigest,
      int planVersion,
      String validatorId,
      String tokenProfile,
      String audience,
      ProbeKind probeKind,
      UUID jti,
      String targetGeneration,
      String targetKid,
      Optional<String> expectedActiveGeneration,
      Optional<String> expectedActiveKid,
      int registryVersion,
      long entryVersion,
      long issuedAtEpochSecond,
      long expiresAtEpochSecond,
      String compactTokenSha256) {
    private static DeliveryMetadata from(
        ReadinessProbePlan plan, ProbeEntry entry, SignedProbeDigest digest) {
      return new DeliveryMetadata(
          plan.operationId(),
          plan.planDigest(),
          plan.planVersion(),
          entry.validatorId(),
          entry.tokenProfile(),
          entry.audience(),
          entry.probeKind(),
          entry.jti(),
          entry.targetGeneration(),
          entry.targetKid(),
          entry.expectedActive().map(value -> value.generation()),
          entry.expectedActive().map(value -> value.kid()),
          entry.registryVersion(),
          entry.entryVersion(),
          entry.plannedIssuedAtEpochSecond(),
          entry.expiresAtEpochSecond(),
          digest.compactTokenSha256());
    }
  }

  private static final class SigningAttemptConflict extends IllegalStateException {
    private SigningAttemptConflict() {
      super("Readiness signing attempt could not be durably pinned");
    }
  }

  private static final class DeliveryQuarantinedException extends IllegalStateException {
    private DeliveryQuarantinedException() {
      super("Readiness probe delivery was ambiguous; the operation was quarantined");
    }
  }
}
