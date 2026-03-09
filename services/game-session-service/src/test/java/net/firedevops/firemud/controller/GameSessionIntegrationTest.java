package net.firedevops.firemud.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.firedevops.firemud.GameSessionServiceApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    properties = {
      "firemud.database.enabled=false",
      "spring.main.allow-bean-definition-overriding=true",
      "spring.autoconfigure.exclude=org.lognet.springboot.grpc.autoconfigure.GRpcAutoConfiguration",
      "game-session.dev-isolated=true",
      "firemud.grpc.plaintext=true",
      "spring.application.name=game-session-service",
      "grpc.server.port=0"
    })
@AutoConfigureMockMvc
@Import(LookCacheTestConfiguration.class)
public @interface GameSessionIntegrationTest {}
