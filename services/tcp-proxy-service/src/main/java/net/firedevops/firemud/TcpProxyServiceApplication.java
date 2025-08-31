package net.firedevops.firemud;

import jakarta.annotation.PreDestroy;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.telnet.TelnetServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
@Import(CommonAutoConfiguration.class)
public class TcpProxyServiceApplication {
  private final TelnetServer telnetServer;

  public TcpProxyServiceApplication(TelnetServer telnetServer) {
    this.telnetServer = telnetServer;
  }

  public static void main(String[] args) {
    SpringApplication.run(TcpProxyServiceApplication.class, args);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void startServer() throws InterruptedException {
    telnetServer.start();
  }

  @PreDestroy
  public void stopServer() {
    telnetServer.stop();
  }
}
