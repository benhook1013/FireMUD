package net.firedevops.firemud.springcloudgateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.springcloudgateway.service.GatewayRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import reactor.core.publisher.Mono;

class GatewayControllerConditionTest {

  private final ReactiveWebApplicationContextRunner contextRunner =
      new ReactiveWebApplicationContextRunner()
          .withUserConfiguration(GatewayController.class)
          .withBean(
              GatewayRouteService.class,
              () ->
                  new GatewayRouteService() {
                    @Override
                    public Mono<net.firedevops.firemud.springcloudgateway.service.GatewayRoute>
                        upsert(
                            net.firedevops.firemud.springcloudgateway.service.GatewayRoute route) {
                      return Mono.just(route);
                    }

                    @Override
                    public Mono<Boolean> remove(String routeId) {
                      return Mono.just(true);
                    }
                  });

  @Test
  void controllerIsAbsentByDefault() {
    contextRunner.run(context -> assertThat(context).doesNotHaveBean(GatewayController.class));
  }

  @Test
  void controllerRequiresExplicitEnablement() {
    contextRunner
        .withPropertyValues("firemud.gateway.dynamic-routes.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(GatewayController.class));
  }
}
