package com.stayhotel.common.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;

/** Registers {@link GlobalExceptionHandler} in every servlet-based service that depends on common-lib. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class CommonErrorAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataAccessException.class)
    @Import(DataAccessExceptionHandler.class)
    static class DataAccessErrors {
    }
}
