package net.firedevops.firemud.common.saga.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.core.annotation.AliasFor;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@EntityScan(basePackageClasses = {SagaInstance.class, SagaStep.class})
public @interface EnableSagaEntityScan {

  @AliasFor(annotation = EntityScan.class, attribute = "basePackageClasses")
  Class<?>[] basePackageClasses() default {};
}
