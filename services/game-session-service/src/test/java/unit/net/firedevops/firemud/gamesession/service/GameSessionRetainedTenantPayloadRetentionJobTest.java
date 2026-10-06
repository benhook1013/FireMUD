package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class GameSessionRetainedTenantPayloadRetentionJobTest {
  @Test
  void componentUsesOnlyTheExistingDslContextAndRetriesFailedCleanup() {
    DSLContext dsl = Mockito.mock(DSLContext.class);
    when(dsl.execute(anyString()))
        .thenThrow(new IllegalStateException("temporary cleanup failure"))
        .thenReturn(1);

    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(DSLContext.class, () -> dsl);
      context.register(GameSessionRetainedTenantPayloadRetentionJob.class);
      context.refresh();

      assertThat(context.getBean(GameSessionRetainedTenantPayloadRetentionJob.class)).isNotNull();
      assertThat(context.getBeansOfType(GameSessionRetainedTenantAssociationRepository.class))
          .isEmpty();

      GameSessionRetainedTenantPayloadRetentionJob job =
          context.getBean(GameSessionRetainedTenantPayloadRetentionJob.class);
      assertThatThrownBy(job::purgeExpiredPayloadBatch)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("temporary cleanup failure");
      job.purgeExpiredPayloadBatch();
    }

    verify(dsl, times(2)).execute(anyString());
  }
}
