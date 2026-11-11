package one.cloud.benchmark.algorithms;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public final class AlgorithmRegistry {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Map<String, AlgorithmDefinition> ENTRIES = new LinkedHashMap<>();

    public record Settings(String id, Map<String, JsonPrimitive> parameters) {
        public Settings {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Algorithm id is required");
            }
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public enum Type {
        INTEGER, NUMBER, BOOLEAN, STRING
    }

    public record Parameter(String key, String label, String description, Type type, JsonPrimitive defaultValue,
            Double minimum, Double maximum, boolean advanced) {
        public static Parameter integer(String key, String label, String description,
                long value, long minimum, long maximum) {
            return new Parameter(key, label, description, Type.INTEGER, new JsonPrimitive(value),
                    (double) minimum, (double) maximum, false);
        }

        public static Parameter number(String key, String label, String description,
                double value, double minimum, double maximum) {
            return new Parameter(key, label, description, Type.NUMBER, new JsonPrimitive(value),
                    minimum, maximum, false);
        }

        public static Parameter bool(String key, String label, String description, boolean value) {
            return new Parameter(key, label, description, Type.BOOLEAN, new JsonPrimitive(value),
                    null, null, false);
        }

        public static Parameter text(String key, String label, String description, String value) {
            return new Parameter(key, label, description, Type.STRING, new JsonPrimitive(value),
                    null, null, false);
        }

        public Parameter asAdvanced() {
            return new Parameter(key, label, description, type, defaultValue, minimum, maximum, true);
        }
    }

    public record Descriptor(String id, String name, List<Parameter> parameters,
            boolean requiresPrior, int maxBulkSize) {
        public Descriptor {
            parameters = List.copyOf(parameters);
            if (maxBulkSize < 1 || maxBulkSize > 100) {
                throw new IllegalArgumentException("Announcement window size must be between 1 and 100");
            }
        }

        public Descriptor(String id, String name, List<Parameter> parameters) {
            this(id, name, parameters, false, 100);
        }
    }

    @FunctionalInterface
    public interface ParameterizedFactory {
        SchedulingAlgorithm create(SchedulingContext context, Request[] catalogue,
                Map<String, JsonPrimitive> parameters) throws Exception;
    }

    static {
        Algorithms.registerAll();
    }

    private AlgorithmRegistry() {
    }

    public static Settings defaults(String id) {
        return normalize(new Settings(id, Map.of()));
    }

    public static synchronized List<Descriptor> algorithms() {
        return ENTRIES.values().stream().map(AlgorithmDefinition::descriptor).toList();
    }

    public static Settings normalize(Settings settings) {
        final var entry = entry(settings.id);
        final var parameters = new LinkedHashMap<String, JsonPrimitive>();
        for (final var parameter : entry.descriptor().parameters()) {
            final var value = settings.parameters.getOrDefault(parameter.key, parameter.defaultValue);
            parameters.put(parameter.key, value(parameter, value));
        }
        for (final var key : settings.parameters.keySet()) {
            if (!parameters.containsKey(key)) {
                throw new IllegalArgumentException("Unsupported parameter for " + settings.id + ": " + key);
            }
        }
        return new Settings(settings.id, parameters);
    }

    public static SchedulingAlgorithm create(Settings settings, SchedulingContext context, Request[] catalogue)
            throws Exception {
        return create(settings, context, catalogue, null);
    }

    public static SchedulingAlgorithm create(Settings settings, SchedulingContext context, Request[] catalogue,
            Path priorFile) throws Exception {
        final var validated = normalize(settings);
        final var entry = entry(validated.id);
        if (entry.descriptor().requiresPrior() && priorFile == null) {
            throw new IllegalArgumentException("Algorithm " + validated.id + " requires a rollout prior file");
        }
        if (!entry.descriptor().requiresPrior() && priorFile != null) {
            throw new IllegalArgumentException("Algorithm " + validated.id + " does not accept a rollout prior file");
        }
        return entry.factory().create(context, catalogue.clone(), validated.parameters, priorFile);
    }

    public static synchronized void register(String id, String name, List<Parameter> parameters,
            ParameterizedFactory factory) {
        Objects.requireNonNull(factory);
        if (parameters == null) {
            throw new IllegalArgumentException("Algorithm parameter schema is required");
        }
        register(new AlgorithmDefinition(id, name, parameters, factory));
    }

    public static synchronized void register(AlgorithmDefinition definition) {
        Objects.requireNonNull(definition);
        final var descriptor = definition.descriptor();
        final String id = descriptor.id;
        final String name = descriptor.name;
        final var parameters = descriptor.parameters;
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Algorithm id and display name are required");
        }
        final var keys = new HashSet<String>();
        for (final var parameter : parameters) {
            validateSchema(parameter);
            if (!keys.add(parameter.key)) {
                throw new IllegalArgumentException("Duplicate algorithm parameter: " + parameter.key);
            }
        }
        if (ENTRIES.putIfAbsent(id, definition) != null) {
            throw new IllegalArgumentException("Algorithm already registered: " + id);
        }
    }

    private static synchronized AlgorithmDefinition entry(String id) {
        final var entry = ENTRIES.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown algorithm: " + id);
        }
        return entry;
    }

    private static void validateSchema(Parameter parameter) {
        if (parameter == null || parameter.key == null || !parameter.key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                || parameter.label == null || parameter.label.isBlank() || parameter.type == null
                || parameter.description == null || parameter.description.isBlank()
                || parameter.defaultValue == null) {
            throw new IllegalArgumentException("Parameter key, label, description, type and default are required");
        }
        final boolean numeric = parameter.type == Type.INTEGER || parameter.type == Type.NUMBER;
        if (!numeric && (parameter.minimum != null || parameter.maximum != null)
                || parameter.minimum != null && !Double.isFinite(parameter.minimum)
                || parameter.maximum != null && !Double.isFinite(parameter.maximum)
                || parameter.minimum != null && parameter.maximum != null && parameter.minimum > parameter.maximum) {
            throw new IllegalArgumentException("Invalid bounds for parameter: " + parameter.key);
        }
        if (parameter.type == Type.INTEGER) {
            for (final var bound : new Double[]{parameter.minimum, parameter.maximum}) {
                if (bound != null && (bound != Math.rint(bound) || Math.abs(bound) > MAX_SAFE_INTEGER)) {
                    throw new IllegalArgumentException("Integer bounds must be safe whole JavaScript integers");
                }
            }
        }
        value(parameter, parameter.defaultValue);
    }

    private static JsonPrimitive value(Parameter parameter, JsonPrimitive value) {
        if (value == null) {
            throw new IllegalArgumentException("Parameter value is required: " + parameter.key);
        }
        switch (parameter.type) {
            case BOOLEAN -> {
                if (value.isBoolean()) {
                    return new JsonPrimitive(value.getAsBoolean());
                }
            }
            case STRING -> {
                if (value.isString()) {
                    return new JsonPrimitive(value.getAsString());
                }
            }
            case INTEGER, NUMBER -> {
                if (value.isNumber()) {
                    try {
                        final var number = value.getAsBigDecimal();
                        if (parameter.minimum != null && number.compareTo(BigDecimal.valueOf(parameter.minimum)) < 0
                                || parameter.maximum != null && number.compareTo(BigDecimal.valueOf(parameter.maximum)) > 0) {
                            throw new IllegalArgumentException("Parameter is outside its range: " + parameter.key);
                        }
                        if (parameter.type == Type.INTEGER) {
                            final long integer = number.longValueExact();
                            if (integer < -MAX_SAFE_INTEGER || integer > MAX_SAFE_INTEGER) {
                                throw new IllegalArgumentException("Integer must be exactly representable in JavaScript: " + parameter.key);
                            }
                            return new JsonPrimitive(integer);
                        }
                        if (Double.isFinite(number.doubleValue())) {
                            return new JsonPrimitive(number.doubleValue());
                        }
                    } catch (NumberFormatException | ArithmeticException failure) {
                        throw new IllegalArgumentException("Invalid numeric parameter: " + parameter.key);
                    }
                }
            }
        }
        throw new IllegalArgumentException("Parameter " + parameter.key + " must have type " + parameter.type);
    }
}
