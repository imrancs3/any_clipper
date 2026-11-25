package com.anyclipper.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * Stores a {@code List<String>} entity field as one JSON text column, e.g. {@code ["#ai","#tech"]}.
 *
 * <p>Used for a clip's hashtags, keywords and alternative titles, so these short lists need no extra
 * tables (see the spec, Step 4).
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write string list as JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored string list is not valid JSON: " + json, e);
        }
    }
}
