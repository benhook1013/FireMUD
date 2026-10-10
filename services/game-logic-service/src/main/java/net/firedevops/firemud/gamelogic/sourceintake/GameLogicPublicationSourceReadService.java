package net.firedevops.firemud.gamelogic.sourceintake;

import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered read of the genuine retained Game Logic source for one exact publication lookup.
 * This service does not create intake authority, read Account state, repair source, or activate a
 * digest transport.
 */
public final class GameLogicPublicationSourceReadService {
  public static final int DIGEST_SCHEMA_VERSION = 1;
  public static final String CANONICALIZATION = "RFC8785";

  private final GameLogicGameplayRuleIntakeRepository repository;
  private final String workloadNamespace;

  public GameLogicPublicationSourceReadService(
      GameLogicGameplayRuleIntakeRepository repository, String workloadNamespace) {
    this.repository = Objects.requireNonNull(repository, "repository");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Logic workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  /**
   * Returns digests only after the exact operation's actual RETAINED terminal is read back.
   *
   * <p>The repository verifies the indexed operation identity and all stored bytes. This method
   * additionally correlates that readback with the complete original authorization and exact
   * publication selection before deriving either digest.
   */
  public Result read(GameLogicPublicationSourceReadBinding binding) {
    requirePeer(workloadNamespace);
    if (binding == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Exact full-version publication source-read binding required")
          .asRuntimeException();
    }
    requireNoActiveTransaction();

    GameLogicIntakeAuthorizationBinding authorization = binding.authorization();
    GameLogicGameplayRuleIntakeOperation requestedOperation =
        new GameLogicGameplayRuleIntakeOperation(workloadNamespace, authorization);
    GameLogicGameplayRuleIntakeTerminal terminal =
        repository
            .findTerminal(authorization.operationId())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("Exact retained Game Logic source is unavailable")
                        .asRuntimeException());

    requireExactRetainedTerminal(terminal, requestedOperation, authorization);
    GameplayRuleSelectedSource retainedSource =
        new GameplayRuleSelectedSource(
            new String(terminal.selectedSourceBytes(), StandardCharsets.UTF_8));
    byte[] manifestBytes =
        retainedSource.manifest().canonicalJson().getBytes(StandardCharsets.UTF_8);
    if (!Arrays.equals(manifestBytes, terminal.manifestBytes())) {
      throw Status.ALREADY_EXISTS
          .withDescription("Retained Game Logic manifest differs from exact selected source")
          .asRuntimeException();
    }

    String manifestDigest = GameplayRuleManifest.sha256(manifestBytes);
    String abilitySchemaDigest = GameplayAbilitySchemaProjection.digest(retainedSource.manifest());
    return new Result(
        binding,
        terminal,
        retainedSource,
        manifestDigest,
        abilitySchemaDigest,
        DIGEST_SCHEMA_VERSION,
        CANONICALIZATION);
  }

  private static void requireExactRetainedTerminal(
      GameLogicGameplayRuleIntakeTerminal terminal,
      GameLogicGameplayRuleIntakeOperation requestedOperation,
      GameLogicIntakeAuthorizationBinding authorization) {
    if (!Arrays.equals(terminal.operation().canonicalBytes(), requestedOperation.canonicalBytes())
        || !Arrays.equals(terminal.authorizationBytes(), authorization.canonicalBytes())) {
      throw Status.ALREADY_EXISTS
          .withDescription("Retained Game Logic operation differs from exact publication source")
          .asRuntimeException();
    }
    if (terminal.outcome() != GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Exact Game Logic source operation did not retain source")
          .asRuntimeException();
    }
    if (!Arrays.equals(terminal.selectedSourceBytes(), authorization.source().canonicalBytes())) {
      throw Status.ALREADY_EXISTS
          .withDescription("Retained Game Logic source differs from Account authorization")
          .asRuntimeException();
    }
    if (!authorization
            .source()
            .binding()
            .target()
            .canonicalTenantId()
            .equals(authorization.tenantId())
        || !authorization
            .source()
            .binding()
            .target()
            .canonicalVersionId()
            .equals(authorization.versionId())) {
      throw Status.ALREADY_EXISTS
          .withDescription("Retained Game Logic commit scope differs from Account authorization")
          .asRuntimeException();
    }
  }

  private static void requirePeer(String namespace) {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified workload identity required")
          .asRuntimeException();
    }
    if (!peer.isInNamespace(namespace)
        || !peer.isService("game-design-service")
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Design workload required")
          .asRuntimeException();
    }
  }

  private static void requireNoActiveTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Publication source reads must run outside SQL")
          .asRuntimeException();
    }
  }

  /**
   * Immutable source-read result whose provenance is the exact object returned by owner storage.
   */
  public static final class Result {
    private final GameLogicPublicationSourceReadBinding binding;
    private final GameLogicGameplayRuleIntakeTerminal terminal;
    private final GameplayRuleSelectedSource selectedSource;
    private final String manifestDigest;
    private final String abilitySchemaDigest;
    private final int digestSchemaVersion;
    private final String canonicalization;

    private Result(
        GameLogicPublicationSourceReadBinding binding,
        GameLogicGameplayRuleIntakeTerminal terminal,
        GameplayRuleSelectedSource selectedSource,
        String manifestDigest,
        String abilitySchemaDigest,
        int digestSchemaVersion,
        String canonicalization) {
      this.binding = Objects.requireNonNull(binding, "binding");
      this.terminal = Objects.requireNonNull(terminal, "terminal");
      this.selectedSource = Objects.requireNonNull(selectedSource, "selectedSource");
      this.manifestDigest = Objects.requireNonNull(manifestDigest, "manifestDigest");
      this.abilitySchemaDigest = Objects.requireNonNull(abilitySchemaDigest, "abilitySchemaDigest");
      if (digestSchemaVersion <= 0) {
        throw new IllegalArgumentException("Positive digest schema version required");
      }
      this.digestSchemaVersion = digestSchemaVersion;
      this.canonicalization = Objects.requireNonNull(canonicalization, "canonicalization");
    }

    public GameLogicPublicationSourceReadBinding binding() {
      return binding;
    }

    /** The genuine immutable terminal instance returned by the owner repository readback. */
    public GameLogicGameplayRuleIntakeTerminal terminal() {
      return terminal;
    }

    public GameLogicGameplayRuleIntakeOperation operation() {
      return terminal.operation();
    }

    /** The exact selected source reconstructed from the terminal's defensive stored bytes. */
    public GameplayRuleSelectedSource selectedSource() {
      return selectedSource;
    }

    public byte[] terminalBytes() {
      return terminal.canonicalBytes();
    }

    public byte[] authorizationBytes() {
      return terminal.authorizationBytes();
    }

    public byte[] selectedSourceBytes() {
      return terminal.selectedSourceBytes();
    }

    public byte[] manifestBytes() {
      return terminal.manifestBytes();
    }

    public String manifestDigest() {
      return manifestDigest;
    }

    public String abilitySchemaDigest() {
      return abilitySchemaDigest;
    }

    public int digestSchemaVersion() {
      return digestSchemaVersion;
    }

    /** Canonicalization evidence for both the retained manifest and v1 ability projection. */
    public String canonicalization() {
      return canonicalization;
    }
  }
}
