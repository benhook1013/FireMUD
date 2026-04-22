package net.firedevops.firemud.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/** Enables the shared JWT + HTTP auth properties for MVC-style controller tests. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(
    properties = {
      FiremudAuthTestProperties.JWT_SECRET,
      FiremudAuthTestProperties.JWT_EXPIRATION,
      FiremudAuthTestProperties.HTTP_ENABLED
    })
public @interface WithFiremudHttpAuthTestProperties {}
