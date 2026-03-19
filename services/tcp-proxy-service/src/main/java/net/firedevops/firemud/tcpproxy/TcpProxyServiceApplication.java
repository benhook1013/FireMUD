package net.firedevops.firemud.tcpproxy;

import jakarta.annotation.PreDestroy;
import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.tcpproxy.telnet.TelnetServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

@SpringBootApplication(
    excludeName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
    })
@Import(CommonAutoConfiguration.class)
@EnableConfigurationProperties(CommonGrpcClientProperties.class)
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
