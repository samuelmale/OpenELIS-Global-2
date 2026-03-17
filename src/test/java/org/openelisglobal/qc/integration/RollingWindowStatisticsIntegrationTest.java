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
 * Integration test for Rolling Window statistics bootstrapping.
 *
 * <p>Scenario: A brand-new control lot configured with calculation_method=ROLLING
 * and window_size=5 (via initial_runs_count) starts in ESTABLISHMENT status with
 * ZERO results and ZERO statistics. We then send 7 ASTM Q-segments through the
 * full pipeline and verify that:
 * <ol>
 *   <li>All 7 QC results are persisted with correct values</li>
 *   <li>Results 1-5 have null z-scores (establishment phase, no statistics yet)</li>
 *   <li>After result 5, statistics are bootstrapped and the lot transitions to ACTIVE</li>
 *   <li>Results 6-7 have exact z-scores computed from rolling statistics</li>
 *   <li>Final rolling statistics (window of results 3-7) have correct mean and SD</li>
 * </ol>
 */
public class RollingWindowStatisticsIntegrationTest extends BaseWebContextSensitiveTest {

    private static final String CONTROL_LOT_ID = "lot-rolling";
    private static final String LOT_CODE = "LOT-ROLLING-001";
    private static final int WINDOW_SIZE = 5;

    /** 7 QC result values to send through the ASTM pipeline, in insertion order. */
    private static final String[] QC_VALUES = {
        "100.0", "102.0", "98.0", "101.0", "99.0", "104.0", "96.0"
    };

    /*
     * Rolling window size = 5.  After 7 results, the most recent 5 are
     * (by ascending runDateTime — indices 2..6): 98.0, 101.0, 99.0, 104.0, 96.0
     *
     * Expected mean  = (98 + 101 + 99 + 104 + 96) / 5 = 498 / 5 = 99.6000
     *
     * RollingCalculator uses POPULATION standard deviation (divides by n, not n-1):
     *   variance = ( (-1.6)^2 + (1.4)^2 + (-0.6)^2 + (4.4)^2 + (-3.6)^2 ) / 5
     *            = (  2.56   +  1.96   +  0.36   + 19.36   + 12.96  ) / 5
     *            = 37.20 / 5 = 7.4400
     *   SD = sqrt(7.44) = 2.7276  (4 decimal places, HALF_UP)
     */
    private static final BigDecimal EXPECTED_MEAN = new BigDecimal("99.6000");
    private static final BigDecimal EXPECTED_SD = new BigDecimal("2.7276");

    /*
     * After result 5 triggers bootstrap, stats are computed from results 1-5:
     *   values: 100.0, 102.0, 98.0, 101.0, 99.0  →  mean=100.0000, SD=1.4142
     *
     * Result 6 (104.0) z-score = (104.0 - 100.0) / 1.4142 = 2.8285
     *
     * After result 6, rolling recalculation uses results 2-6 (most recent 5):
     *   values: 102.0, 98.0, 101.0, 99.0, 104.0  →  mean=100.8000, SD=2.1354
     *
     * Result 7 (96.0) z-score = (96.0 - 100.8) / 2.1354 = -2.2478
     */
    private static final BigDecimal EXPECTED_ZSCORE_RESULT6 = new BigDecimal("2.8285");
    private static final BigDecimal EXPECTED_ZSCORE_RESULT7 = new BigDecimal("-2.2478");

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
        executeDataSetWithStateManagement("testdata/qc-rolling-window.xml");

        testAnalyzer = analyzerService.get("1");
        assertNotNull("Analyzer id=1 should be loaded from dataset", testAnalyzer);

        // Precondition: lot starts with zero results and zero statistics
        List<QCResult> preExisting = qcResultDAO.findByControlLot(CONTROL_LOT_ID);
        assertEquals("Precondition: lot should have 0 results", 0, preExisting.size());
        assertNull("Precondition: lot should have no statistics",
                statisticsDAO.findLatestByControlLot(CONTROL_LOT_ID));
    }

    /**
     * Send 7 ASTM Q-segments to a ROLLING lot that starts in ESTABLISHMENT status
     * with zero results and zero statistics. Verify that the system bootstraps
     * rolling statistics from raw ASTM data and transitions the lot to ACTIVE.
     */
    @Test
    public void rollingWindowLot_astmResultsBootstrapStatistics_andComputeCorrectMeanAndSD() {
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

        // ── Phase 4: Verify rolling statistics computed correctly ─────────
        QCStatistics latestStats = statisticsDAO.findLatestByControlLot(CONTROL_LOT_ID);
        assertNotNull("Rolling statistics should have been computed after >= 5 results", latestStats);

        assertEquals("Statistics controlLotId should match",
                CONTROL_LOT_ID, latestStats.getControlLotId());
        assertEquals("Calculation method should be ROLLING",
                "ROLLING", latestStats.getCalculationMethod());
        assertEquals("Window should contain exactly " + WINDOW_SIZE + " values",
                Integer.valueOf(WINDOW_SIZE), latestStats.getNumValues());

        // Verify mean: (98 + 101 + 99 + 104 + 96) / 5 = 498 / 5 = 99.6000
        BigDecimal actualMean = latestStats.getMean().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("Mean should be %s but was %s", EXPECTED_MEAN, actualMean),
                0, EXPECTED_MEAN.compareTo(actualMean));

        // Verify SD (population): sqrt(37.2 / 5) = sqrt(7.44) = 2.7276
        BigDecimal actualSD = latestStats.getStandardDeviation().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("SD should be %s but was %s", EXPECTED_SD, actualSD),
                0, EXPECTED_SD.compareTo(actualSD));

        // ── Phase 5: Verify z-scores ─────────────────────────────────────
        // Results 1-5 (indices 0-4) are in the establishment phase:
        // statistics don't exist until AFTER result 5 triggers bootstrap,
        // so all 5 results are persisted with null z-scores.
        for (int i = 0; i < WINDOW_SIZE; i++) {
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
        BigDecimal actualZScore6 = sixthResult.getZScore().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("6th result z-score should be %s but was %s",
                        EXPECTED_ZSCORE_RESULT6, actualZScore6),
                0, EXPECTED_ZSCORE_RESULT6.compareTo(actualZScore6));

        // Result 7 (96.0): rolling stats updated after result 6 (mean=100.8, SD=2.1354)
        // z-score = (96.0 - 100.8) / 2.1354 = -2.2478
        QCResult seventhResult = results.get(6);
        assertEquals("7th result value should be 96.0",
                0, new BigDecimal("96.0").compareTo(seventhResult.getResultValue()));
        BigDecimal actualZScore7 = seventhResult.getZScore().setScale(4, RoundingMode.HALF_UP);
        assertEquals(
                String.format("7th result z-score should be %s but was %s",
                        EXPECTED_ZSCORE_RESULT7, actualZScore7),
                0, EXPECTED_ZSCORE_RESULT7.compareTo(actualZScore7));
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
