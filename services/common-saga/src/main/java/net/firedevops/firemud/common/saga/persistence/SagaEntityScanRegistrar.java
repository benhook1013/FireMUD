package net.firedevops.firemud.common.saga.persistence;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

final class SagaEntityScanRegistrar implements ImportBeanDefinitionRegistrar {
  static final String MARKER_BEAN_NAME = "firemudSagaPersistenceEnabledMarker";

  @Override
  public void registerBeanDefinitions(
      AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
    Set<String> packages = new LinkedHashSet<>();
    packages.add(ClassUtils.getPackageName(importingClassMetadata.getClassName()));
    packages.add(SagaInstance.class.getPackageName());
    AutoConfigurationPackages.register(registry, SagaInstanceRepository.class.getPackageName());
    EntityScanPackages.register(registry, packages);
    if (!registry.containsBeanDefinition(MARKER_BEAN_NAME)) {
      registry.registerBeanDefinition(
          MARKER_BEAN_NAME, new RootBeanDefinition(SagaPersistenceEnabledMarker.class));
    }
  }

  private static final class ClassUtils {
    private static String getPackageName(String className) {
      int packageSeparator = className.lastIndexOf('.');
      return (packageSeparator >= 0) ? className.substring(0, packageSeparator) : "";
    }
  }
}
