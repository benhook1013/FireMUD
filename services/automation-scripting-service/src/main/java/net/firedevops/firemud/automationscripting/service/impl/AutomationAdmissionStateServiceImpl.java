package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionRequestHistory;
import net.firedevops.firemud.automationscripting.entity.AutomationAdmissionState;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionRequestHistoryRepository;
import net.firedevops.firemud.automationscripting.repository.AutomationAdmissionStateRepository;
import net.firedevops.firemud.automationscripting.service.AutomationAdmissionStateService;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository is an internal Spring collaborator.")
public class AutomationAdmissionStateServiceImpl implements AutomationAdmissionStateService {
  private static final int ADMISSION_SCOPE_LOCK_NAMESPACE = 0x41534D44;
  private static final String MODE_NORMAL = "NORMAL";
  private static final String MODE_PAUSED_FOR_ROLLBACK = "PAUSED_FOR_ROLLBACK";

  private final AutomationAdmissionStateRepository repository;
  private final AutomationAdmissionRequestHistoryRepository requestHistoryRepository;
  private final DSLContext dsl;

  AutomationAdmissionStateServiceImpl(AutomationAdmissionStateRepository repository) {
    this(repository, null, null);
  }

  AutomationAdmissionStateServiceImpl(
      AutomationAdmissionStateRepository repository,
      AutomationAdmissionRequestHistoryRepository requestHistoryRepository) {
    this(repository, null, requestHistoryRepository);
  }

  @Autowired
  public AutomationAdmissionStateServiceImpl(
      AutomationAdmissionStateRepository repository,
      DSLContext dsl,
      AutomationAdmissionRequestHistoryRepository requestHistoryRepository) {
    this.repository = repository;
    this.dsl = dsl;
    this.requestHistoryRepository = requestHistoryRepository;
  }

