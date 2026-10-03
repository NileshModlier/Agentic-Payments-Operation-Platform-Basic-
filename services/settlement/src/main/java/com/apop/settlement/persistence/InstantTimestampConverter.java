package com.apop.settlement.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Converter(autoApply = true)
public class InstantTimestampConverter implements AttributeConverter<Instant, OffsetDateTime> {

    @Override
    public OffsetDateTime convertToDatabaseColumn(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    @Override
    public Instant convertToEntityAttribute(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
