package com.orbitspace.kernel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Only include disclosure-safe JSON data in details, e.g. an authorized cursor reset boundary. */
public record ApiErrorResponse(String code, String message, String requestId, Map<String, ?> details) {
    public ApiErrorResponse {
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        Objects.requireNonNull(requestId);
        details = copyDetails(details);
    }

    static Map<String, ?> copyDetails(Map<?, ?> source) {
        var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Expected a JSON object key");
            }
            copy.put(name, copyValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        return switch (value) {
            case null -> null;
            case Map<?, ?> map -> copyDetails(map);
            case List<?> list -> list.stream().map(ApiErrorResponse::copyValue).toList();
            case String s -> s;
            case Boolean b -> b;
            case Number n when n instanceof Byte || n instanceof Short || n instanceof Integer
                    || n instanceof Long || n instanceof BigInteger || n instanceof BigDecimal -> n;
            case Double d when Double.isFinite(d) -> d;
            case Float f when Float.isFinite(f) -> f;
            default -> throw new IllegalArgumentException("Expected a JSON value");
        };
    }
}
