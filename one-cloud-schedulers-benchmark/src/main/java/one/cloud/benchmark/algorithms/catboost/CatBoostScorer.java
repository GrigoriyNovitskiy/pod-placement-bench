package one.cloud.benchmark.algorithms.catboost;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import ai.catboost.CatBoostError;
import ai.catboost.CatBoostModel;

final class CatBoostScorer implements AutoCloseable {
    private final CatBoostModel model;
    private final String sha256;
    private final int[] numericSources;
    private final int[] categoricalSources;
    private final List<String> featureNames;

    CatBoostScorer(Path modelPath, String resourceName) throws IOException, CatBoostError {
        final byte[] bytes;
        if (modelPath != null) {
            bytes = Files.readAllBytes(modelPath);
        } else {
            try (final var stream = CatBoostScorer.class.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    throw new IOException("Missing packaged model: " + resourceName);
                }
                bytes = stream.readAllBytes();
            }
        }
        try {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        model = CatBoostModel.loadModel(new ByteArrayInputStream(bytes));
        try {
            if (model.getPredictionDimension() != 1) {
                throw new IllegalArgumentException("Expected one ranking score per candidate");
            }
            var features = model.getFeatures();
            int numericCount = 0;
            int categoricalCount = 0;
            for (var feature : features) {
                if (feature instanceof CatBoostModel.FloatFeature) {
                    numericCount++;
                } else if (feature instanceof CatBoostModel.CatFeature) {
                    categoricalCount++;
                } else {
                    throw new IllegalArgumentException("Unsupported model feature: " + feature.getName());
                }
            }
            numericSources = new int[numericCount];
            categoricalSources = new int[categoricalCount];
            Arrays.fill(numericSources, -1);
            Arrays.fill(categoricalSources, -1);
            for (var feature : features) {
                int source = CandidateFeatures.names().indexOf(feature.getName());
                if (source < 0) {
                    throw new IllegalArgumentException("Unknown model feature: " + feature.getName());
                }
                boolean categorical = feature instanceof CatBoostModel.CatFeature;
                if (categorical != feature.getName().equals("request_kind")) {
                    throw new IllegalArgumentException("Unexpected model feature type: " + feature.getName());
                }
                int[] sources = categorical ? categoricalSources : numericSources;
                int index = feature.getFeatureIndex();
                if (index < 0 || index >= sources.length || sources[index] != -1) {
                    throw new IllegalArgumentException("Invalid model feature index: " + feature.getName());
                }
                sources[index] = source;
            }
            featureNames = List.copyOf(Arrays.asList(model.getFeatureNames()));
        } catch (RuntimeException failure) {
            try {
                model.close();
            } catch (CatBoostError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    String sha256() {
        return sha256;
    }

    public List<String> featureNames() {
        return featureNames;
    }

    public int treeCount() {
        return model.getTreeCount();
    }

    public double[] score(float[][] raw) throws CatBoostError {
        if (raw.length == 0) {
            return new double[0];
        }
        float[][] numeric = new float[raw.length][numericSources.length];
        String[][] categorical = new String[raw.length][categoricalSources.length];
        for (int row = 0; row < raw.length; row++) {
            if (raw[row].length != CandidateFeatures.names().size()) {
                throw new IllegalArgumentException("Raw feature width does not match CandidateFeatures");
            }
            for (float value : raw[row]) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Raw features must be finite");
                }
            }
            for (int column = 0; column < numericSources.length; column++) {
                numeric[row][column] = raw[row][numericSources[column]];
            }
            for (int column = 0; column < categoricalSources.length; column++) {
                double value = raw[row][categoricalSources[column]];
                if (value < 0 || value > Integer.MAX_VALUE || value != Math.floor(value)) {
                    throw new IllegalArgumentException("request_kind must be a nonnegative integer code");
                }
                categorical[row][column] = Integer.toString((int) value);
            }
        }
        double[] scores = model.predict(numeric, categorical).copyRowMajorPredictions();
        for (double value : scores) {
            if (!Double.isFinite(value)) {
                throw new IllegalStateException("Model produced a non-finite score");
            }
        }
        return scores;
    }

    @Override
    public void close() throws CatBoostError {
        model.close();
    }
}
