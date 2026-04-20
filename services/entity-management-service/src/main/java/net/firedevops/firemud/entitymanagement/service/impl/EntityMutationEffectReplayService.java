package net.firedevops.firemud.entitymanagement.service.impl;

import com.google.protobuf.MessageLite;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.firedevops.firemud.entitymanagement.entity.EntityMutationEffect;
import net.firedevops.firemud.entitymanagement.repository.EntityMutationEffectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EntityMutationEffectReplayService {
  private static final String METRIC_NAME = "entitymanagement.mutation.effect.execution";
  private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
  private static final String STATUS_APPLIED = "APPLIED";

  private final EntityMutationEffectRepository repository;
  private final MeterRegistry meterRegistry;

  public EntityMutationEffectReplayService(
      EntityMutationEffectRepository repository, MeterRegistry meterRegistry) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  @Transactional
  public <T extends MessageLite> T execute(
      Long tenantId,
      String effectId,
      String operationName,
      Supplier<T> mutation,
      ResponseParser<T> parser) {
    if (!StringUtils.hasText(effectId)) {
      return mutation.get();
    }
    String normalizedEffectId = effectId.trim();
    String normalizedOperationName = operationName.trim();
    Optional<EntityMutationEffect> existing =
        repository.findByTenantIdAndEffectId(tenantId, normalizedEffectId);
    if (existing.isPresent()) {
      return replay(existing.orElseThrow(), normalizedOperationName, parser);
    }
    int inserted =
        repository.insertInProgress(tenantId, normalizedEffectId, normalizedOperationName);
    if (inserted == 0) {
      EntityMutationEffect raced =
          repository
              .findByTenantIdAndEffectId(tenantId, normalizedEffectId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT, "Mutation effect is already being applied"));
      return replay(raced, normalizedOperationName, parser);
    }
    return executeFirstApplication(tenantId, normalizedEffectId, normalizedOperationName, mutation);
  }

  private <T extends MessageLite> T executeFirstApplication(
      Long tenantId, String effectId, String operationName, Supplier<T> mutation) {
    EntityMutationEffect effect =
        repository
            .findByTenantIdAndEffectId(tenantId, effectId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Mutation effect marker was not created"));
    T response = mutation.get();
    effect.setStatus(STATUS_APPLIED);
    effect.setResponseType(response.getClass().getName());
    effect.setResponsePayload(response.toByteArray());
    effect.setCompletedAt(Instant.now());
    repository.save(effect);
    record(operationName, STATUS_APPLIED);
    return response;
  }

  private <T extends MessageLite> T replay(
      EntityMutationEffect existing, String operationName, ResponseParser<T> parser) {
    return replayAlreadySerialized(existing, operationName, parser);
  }

  private <T extends MessageLite> T replayAlreadySerialized(
      EntityMutationEffect existing, String operationName, ResponseParser<T> parser) {
    if (!operationName.equals(existing.getOperationName())) {
      record(operationName, "REJECTED");
      throw new IllegalArgumentException("Effect id was already used for another operation");
    }
    if (!STATUS_APPLIED.equals(existing.getStatus()) || existing.getResponsePayload() == null) {
      record(operationName, "IN_PROGRESS");
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Mutation effect is already being applied");
    }
    try {
      T response = parser.parse(existing.getResponsePayload());
      record(operationName, "REPLAY_NOOP");
      return response;
    } catch (Exception ex) {
      record(operationName, "UNREADABLE");
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Stored mutation effect response is unreadable", ex);
    }
  }

  private void record(String operationName, String effectStatus) {
    meterRegistry
        .counter(METRIC_NAME, "operation", operationName, "effect_status", effectStatus)
        .increment();
  }

  @FunctionalInterface
  public interface ResponseParser<T extends MessageLite> {
    T parse(byte[] bytes) throws Exception;
  }
}
