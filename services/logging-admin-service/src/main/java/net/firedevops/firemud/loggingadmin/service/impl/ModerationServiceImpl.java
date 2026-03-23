package net.firedevops.firemud.loggingadmin.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.loggingadmin.client.AccountClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.dto.ApplyModerationActionRequest;
import net.firedevops.firemud.loggingadmin.dto.ModerationActionDto;
import net.firedevops.firemud.loggingadmin.entity.ModerationAction;
import net.firedevops.firemud.loggingadmin.mapper.ModerationActionMapper;
import net.firedevops.firemud.loggingadmin.repository.ModerationActionRepository;
import net.firedevops.firemud.loggingadmin.service.ModerationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected clients and repositories remain internal")
public class ModerationServiceImpl implements ModerationService {
  private static final Logger logger = LoggingUtil.getLogger(ModerationServiceImpl.class);

  private final ModerationActionRepository repository;
  private final ModerationActionMapper mapper;
  private final AccountClient accountClient;
  private final GameSessionClient gameSessionClient;
  private final SagaRunner sagaRunner;

  public ModerationServiceImpl(
      ModerationActionRepository repository,
      ModerationActionMapper mapper,
      AccountClient accountClient,
      GameSessionClient gameSessionClient,
      SagaRunner sagaRunner) {
    this.repository = repository;
    this.mapper = mapper;
    this.accountClient = accountClient;
    this.gameSessionClient = gameSessionClient;
    this.sagaRunner = sagaRunner;
  }

  @Override
  @Transactional
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
    final ModerationAction[] ref = new ModerationAction[] {entity};

    SagaBuilder builder = new SagaBuilder("adminBan");
    builder
        .step(
            "recordAction", () -> ref[0] = repository.save(ref[0]), () -> repository.delete(ref[0]))
        .step(
            "deleteAccount",
            () -> accountClient.deleteAccount(request.tenantId(), request.accountId()))
        .step("stopSession", () -> gameSessionClient.stopSession(request.accountId()));

    try {
      var saga = builder.build();
      sagaRunner.run(saga);
    } catch (SagaException e) {
      logger.warn("Admin operation saga failed", e);
      throw new IllegalStateException("Moderation action failed", e);
    }

    return mapper.toDto(ref[0]);
  }
}
