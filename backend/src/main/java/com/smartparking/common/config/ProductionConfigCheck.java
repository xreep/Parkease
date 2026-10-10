package com.smartparking.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Runs {@link ProductionConfigValidator} as soon as the context starts, before beans are created. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionConfigCheck {

    @Bean
    static BeanFactoryPostProcessor productionConfigValidation(Environment environment) {
        return beanFactory -> ProductionConfigValidator.of(environment).validate();
    }
}
