package net.firedevops.firemud.gamesession.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Provides the executor used for running tick processing in parallel. */
@Configuration
@EnableAsync
public class AsyncConfig {
  @Bean(name = "tickExecutor")
  public Executor tickExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    int cores = Runtime.getRuntime().availableProcessors();
    executor.setCorePoolSize(cores);
    executor.setMaxPoolSize(cores * 2);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("tick-");
    executor.initialize();
    return executor;
  }
}
