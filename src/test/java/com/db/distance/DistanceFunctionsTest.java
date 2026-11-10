package com.db.distance;

import com.db.config.DistanceMetric;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistanceFunctionsTest {

    private static final double EPS = 1e-9;

    @Test
    void cosine() {
        assertEquals(0.0, DistanceFunctions.cosine(new double[]{1, 0}, new double[]{2, 0}), EPS);
        assertEquals(1.0, DistanceFunctions.cosine(new double[]{1, 0}, new double[]{0, 3}), EPS);
        assertEquals(2.0, DistanceFunctions.cosine(new double[]{1, 0}, new double[]{-1, 0}), EPS);
        assertEquals(1.0, DistanceFunctions.cosine(new double[]{0, 0}, new double[]{1, 1}), EPS);
    }

    @Test
    void euclidean() {
        assertEquals(5.0, DistanceFunctions.euclidean(new double[]{0, 0}, new double[]{3, 4}), EPS);
    }

    @Test
    void dotProductIsNegatedSoSmallerIsCloser() {
        assertEquals(-11.0, DistanceFunctions.dotProduct(new double[]{1, 2}, new double[]{3, 4}), EPS);
    }

    @Test
    void differentLengthsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DistanceFunctions.compute(new double[]{1}, new double[]{1, 2}, DistanceMetric.COSINE));
    }
}
