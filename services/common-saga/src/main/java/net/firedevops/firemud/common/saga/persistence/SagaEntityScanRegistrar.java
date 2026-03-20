package net.firedevops.firemud.common.saga.persistence;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

final class SagaEntityScanRegistrar implements ImportBeanDefinitionRegistrar {

  @Override
  public void registerBeanDefinitions(
      AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    Set<String> packages = new LinkedHashSet<>();
    packages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
    packages.add(SagaInstance.class.getPackageName());
    EntityScanPackages.register(registry, packages);
  }

  private static final class ClassUtils {
    private static String getPackageName(String className) {
      int packageSeparator = className.lastIndexOf('.');
      return (packageSeparator >= 0) ? className.substring(0, packageSeparator) : "";
    }
  }
}
