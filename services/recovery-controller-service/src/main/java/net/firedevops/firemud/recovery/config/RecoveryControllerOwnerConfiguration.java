package net.firedevops.firemud.recovery.config;

import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.journal.JooqInitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.DeniedInitialGameplayReleaseEvidenceProvider;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseController;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration(afterName = "org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration")
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class RecoveryControllerOwnerConfiguration {
  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseEvidenceProvider.class)
  InitialGameplayReleaseEvidenceProvider deniedInitialGameplayReleaseEvidenceProvider() {
    return new DeniedInitialGameplayReleaseEvidenceProvider();
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseEffectPort.class)
  InitialGameplayReleaseEffectPort deniedInitialGameplayReleaseEffectPort() {
    return new DeniedInitialGameplayReleaseEffectPort();
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseJournal.class)
  InitialGameplayReleaseJournal initialGameplayReleaseJournal(
      DSLContext dsl, InitialGameplayReleaseTupleCodec tupleCodec) {
    return new JooqInitialGameplayReleaseJournal(dsl, tupleCodec);
  }

  @Bean
  @ConditionalOnMissingBean(InitialGameplayReleaseController.class)
  InitialGameplayReleaseController initialGameplayReleaseController(
      InitialGameplayReleaseJournal journal,
      InitialGameplayReleaseTupleCodec tupleCodec,
      InitialGameplayReleaseEvidenceProvider evidenceProvider,
      InitialGameplayReleaseEffectPort effectPort) {
    return new InitialGameplayReleaseController(journal, tupleCodec, evidenceProvider, effectPort);
  }
}
