package one.cloud.benchmark.algorithms.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Arrays;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

public final class PolicyNetwork {
    private static final int HIDDEN_SIZE = 32;
    private static final double MIN_SCALE = 1e-6;
    private static final double TIE_TOLERANCE_PER_MEMBER = 1e-5;
    private final Artifact model;
    private final String sha256;

    private record Artifact(int formatVersion, List<String> featureNames, double[] mean, double[] scale,
            double[][] hiddenWeights, double[] hiddenBias, double[] outputWeights, Double outputBias) {
    }

    private PolicyNetwork(Artifact model, String sha256) {
        this.model = model;
        this.sha256 = sha256;
        validate();
    }

    public static PolicyNetwork load(Path path) throws IOException {
        final byte[] bytes;
        if (path != null) {
            bytes = Files.readAllBytes(path);
        } else {
            try (final var stream = PolicyNetwork.class.getResourceAsStream(
                    "/one/cloud/benchmark/algorithms/rl/policy.json")) {
                if (stream == null) {
                    throw new IOException("Missing packaged RL model");
                }
                bytes = stream.readAllBytes();
            }
        }
        try {
            final var artifact = new Gson().fromJson(new String(bytes, StandardCharsets.UTF_8), Artifact.class);
            final var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new PolicyNetwork(artifact, HexFormat.of().formatHex(digest));
        } catch (JsonParseException failure) {
            throw new IllegalArgumentException("Invalid RL model JSON", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String sha256() {
        return sha256;
    }

    public int select(float[][] rows, int members) {
        return shortlist(rows, members, 1)[0];
    }

    public int[] shortlist(float[][] rows, int members, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("RL shortlist size must be positive");
        }
        final var scores = scores(rows, members);
        if (scores.length == 0) {
            throw new IllegalArgumentException("RL requires at least one candidate");
        }
        final var selected = new int[Math.min(limit, scores.length)];
        final var retained = new boolean[scores.length];
        for (int index = 0; index < selected.length; index++) {
            double maximum = Double.NEGATIVE_INFINITY;
            for (int candidate = 0; candidate < scores.length; candidate++) {
                if (!retained[candidate]) {
                    maximum = Math.max(maximum, scores[candidate]);
                }
            }
            final double minimum = maximum - TIE_TOLERANCE_PER_MEMBER * members;
            for (int candidate = 0; candidate < scores.length; candidate++) {
                if (!retained[candidate] && scores[candidate] >= minimum) {
                    retained[candidate] = true;
                    selected[index] = candidate;
                    break;
                }
            }
        }
        Arrays.sort(selected);
        return selected;
    }

    public double[] scores(float[][] rows, int members) {
        if (members <= 0 || rows.length % members != 0) {
            throw new IllegalArgumentException("Feature rows must contain complete candidates");
        }
        final var scores = new double[rows.length / members];
        final var normalized = new double[model.mean.length];
        for (int row = 0; row < rows.length; row++) {
            if (rows[row] == null || rows[row].length != normalized.length) {
                throw new IllegalArgumentException("RL feature row has the wrong width");
            }
            for (int feature = 0; feature < normalized.length; feature++) {
                final double value = rows[row][feature];
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("RL features must be finite");
                }
                final double transformed = Math.copySign(Math.log1p(Math.abs(value)), value);
                normalized[feature] = (transformed - model.mean[feature]) / model.scale[feature];
            }
            double score = model.outputBias;
            for (int hidden = 0; hidden < HIDDEN_SIZE; hidden++) {
                double activation = model.hiddenBias[hidden];
                for (int feature = 0; feature < normalized.length; feature++) {
                    activation += model.hiddenWeights[hidden][feature] * normalized[feature];
                }
                score += model.outputWeights[hidden] * Math.tanh(activation);
            }
            scores[row / members] += score;
            if (!Double.isFinite(scores[row / members])) {
                throw new IllegalArgumentException("RL model produced a non-finite score");
            }
        }
        return scores;
    }

    private void validate() {
        if (model == null || model.formatVersion != 1 || !RlFeatures.names().equals(model.featureNames)) {
            throw new IllegalArgumentException("RL model version or feature names do not match this runner");
        }
        final int features = RlFeatures.names().size();
        validateVector(model.mean, features, "mean");
        validateVector(model.scale, features, "scale");
        validateVector(model.hiddenBias, HIDDEN_SIZE, "hiddenBias");
        validateVector(model.outputWeights, HIDDEN_SIZE, "outputWeights");
        if (model.hiddenWeights == null || model.hiddenWeights.length != HIDDEN_SIZE) {
            throw new IllegalArgumentException("RL hiddenWeights must contain " + HIDDEN_SIZE + " rows");
        }
        for (final var weights : model.hiddenWeights) {
            validateVector(weights, features, "hiddenWeights row");
        }
        for (double scale : model.scale) {
            if (scale < MIN_SCALE) {
                throw new IllegalArgumentException("RL scales must be at least " + MIN_SCALE);
            }
        }
        if (model.outputBias == null || !Double.isFinite(model.outputBias)) {
            throw new IllegalArgumentException("RL outputBias must be finite");
        }
    }

    private static void validateVector(double[] vector, int expected, String name) {
        if (vector == null || vector.length != expected) {
            throw new IllegalArgumentException("RL " + name + " has the wrong size");
        }
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("RL " + name + " must contain finite values");
            }
        }
    }
}
