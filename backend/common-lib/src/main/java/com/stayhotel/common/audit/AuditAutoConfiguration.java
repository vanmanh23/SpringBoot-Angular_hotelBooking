package com.stayhotel.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** Enabled per service with {@code stayhotel.audit.enabled=true} (the service must own an audit_logs table). */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@ConditionalOnProperty(name = "stayhotel.audit.enabled", havingValue = "true")
public class AuditAutoConfiguration {

    @Bean
    AuditTrail auditTrail(JdbcTemplate jdbc, ObjectMapper json, ObjectProvider<Clock> clock) {
        return new AuditTrail(jdbc, json, clock.getIfAvailable(Clock::systemUTC));
    }
}
