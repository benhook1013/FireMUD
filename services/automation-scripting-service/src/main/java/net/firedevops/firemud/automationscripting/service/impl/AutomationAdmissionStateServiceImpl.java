package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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
    justification = "Injected repositories are internal Spring collaborators.")
public class AutomationAdmissionStateServiceImpl implements AutomationAdmissionStateService {
  private static final int ADMISSION_SCOPE_LOCK_NAMESPACE = 0x41534D44;
  private static final String MODE_NORMAL = "NORMAL";
  private static final String MODE_PAUSED_FOR_ROLLBACK = "PAUSED_FOR_ROLLBACK";

  private final AutomationAdmissionStateRepository repository;
  private final AutomationAdmissionRequestHistoryRepository requestHistoryRepository;
  private final DSLContext dsl;

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

  /** Internal runtime admission may create the default state row for a new exact scope. */
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

  /** Operator drain and acknowledgement readback must not create missing state. */
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
    String requestId =
        requireNormalizedText(command.controlPlaneRequestId(), "control_plane_request_id");
    String actorPrincipal = requireNormalizedText(command.actorPrincipal(), "actor");
    String reason = requireNormalizedText(command.reason(), "reason");
    String fingerprint =
        requestFingerprint(
            tenantId, gameInstanceId, regionId, mode, requestId, actorPrincipal, reason);
    lockMutationScope(dsl, tenantId, gameInstanceId);

    Optional<AutomationAdmissionRequestHistory> priorRequest =
        requestHistoryRepository.find(tenantId, gameInstanceId, regionId, mode, requestId);
    if (priorRequest.isPresent()) {
      AutomationAdmissionRequestHistory history = priorRequest.orElseThrow();
      verifyRequestFingerprint(history.getRequestFingerprint(), fingerprint);
      return toSummary(history);
    }

    AutomationAdmissionState state = findOrCreate(tenantId, gameInstanceId, regionId);
    if (requestId.equals(normalize(state.getControlPlaneRequestId()))
        && !normalize(state.getControlPlaneRequestFingerprint()).isBlank()) {
      verifyRequestFingerprint(state.getControlPlaneRequestFingerprint(), fingerprint);
      throw new IllegalStateException(
          "admission state records the request without a durable acknowledgement");
    }

    Instant now = Instant.now();
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
    AutomationAdmissionRequestHistory durableResult =
        requestHistoryRepository.insertOrGet(toHistory(saved, mode, outcome, fingerprint, now));
    verifyRequestFingerprint(durableResult.getRequestFingerprint(), fingerprint);
    return toSummary(durableResult);
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
              state.setTenantId(tenantId);
              state.setGameInstanceId(gameInstanceId);
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
        state.getUpdatedAt().toEpochMilli(),
        "",
        OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE,
        "",
        0L);
  }

  private AdmissionStateSummary toReadSummary(AutomationAdmissionState state) {
    String requestId = normalize(state.getControlPlaneRequestId());
    String stateFingerprint = normalize(state.getControlPlaneRequestFingerprint());
    if (!requestId.isBlank() && !stateFingerprint.isBlank()) {
      Optional<AutomationAdmissionRequestHistory> history =
          requestHistoryRepository.find(
              state.getTenantId(),
              state.getGameInstanceId(),
              state.getRegionId(),
              state.getMode(),
              requestId);
      if (history.isPresent() && isCurrentSuccessfulAcknowledgement(state, history.orElseThrow())) {
        return toSummary(history.orElseThrow());
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
    return state.getAdmissionEpoch() == history.getAdmissionEpoch()
        && state.getMode().equals(history.getMode())
        && stateFingerprint.equals(normalize(history.getRequestFingerprint()))
        && (OUTCOME_APPLIED.equals(history.getOutcome())
            || OUTCOME_ALREADY_APPLIED.equals(history.getOutcome()));
  }

  private static AutomationAdmissionRequestHistory toHistory(
      AutomationAdmissionState state,
      String targetMode,
      String outcome,
      String fingerprint,
      Instant acknowledgedAt) {
    AutomationAdmissionRequestHistory history = new AutomationAdmissionRequestHistory();
    history.setTenantId(state.getTenantId());
    history.setGameInstanceId(state.getGameInstanceId());
    history.setRegionId(state.getRegionId());
    history.setMode(targetMode);
    history.setControlPlaneRequestId(state.getControlPlaneRequestId());
    history.setRequestFingerprint(fingerprint);
    history.setAdmissionEpoch(state.getAdmissionEpoch());
    history.setOutcome(outcome);
    history.setActorPrincipal(state.getActorPrincipal());
    history.setReason(state.getReason());
    history.setCreatedAt(acknowledgedAt);
    return history;
  }

  private static AdmissionStateSummary toSummary(AutomationAdmissionRequestHistory history) {
    long acknowledgedAtMs = history.getCreatedAt().toEpochMilli();
    return new AdmissionStateSummary(
        history.getTenantId(),
        history.getGameInstanceId(),
        history.getRegionId(),
        history.getMode(),
        history.getAdmissionEpoch(),
        history.getControlPlaneRequestId(),
        history.getActorPrincipal(),
        history.getReason(),
        acknowledgedAtMs,
        history.getMode(),
        history.getOutcome(),
        history.getRequestFingerprint(),
        acknowledgedAtMs);
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
    return requireNormalizedText(value, fieldName);
  }

  private static String requireNormalizedText(String value, String fieldName) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void verifyRequestFingerprint(String storedFingerprint, String fingerprint) {
    if (!normalize(storedFingerprint).equals(fingerprint)) {
      throw new IllegalArgumentException(
          "control_plane_request_id already records a different admission-mode request");
    }
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
    return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static String lengthPrefixedIdentity(String... values) {
    StringBuilder identity = new StringBuilder();
    for (String value : values) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      identity.append(bytes.length).append(':').append(value);
    }
    return identity.toString();
  }
}
