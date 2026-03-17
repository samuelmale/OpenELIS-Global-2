package org.openelisglobal.qc.integration;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.service.MappingAwareAnalyzerLineInserter;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzerimport.analyzerreaders.AnalyzerLineInserter;
import org.openelisglobal.qc.dao.QCControlLotDAO;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.dao.QCStatisticsDAO;
import org.openelisglobal.qc.valueholder.QCControlLot;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCStatistics;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test for Initial Runs statistics bootstrapping.
 *
 * <p>Scenario: A brand-new control lot configured with calculation_method=INITIAL_RUNS
 * and initial_runs_count=5 starts in ESTABLISHMENT status with ZERO results and ZERO
 * statistics. We then send 7 ASTM Q-segments through the full pipeline and verify that:
 * <ol>
 *   <li>All 7 QC results are persisted with correct values</li>
 *   <li>Results 1-5 have null z-scores (establishment phase, no statistics yet)</li>
 *   <li>After result 5, statistics are bootstrapped and the lot transitions to ACTIVE</li>
 *   <li>Results 6-7 have z-scores computed from the SAME bootstrap statistics
 *       (unlike ROLLING, INITIAL_RUNS does NOT recalculate on subsequent results)</li>
 *   <li>Final statistics are identical to bootstrap statistics (mean and SD unchanged)</li>
 * </ol>
 *
 * <p>Key difference from ROLLING: After bootstrapping, INITIAL_RUNS statistics remain
 * fixed. Both results 6 and 7 use the same mean and SD computed from the first 5 results.
 */
public class InitialRunsStatisticsIntegrationTest extends BaseWebContextSensitiveTest {

    private static final String CONTROL_LOT_ID = "lot-initial-runs";
    private static final String LOT_CODE = "LOT-INITIAL-001";
    private static final int INITIAL_RUNS_COUNT = 5;

    /** 7 QC result values to send through the ASTM pipeline, in insertion order. */
    private static final String[] QC_VALUES = {
        "100.0", "102.0", "98.0", "101.0", "99.0", "104.0", "96.0"
    };

    /*
     * INITIAL_RUNS with initial_runs_count=5.  Statistics are computed from the
     * first 5 results and remain FIXED thereafter:
     *   values: 100.0, 102.0, 98.0, 101.0, 99.0
     *   mean = (100 + 102 + 98 + 101 + 99) / 5 = 500 / 5 = 100.0000
     *
     * InitialRunsCalculator uses POPULATION standard deviation (divides by n):
     *   variance = ((0)^2 + (2)^2 + (-2)^2 + (1)^2 + (-1)^2) / 5
     *            = (0 + 4 + 4 + 1 + 1) / 5 = 10 / 5 = 2.0000
     *   SD = sqrt(2.0) = 1.4142
     */
    private static final BigDecimal EXPECTED_MEAN = new BigDecimal("100.0000");
    private static final BigDecimal EXPECTED_SD = new BigDecimal("1.4142");

    /*
     * Unlike ROLLING, INITIAL_RUNS does NOT recalculate statistics after bootstrap.
     * Both result 6 and result 7 use the same bootstrap statistics (mean=100.0, SD=1.4142):
     *
     * Result 6 (104.0): z = (104.0 - 100.0) / 1.4142 = 4.0 / 1.4142 = 2.8285
     * Result 7 (96.0):  z = (96.0 - 100.0)  / 1.4142 = -4.0 / 1.4142 = -2.8285
     *
     * Note: In the ROLLING test, result 7 uses DIFFERENT statistics (recalculated
     * after result 6), giving z = -2.2478. Here both results use the SAME statistics.
     */
    private static final BigDecimal EXPECTED_ZSCORE_RESULT6 = new BigDecimal("2.8285");
    private static final BigDecimal EXPECTED_ZSCORE_RESULT7 = new BigDecimal("-2.8285");

    @Autowired
    private AnalyzerService analyzerService;

