package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.dto.ModerationPolicyDecisionDto;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.mapper.ModerationActionMapper;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring injects the shared repository singleton for this service seam.")
public class ModerationServiceImpl implements ModerationService {
  private static final Logger logger = LoggingUtil.getLogger(ModerationServiceImpl.class);
  private static final String SCOPE_GAMEPLAY_ADMISSION = "GAMEPLAY_ADMISSION";
  private static final String SCOPE_CHAT_SEND = "CHAT_SEND";
  private static final List<String> GAMEPLAY_BLOCK_ACTIONS =
      List.of("ban", "account_ban", "gameplay_ban");
  private static final List<String> CHAT_BLOCK_ACTIONS =
      List.of("ban", "account_ban", "chat_ban", "chat_mute");

  private final ModerationActionRepository repository;
  private final ModerationActionMapper mapper;

  public ModerationServiceImpl(
      ModerationActionRepository repository, ModerationActionMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Timed(value = "moderation.applyAction")
  public ModerationActionDto applyAction(ApplyModerationActionRequest request) {
    logger.info(
        "Applying moderation action {} to account {}", request.action(), request.accountId());
    ModerationAction entity = new ModerationAction();
    entity.setTenantId(request.tenantId());
    entity.setAccountId(request.accountId());
    entity.setAction(request.action());
    entity.setReason(request.reason());
    entity.setCreatedAt(Instant.now());
    return mapper.toDto(repository.save(entity));
  }

  @Override
  @Timed(value = "moderation.evaluatePolicy")
  public ModerationPolicyDecisionDto evaluatePolicy(long tenantId, long accountId, String scope) {
    List<String> blockingActions = blockingActionsFor(scope);
    if (blockingActions.isEmpty()) {
      throw new IllegalArgumentException("Unknown moderation policy scope: " + scope);
    }
    return repository
        .findActivePolicyActions(tenantId, accountId, blockingActions, Instant.now())
        .stream()
        .findFirst()
        .map(
            action ->
                new ModerationPolicyDecisionDto(
                    false, action.getAction(), action.getReason(), action.getExpiresAt()))
        .orElseGet(() -> new ModerationPolicyDecisionDto(true, "", "", null));
  }

  private List<String> blockingActionsFor(String scope) {
    String normalized = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case SCOPE_GAMEPLAY_ADMISSION -> GAMEPLAY_BLOCK_ACTIONS;
      case SCOPE_CHAT_SEND -> CHAT_BLOCK_ACTIONS;
      default -> List.of();
    };
  }
}
