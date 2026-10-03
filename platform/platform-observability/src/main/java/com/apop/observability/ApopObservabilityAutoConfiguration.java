package com.apop.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class ApopObservabilityAutoConfiguration {

    @Bean
    ApopMeters apopMeters(MeterRegistry registry) {
        return new ApopMeters(registry);
    }

    @Bean
    MeterFilter denyHighCardinalityLabels() {
        return MeterFilter.deny(ApopObservabilityAutoConfiguration::highCardinality);
    }

    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    OutboxAgeMeter outboxAgeMeter(JdbcTemplate jdbc) {
        return new OutboxAgeMeter(jdbc);
    }

    @Bean
    @ConditionalOnClass(Span.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<TraceResponseFilter> traceResponseFilter() {
        FilterRegistrationBean<TraceResponseFilter> registration = new FilterRegistrationBean<>(new TraceResponseFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    static boolean highCardinality(Meter.Id id) {
        for (Tag tag : id.getTags()) {
            String key = tag.getKey();
            if ("payment_id".equals(key)
                    || "paymentId".equals(key)
                    || "account_id".equals(key)
                    || "accountId".equals(key)
                    || "customer_id".equals(key)
                    || "correlation_id".equals(key)) {
                return true;
            }
        }
        return false;
    }

    @RestController
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static final class ProbeController {

        private final ApplicationAvailability availability;

        ProbeController(ApplicationAvailability availability) {
            this.availability = availability;
        }

        @GetMapping("/live")
        org.springframework.http.ResponseEntity<Void> live() {
            boolean alive = availability.getLivenessState() == LivenessState.CORRECT;
            return alive
                    ? org.springframework.http.ResponseEntity.ok().build()
                    : org.springframework.http.ResponseEntity.status(503).build();
        }

        @GetMapping("/ready")
        org.springframework.http.ResponseEntity<Void> ready() {
            boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
            return ready
                    ? org.springframework.http.ResponseEntity.ok().build()
                    : org.springframework.http.ResponseEntity.status(503).build();
        }
    }

    static final class TraceResponseFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            SpanContext context = Span.current().getSpanContext();
            if (context.isValid()) {
                MDC.put("traceId", context.getTraceId());
                MDC.put("spanId", context.getSpanId());
            }
            try {
                filterChain.doFilter(request, response);
            } finally {
                if (context.isValid()) {
                    String flags = context.isSampled() ? "01" : "00";
                    response.setHeader("traceparent", "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags);
                    MDC.remove("traceId");
                    MDC.remove("spanId");
                }
            }
        }
    }
}
