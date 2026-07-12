package net.firedevops.firemud.gamesession.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Provides bounded executors for background Game Session work. */
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

  @Bean(name = "scriptEventExecutor")
  public Executor scriptEventExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("script-event-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "commandHistoryExecutor")
  public Executor commandHistoryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // History is best-effort. One writer prevents it from contending with concurrent gameplay
    // transactions for the service's shared JDBC pool.
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(500);
    executor.setThreadNamePrefix("command-history-");
    executor.initialize();
    return executor;
  }

  @Bean(name = "commandHistoryRetentionScheduler")
  public ThreadPoolTaskScheduler commandHistoryRetentionScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("command-history-retention-");
    scheduler.initialize();
    return scheduler;
  }
}
