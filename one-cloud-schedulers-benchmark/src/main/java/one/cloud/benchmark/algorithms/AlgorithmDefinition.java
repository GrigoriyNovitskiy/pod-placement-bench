package one.cloud.benchmark.algorithms;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.JsonPrimitive;

import one.cloud.benchmark.algorithms.AlgorithmRegistry.Descriptor;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.Parameter;
import one.cloud.benchmark.algorithms.AlgorithmRegistry.ParameterizedFactory;
import one.cloud.benchmark.algorithms.fitcount.Catalogue;
import one.cloud.benchmark.engine.SchedulingAlgorithm;
import one.cloud.benchmark.engine.SchedulingContext;
import one.cloud.benchmark.model.Request;

public record AlgorithmDefinition(Descriptor descriptor, Factory factory) {
    public AlgorithmDefinition {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(factory);
    }

    public AlgorithmDefinition(String id, String name, List<Parameter> parameters, ParameterizedFactory factory) {
        this(descriptor(id, name, parameters), adapt(factory));
    }

    @FunctionalInterface
    public interface Factory {
        SchedulingAlgorithm create(SchedulingContext context, Request[] catalogue,
                Map<String, JsonPrimitive> parameters, Path priorFile) throws Exception;

        default SchedulingAlgorithm create(SchedulingContext context, Catalogue catalogue,
                Map<String, JsonPrimitive> parameters, Path priorFile) throws Exception {
            if (catalogue.hasFullFrequencies()) {
                throw new IllegalArgumentException("Full catalogue weights require a catalogue-aware factory");
            }
            return create(context, catalogue.requests(), parameters, priorFile);
        }
    }

    @FunctionalInterface
    public interface CatalogueFactory {
        SchedulingAlgorithm create(SchedulingContext context, Catalogue catalogue,
                Map<String, JsonPrimitive> parameters) throws Exception;
    }

    public static AlgorithmDefinition withCatalogue(String id, String name, List<Parameter> parameters,
            CatalogueFactory factory) {
        Objects.requireNonNull(factory);
        return new AlgorithmDefinition(descriptor(id, name, parameters), new Factory() {
            @Override
            public SchedulingAlgorithm create(SchedulingContext context, Request[] catalogue,
                    Map<String, JsonPrimitive> values, Path priorFile) throws Exception {
                return factory.create(context, Catalogue.sampled(catalogue), values);
            }

            @Override
            public SchedulingAlgorithm create(SchedulingContext context, Catalogue catalogue,
                    Map<String, JsonPrimitive> values, Path priorFile) throws Exception {
                return factory.create(context, catalogue, values);
            }
        });
    }

    private static Descriptor descriptor(String id, String name, List<Parameter> parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Algorithm parameter schema is required");
        }
        return new Descriptor(id, name, parameters);
    }

    private static Factory adapt(ParameterizedFactory factory) {
        Objects.requireNonNull(factory);
        return (context, catalogue, parameters, priorFile) -> factory.create(context, catalogue, parameters);
    }
}
