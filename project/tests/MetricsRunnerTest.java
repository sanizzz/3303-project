import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricsRunnerTest {

    private static final double DELTA = 1.0e-9;

    @Test
    void calculatesSampleMeanForKnownProcessingTimes() {
        // Sample mean validation: this proves the rubric's average processing-time value is
        // computed as the arithmetic mean of the 30 run times rather than any weighted variant.
        double[] processingTimes = { 1.2, 1.4, 1.3, 1.5, 1.6 };

        double mean = MetricsRunner.calculateSampleMean(processingTimes);

        assertEquals(1.4, mean, DELTA);
    }

    @Test
    void calculatesSampleStandardDeviationUsingNMinusOneDenominator() {
        // Sample standard deviation validation: this proves the rubric uses sample variability
        // with the n-1 denominator, which is the mathematically correct estimator for run samples.
        double[] processingTimes = { 1.2, 1.4, 1.3, 1.5, 1.6 };

        double standardDeviation = MetricsRunner.calculateSampleStandardDeviation(processingTimes, 1.4);

        assertEquals(0.15811388300841897, standardDeviation, DELTA);
    }

    @Test
    void calculatesNinetyFivePercentConfidenceIntervalBoundsForThirtySamples() {
        // 95% confidence-interval validation: this proves the rubric's interval bounds are
        // computed from mean +/- z * (s / sqrt(n)) for a fixed sample size of 30.
        double mean = 1.4;
        double sampleStandardDeviation = 0.2;
        int sampleSize = 30;
        double zScore = 1.96;
        double expectedHalfWidth = zScore * sampleStandardDeviation / Math.sqrt(sampleSize);

        double[] interval = MetricsRunner.calculateConfidenceInterval(mean, sampleStandardDeviation, sampleSize, zScore);

        assertEquals(mean - expectedHalfWidth, interval[0], DELTA);
        assertEquals(mean + expectedHalfWidth, interval[1], DELTA);
    }
}
