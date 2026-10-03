package io.github.neareststep.nexusai.load;

import java.util.Arrays;

/** MSPT samples in milliseconds, as reported by Paper. */
final class DoubleSamples {

    private double[] data = new double[256];
    private int size;

    void add(double value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = value;
    }

    int count() {
        return size;
    }

    double percentile(double fraction) {
        if (size == 0) {
            return 0.0d;
        }
        double[] copy = Arrays.copyOf(data, size);
        Arrays.sort(copy);
        int index = (int) Math.ceil(fraction * size) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= size) {
            index = size - 1;
        }
        return copy[index];
    }

    double max() {
        double peak = 0.0d;
        for (int i = 0; i < size; i++) {
            if (data[i] > peak) {
                peak = data[i];
            }
        }
        return peak;
    }

    double mean() {
        if (size == 0) {
            return 0.0d;
        }
        double sum = 0.0d;
        for (int i = 0; i < size; i++) {
            sum += data[i];
        }
        return sum / size;
    }
}