    @Autowired
    private QCResultDAO qcResultDAO;

    @Autowired
    private QCStatisticsDAO statisticsDAO;

    @Autowired
    private QCControlLotDAO controlLotDAO;

    private Analyzer testAnalyzer;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/qc-initial-runs.xml");

        testAnalyzer = analyzerService.get("1");
        assertNotNull("Analyzer id=1 should be loaded from dataset", testAnalyzer);

        // Precondition: lot starts with zero results and zero statistics
        List<QCResult> preExisting = qcResultDAO.findByControlLot(CONTROL_LOT_ID);
        assertEquals("Precondition: lot should have 0 results", 0, preExisting.size());
        assertNull("Precondition: lot should have no statistics",
                statisticsDAO.findLatestByControlLot(CONTROL_LOT_ID));
    }

    /**
     * Send 7 ASTM Q-segments to an INITIAL_RUNS lot that starts in ESTABLISHMENT
     * status with zero results and zero statistics. Verify that the system bootstraps
     * statistics from the first 5 results, transitions the lot to ACTIVE, and then
     * does NOT recalculate statistics for subsequent results.
     */
    @Test
    public void initialRunsLot_astmResultsBootstrapStatistics_andStatisticsRemainFixed() {
        // ── Phase 1: Send 7 ASTM messages through the full pipeline ──────
        long timestamp = 20250615100000L;
        for (int i = 0; i < QC_VALUES.length; i++) {
            AnalyzerLineInserter mockInserter = createMockInserter(true);
            MappingAwareAnalyzerLineInserter wrapper =
                    new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

            String qSegment = String.format(
                    "Q|1|GLU^%s^N|%s|mg/dL|%d", LOT_CODE, QC_VALUES[i], timestamp + (i * 10000L));

            List<String> lines = Arrays.asList(
                    "H|\\^&|||TEST^BioRad^1.0|||||||P",
                    qSegment,
                    "L|1|N");

            wrapper.insert(lines, "1");
        }

        // ── Phase 2: Verify all 7 QC results persisted with correct values ──
        // Use ASC-ordered query so index 0 = oldest, index 6 = newest
        List<QCResult> results = qcResultDAO.findByControlLotIdOrderByRunDateTime(CONTROL_LOT_ID);
        assertEquals("All 7 QC results should be persisted", 7, results.size());

        // Deep verification: each result's value must match the ASTM value sent
        for (int i = 0; i < QC_VALUES.length; i++) {
            BigDecimal expected = new BigDecimal(QC_VALUES[i]);
            BigDecimal actual = results.get(i).getResultValue();
            assertEquals(
                    String.format("Result %d value should be %s but was %s", i + 1, QC_VALUES[i], actual),
                    0, expected.compareTo(actual));
            assertEquals(
                    "Result " + (i + 1) + " controlLotId should be " + CONTROL_LOT_ID,
                    CONTROL_LOT_ID, results.get(i).getControlLotId());
        }

        // ── Phase 3: Verify lot transitioned from ESTABLISHMENT → ACTIVE ──
        Optional<QCControlLot> lotAfter = controlLotDAO.get(CONTROL_LOT_ID);
        assertEquals("Lot status should be ACTIVE after bootstrapping",
                "ACTIVE", lotAfter.get().getStatus());

        // ── Phase 4: Verify statistics computed correctly from first 5 results ──
        QCStatistics latestStats = statisticsDAO.findLatestByControlLot(CONTROL_LOT_ID);
        assertNotNull("Statistics should have been computed after 5 results", latestStats);

        assertEquals("Statistics controlLotId should match",
                CONTROL_LOT_ID, latestStats.getControlLotId());
        assertEquals("Calculation method should be INITIAL_RUNS",
                "INITIAL_RUNS", latestStats.getCalculationMethod());
        assertEquals("numValues should be " + INITIAL_RUNS_COUNT + " (initial runs count)",
                Integer.valueOf(INITIAL_RUNS_COUNT), latestStats.getNumValues());

        // Verify mean: (100 + 102 + 98 + 101 + 99) / 5 = 100.0000
        BigDecimal actualMean = latestStats.getMean().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("Mean should be %s but was %s", EXPECTED_MEAN, actualMean),
                0, EXPECTED_MEAN.compareTo(actualMean));

        // Verify SD (population): sqrt(10 / 5) = sqrt(2) = 1.4142
        BigDecimal actualSD = latestStats.getStandardDeviation().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("SD should be %s but was %s", EXPECTED_SD, actualSD),
                0, EXPECTED_SD.compareTo(actualSD));

        // ── Phase 5: Verify z-scores ─────────────────────────────────────
        // Results 1-5 (indices 0-4) are in the establishment phase:
        // no statistics exist yet, so all 5 are persisted with null z-scores.
        for (int i = 0; i < INITIAL_RUNS_COUNT; i++) {
            assertNull(
                    String.format("Result %d (%s) is in establishment phase — z-score should be null",
                            i + 1, QC_VALUES[i]),
                    results.get(i).getZScore());
        }

        // Result 6 (104.0): lot is now ACTIVE with bootstrap stats (mean=100.0, SD=1.4142)
        // z-score = (104.0 - 100.0) / 1.4142 = 2.8285
        QCResult sixthResult = results.get(5);
        assertEquals("6th result value should be 104.0",
                0, new BigDecimal("104.0").compareTo(sixthResult.getResultValue()));
        assertNotNull("6th result should have a z-score (lot is ACTIVE)", sixthResult.getZScore());
        BigDecimal actualZScore6 = sixthResult.getZScore().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("6th result z-score should be %s but was %s",
                        EXPECTED_ZSCORE_RESULT6, actualZScore6),
                0, EXPECTED_ZSCORE_RESULT6.compareTo(actualZScore6));

        // Result 7 (96.0): INITIAL_RUNS does NOT recalculate — same stats as result 6
        // z-score = (96.0 - 100.0) / 1.4142 = -2.8285
        QCResult seventhResult = results.get(6);
        assertEquals("7th result value should be 96.0",
                0, new BigDecimal("96.0").compareTo(seventhResult.getResultValue()));
        assertNotNull("7th result should have a z-score (lot is ACTIVE)", seventhResult.getZScore());
        BigDecimal actualZScore7 = seventhResult.getZScore().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("7th result z-score should be %s but was %s",
                        EXPECTED_ZSCORE_RESULT7, actualZScore7),
                0, EXPECTED_ZSCORE_RESULT7.compareTo(actualZScore7));

        // ── Phase 6: Verify statistics remain FIXED (key INITIAL_RUNS behavior) ──
        // Unlike ROLLING, statistics should NOT be recalculated after results 6-7.
        // The mean and SD should still be from the original first 5 results.
        QCStatistics finalStats = statisticsDAO.findLatestByControlLot(CONTROL_LOT_ID);
        BigDecimal finalMean = finalStats.getMean().setScale(4, RoundingMode.HALF_UP);
        BigDecimal finalSD = finalStats.getStandardDeviation().setScale(4, RoundingMode.HALF_UP);

        assertEquals("Final mean should still be 100.0000 (not recalculated)",
                0, EXPECTED_MEAN.compareTo(finalMean));
        assertEquals("Final SD should still be 1.4142 (not recalculated)",
                0, EXPECTED_SD.compareTo(finalSD));
        assertEquals("Final numValues should still be 5 (not updated)",
                Integer.valueOf(INITIAL_RUNS_COUNT), finalStats.getNumValues());
    }

    // ── Helper methods ──────────────────────────────────────────────────

    private AnalyzerLineInserter createMockInserter(boolean returnValue) {
        return new AnalyzerLineInserter() {
            @Override
            public boolean insert(List<String> lines, String currentUserId) {
                return returnValue;
            }

            @Override
            public String getError() {
                return null;
            }
        };
    }
}
