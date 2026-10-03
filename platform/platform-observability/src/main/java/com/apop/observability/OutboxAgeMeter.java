package com.apop.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;

public class OutboxAgeMeter implements MeterBinder {

    private final JdbcTemplate jdbc;

    public OutboxAgeMeter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("apop.outbox.unpublished.age", this, OutboxAgeMeter::ageSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox row")
                .register(registry);
    }

    double ageSeconds() {
        try {
            Double age = jdbc.queryForObject("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0)
                    FROM outbox
                    WHERE published_at IS NULL
                    """, Double.class);
            return age == null ? 0d : age;
        } catch (RuntimeException exception) {
            return 0d;
        }
    }
}