  @Override
  @Transactional
  public AdmissionStateSummary getState(String tenantId, String gameInstanceId, String regionId) {
    String normalizedTenantId = requireNormalizedScopeText(tenantId, "tenant_id");
    String normalizedGameInstanceId =
        requireNormalizedScopeText(gameInstanceId, "game_instance_id");
    // The first read may create the regional row. It must take the same instance-wide lock as
    // setMode so a concurrent mode mutation cannot be lost while both callers observe no row.
    lockMutationScope(dsl, normalizedTenantId, normalizedGameInstanceId);
    return toSummary(findOrCreate(normalizedTenantId, normalizedGameInstanceId, regionId));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AdmissionStateSummary> findState(
      String tenantId, String gameInstanceId, String regionId) {
    String normalizedTenantId = requireNormalizedScopeText(tenantId, "tenant_id");
    String normalizedGameInstanceId =
        requireNormalizedScopeText(gameInstanceId, "game_instance_id");
    String normalizedRegionId = normalize(regionId);
    return repository
        .findByTenantIdAndGameInstanceIdAndRegionId(
            normalizedTenantId, normalizedGameInstanceId, normalizedRegionId)
        .map(this::toReadSummary);
  }

  @Override
  @Transactional
  public AdmissionStateSummary setMode(SetAdmissionModeCommand command) {
    String tenantId = requireNormalizedScopeText(command.tenantId(), "tenant_id");
    String gameInstanceId =
        requireNormalizedScopeText(command.gameInstanceId(), "game_instance_id");
    String regionId = normalize(command.regionId());
    String mode = normalizeMode(command.mode());
    String requestId = normalize(command.controlPlaneRequestId());
    if (requestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    String actorPrincipal = requireNormalizedScopeText(command.actorPrincipal(), "actor");
    String reason = requireNormalizedScopeText(command.reason(), "reason");
    String fingerprint =
        requestFingerprint(
            tenantId, gameInstanceId, regionId, mode, requestId, actorPrincipal, reason);
    lockMutationScope(dsl, tenantId, gameInstanceId);
    Optional<AutomationAdmissionRequestHistory> priorRequest =
        findPriorRequest(tenantId, gameInstanceId, regionId, mode, requestId);
    if (priorRequest.isPresent()) {
      AutomationAdmissionRequestHistory history = priorRequest.orElseThrow();
      verifyRequestFingerprint(history.getRequestFingerprint(), fingerprint);
      AutomationAdmissionState currentState =
          repository
              .findByTenantIdAndGameInstanceIdAndRegionId(tenantId, gameInstanceId, regionId)
              .orElse(null);
      return currentState == null ? toSummary(history) : toSummary(currentState, history);
    }
    AutomationAdmissionState state = findOrCreate(tenantId, gameInstanceId, regionId);
    Instant now = Instant.now();
    if (requestId.equals(normalize(state.getControlPlaneRequestId()))
        && mode.equals(state.getMode())) {
      if (!fingerprint.equals(normalize(state.getControlPlaneRequestFingerprint()))) {
        throw new IllegalArgumentException(
            "control_plane_request_id already records a different admission-mode request");
      }
      AutomationAdmissionRequestHistory history =
          recordRequest(state, mode, requestId, fingerprint, OUTCOME_ALREADY_APPLIED, now);
      return history == null
          ? toMutationSummary(state, mode, OUTCOME_ALREADY_APPLIED, fingerprint, now)
          : toSummary(history);
    }
    String outcome = state.getMode().equals(mode) ? OUTCOME_ALREADY_APPLIED : OUTCOME_APPLIED;
    if (!state.getMode().equals(mode)) {
      state.setMode(mode);
      if (MODE_PAUSED_FOR_ROLLBACK.equals(mode)) {
        state.setAdmissionEpoch(state.getAdmissionEpoch() + 1);
      }
    }
    state.setControlPlaneRequestId(requestId);
    state.setControlPlaneRequestFingerprint(fingerprint);
    state.setActorPrincipal(actorPrincipal);
    state.setReason(reason);
    state.setUpdatedAt(now);
    AutomationAdmissionState saved = repository.save(state);
    AutomationAdmissionRequestHistory history =
        recordRequest(saved, mode, requestId, fingerprint, outcome, now);
    return history == null
        ? toMutationSummary(saved, mode, outcome, fingerprint, now)
        : toSummary(history);
  }

  /** Serializes admission mutations for one game instance across all regional scope rows. */
  static void lockMutationScope(DSLContext dsl, String tenantId, String gameInstanceId) {
    if (dsl == null || dsl.dialect().family() != SQLDialect.POSTGRES) {
      return;
    }
    dsl.execute(
        "select pg_advisory_xact_lock(?, ?)",
        ADMISSION_SCOPE_LOCK_NAMESPACE,
        (tenantId + "\u0000" + gameInstanceId).hashCode());
  }

  private AutomationAdmissionState findOrCreate(
      String tenantId, String gameInstanceId, String regionId) {
    String normalizedRegionId = normalize(regionId);
    return repository
        .findByTenantIdAndGameInstanceIdAndRegionId(tenantId, gameInstanceId, normalizedRegionId)
        .orElseGet(
            () -> {
              AutomationAdmissionState state = new AutomationAdmissionState();
              state.setTenantId(requireText(tenantId, "tenant_id"));
              state.setGameInstanceId(requireText(gameInstanceId, "game_instance_id"));
              state.setRegionId(normalizedRegionId);
              state.setMode(MODE_NORMAL);
              state.setAdmissionEpoch(1L);
              return repository.save(state);
            });
  }

  private static AdmissionStateSummary toSummary(AutomationAdmissionState state) {
    return new AdmissionStateSummary(
        state.getTenantId(),
        state.getGameInstanceId(),
        state.getRegionId(),
        state.getMode(),
        state.getAdmissionEpoch(),
        blankToEmpty(state.getControlPlaneRequestId()),
        blankToEmpty(state.getActorPrincipal()),
        blankToEmpty(state.getReason()),
        state.getUpdatedAt().toEpochMilli());
  }

  private AdmissionStateSummary toReadSummary(AutomationAdmissionState state) {
    String requestId = normalize(state.getControlPlaneRequestId());
    if (requestHistoryRepository != null && !requestId.isBlank()) {
      Optional<AutomationAdmissionRequestHistory> history =
          requestHistoryRepository.find(
              state.getTenantId(),
              state.getGameInstanceId(),
              state.getRegionId(),
              state.getMode(),
              requestId);
      if (history.isPresent() && isCurrentSuccessfulAcknowledgement(state, history.get())) {
        return toSummary(history.get());
      }
    }
    return new AdmissionStateSummary(
        state.getTenantId(),
        state.getGameInstanceId(),
        state.getRegionId(),
        state.getMode(),
        state.getAdmissionEpoch(),
        "",
        "",
        "",
        state.getUpdatedAt().toEpochMilli(),
        "",
        OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE,
        "",
        0L);
  }

  private static boolean isCurrentSuccessfulAcknowledgement(
      AutomationAdmissionState state, AutomationAdmissionRequestHistory history) {
    String stateFingerprint = normalize(state.getControlPlaneRequestFingerprint());
    String historyFingerprint = normalize(history.getRequestFingerprint());
    return state.getAdmissionEpoch() == history.getAdmissionEpoch()
        && !stateFingerprint.isBlank()
        && stateFingerprint.equals(historyFingerprint)
        && (OUTCOME_APPLIED.equals(history.getOutcome())
            || OUTCOME_ALREADY_APPLIED.equals(history.getOutcome()));
  }

  private static AdmissionStateSummary toSummary(AutomationAdmissionRequestHistory history) {
    return new AdmissionStateSummary(
        history.getTenantId(),
        history.getGameInstanceId(),
        history.getRegionId(),
        history.getMode(),
        history.getAdmissionEpoch(),
        blankToEmpty(history.getControlPlaneRequestId()),
        blankToEmpty(history.getActorPrincipal()),
        blankToEmpty(history.getReason()),
        history.getCreatedAt().toEpochMilli(),
        history.getMode(),
        history.getOutcome(),
        blankToEmpty(history.getRequestFingerprint()),
        history.getCreatedAt().toEpochMilli());
  }

  private static AdmissionStateSummary toSummary(
      AutomationAdmissionState state, AutomationAdmissionRequestHistory history) {
    return new AdmissionStateSummary(
        state.getTenantId(),
        state.getGameInstanceId(),
        state.getRegionId(),
        state.getMode(),
        state.getAdmissionEpoch(),
        blankToEmpty(state.getControlPlaneRequestId()),
        blankToEmpty(state.getActorPrincipal()),
        blankToEmpty(state.getReason()),
        state.getUpdatedAt().toEpochMilli(),
        history.getMode(),
        history.getOutcome(),
        blankToEmpty(history.getRequestFingerprint()),
        history.getCreatedAt().toEpochMilli());
  }

  private static AdmissionStateSummary toMutationSummary(
      AutomationAdmissionState state,
      String mode,
      String outcome,
      String fingerprint,
      Instant acknowledgedAt) {
    return new AdmissionStateSummary(
        state.getTenantId(),
        state.getGameInstanceId(),
        state.getRegionId(),
        state.getMode(),
        state.getAdmissionEpoch(),
        blankToEmpty(state.getControlPlaneRequestId()),
        blankToEmpty(state.getActorPrincipal()),
        blankToEmpty(state.getReason()),
        state.getUpdatedAt().toEpochMilli(),
        mode,
        outcome,
        fingerprint,
        acknowledgedAt.toEpochMilli());
  }

  private static String normalizeMode(String mode) {
    String normalized = requireText(mode, "mode");
    if (MODE_NORMAL.equals(normalized) || MODE_PAUSED_FOR_ROLLBACK.equals(normalized)) {
      return normalized;
    }
    throw new IllegalArgumentException("mode must be NORMAL or PAUSED_FOR_ROLLBACK");
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String requireNormalizedScopeText(String value, String fieldName) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private Optional<AutomationAdmissionRequestHistory> findPriorRequest(
      String tenantId, String gameInstanceId, String regionId, String mode, String requestId) {
    return requestHistoryRepository == null
        ? Optional.empty()
        : requestHistoryRepository.find(tenantId, gameInstanceId, regionId, mode, requestId);
  }

  private AutomationAdmissionRequestHistory recordRequest(
      AutomationAdmissionState state,
      String mode,
      String requestId,
      String fingerprint,
      String outcome,
      Instant createdAt) {
    if (requestHistoryRepository == null) {
      return null;
    }
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId(state.getTenantId());
    history.setGameInstanceId(state.getGameInstanceId());
    history.setRegionId(state.getRegionId());
    history.setMode(mode);
    history.setControlPlaneRequestId(requestId);
    history.setRequestFingerprint(fingerprint);
    history.setAdmissionEpoch(state.getAdmissionEpoch());
    history.setOutcome(outcome);
    history.setActorPrincipal(blankToEmpty(state.getActorPrincipal()));
    history.setReason(blankToEmpty(state.getReason()));
    history.setCreatedAt(createdAt);
    return requestHistoryRepository.insertOrGet(history);
  }

  private static void verifyRequestFingerprint(String storedFingerprint, String fingerprint) {
    if (!normalize(storedFingerprint).equals(fingerprint)) {
      throw new IllegalArgumentException(
          "control_plane_request_id already records a different admission-mode request");
    }
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String requestFingerprint(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String mode,
      String requestId,
      String actorPrincipal,
      String reason) {
    String canonical =
        lengthPrefixedIdentity(
            "SetAutomationAdmissionMode",
            tenantId,
            gameInstanceId,
            regionId,
            mode,
            requestId,
            actorPrincipal,
            reason);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        result.append(String.format("%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static String lengthPrefixedIdentity(String... values) {
    StringBuilder identity = new StringBuilder();
    for (String value : values) {
      String normalized = value == null ? "" : value;
      identity
          .append(normalized.getBytes(StandardCharsets.UTF_8).length)
          .append(':')
          .append(normalized);
    }
    return identity.toString();
  }
}
