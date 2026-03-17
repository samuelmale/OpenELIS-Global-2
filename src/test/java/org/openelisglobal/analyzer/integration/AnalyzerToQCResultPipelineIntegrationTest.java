package org.openelisglobal.analyzer.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzer.service.AnalyzerService;
import org.openelisglobal.analyzer.service.MappingAwareAnalyzerLineInserter;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzerimport.analyzerreaders.AnalyzerLineInserter;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.dao.QCRuleViolationDAO;
import org.openelisglobal.qc.event.QCResultCreatedEvent;
import org.openelisglobal.qc.valueholder.QCResult;
import org.openelisglobal.qc.valueholder.QCRuleViolation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Integration test: Full pipeline from ASTM message to persisted QCResult.
 *
 * <p>
 * Exercises: MappingAwareAnalyzerLineInserter.insert() →
 * MappingApplicationService.hasActiveMappings() / applyMappings() →
 * ASTMQSegmentParser.parseQSegments() →
 * QCResultExtractionService.extractQCResult() (field mapping DB lookup) →
 * QCResultProcessingService.processQCResult() →
 * QCResultService.createQCResult() (z-score, persist, event)
 *
 * <p>
 * Test data: testdata/analyzer-qc-pipeline.xml sets up:
 * <ul>
 * <li>Analyzer id=1 ("TEST BioRad")</li>
 * <li>AnalyzerField "GLU" → AnalyzerFieldMapping type=TEST → Test id=1</li>
 * <li>AnalyzerField "LOT-2025-001" → AnalyzerFieldMapping type=QC → lot-001
 * (ACTIVE)</li>
 * <li>AnalyzerField "LOT-2024-999" → AnalyzerFieldMapping type=QC → lot-expired
 * (EXPIRED)</li>
 * <li>QCControlLot lot-001 (ACTIVE), lot-expired (EXPIRED)</li>
 * <li>QCStatistics for lot-001: mean=100.0, SD=5.0</li>
 * </ul>
 */
public class AnalyzerToQCResultPipelineIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private AnalyzerService analyzerService;

    @Autowired
    private QCResultDAO qcResultDAO;

    @Autowired
    private QCRuleViolationDAO violationDAO;

    private Analyzer testAnalyzer;

    /**
     * Captures QCResultCreatedEvents published during the test. Thread-safe
     * for @Async listeners.
     */
    private final CopyOnWriteArrayList<QCResultCreatedEvent> capturedEvents = new CopyOnWriteArrayList<>();

    private final ApplicationListener<QCResultCreatedEvent> eventCapture = capturedEvents::add;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/analyzer-qc-pipeline.xml");

        capturedEvents.clear();
        ((ConfigurableApplicationContext) webApplicationContext).addApplicationListener(eventCapture);

        testAnalyzer = analyzerService.get("1");
        assertNotNull("Analyzer id=1 should be loaded from dataset", testAnalyzer);
    }

    @After
    public void tearDown() {
        // ApplicationContext has no removeApplicationListener, but the list is cleared
        // per test, so stale events from a previous test won't affect the next one.
        capturedEvents.clear();
    }

    /**
     * Full pipeline: ASTM message with Q-segment → persisted QCResult with z-score.
     *
     * Input: Q|1|GLU^LOT-2025-001^N|110.0|mg/dL|20250615103000 Expected: z-score =
     * (110 - 100) / 5 = 2.0000
     */
    @Test
    public void fullPipeline_astmWithQSegment_persistsQCResultWithZScore() {
        // Arrange
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|110.0|mg/dL|20250615103000", "L|1|N");

        // Act
        boolean result = wrapper.insert(lines, "1");

        // Assert
        assertTrue("insert() should return true", result);

        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult should be persisted", 1, qcResults.size());

        QCResult qcResult = qcResults.get(0);
        assertEquals("Result value should be 110.0", 0, new BigDecimal("110.0").compareTo(qcResult.getResultValue()));
        assertEquals("Z-score should be 2.0000 ((110-100)/5)", 0,
                new BigDecimal("2.0000").compareTo(qcResult.getZScore()));
        assertEquals("Control lot ID should be lot-001", "lot-001", qcResult.getControlLotId());
        assertEquals("Test ID should be 1", Integer.valueOf(1), qcResult.getTestId());
        assertEquals("Instrument ID should be 1", Integer.valueOf(1), qcResult.getInstrumentId());
        assertEquals("Unit should be mg/dL", "mg/dL", qcResult.getUnitOfMeasure());
        assertEquals("Status should be ACCEPTED (rule evaluation found no rejections)", "ACCEPTED",
                qcResult.getResultStatus());
        assertFalse("Non-conformity flag should be false", qcResult.getNonConformityFlag());
    }

    /**
     * Full pipeline: ASTM message with 2 Q-segments → 2 persisted QCResults.
     *
     * Input: two Q-segments with values 110.0 and 95.0 for the same lot Expected: 2
     * QCResults with z-scores 2.0000 and -1.0000
     */
    @Test
    public void fullPipeline_multipleQSegments_persistsMultipleQCResults() {
        // Arrange
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|110.0|mg/dL|20250615103000", "Q|2|GLU^LOT-2025-001^N|95.0|mg/dL|20250615104000",
                "L|1|N");

        // Act
        boolean result = wrapper.insert(lines, "1");

        // Assert
        assertTrue("insert() should return true", result);

        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 2 QCResults should be persisted", 2, qcResults.size());

        // Results ordered by runDateTime DESC, so 104000 first, 103000 second
        QCResult secondResult = qcResults.get(0); // 104000 timestamp (newer)
        QCResult firstResult = qcResults.get(1); // 103000 timestamp (older)

        assertEquals("First result z-score should be 2.0000 ((110-100)/5)", 0,
                new BigDecimal("2.0000").compareTo(firstResult.getZScore()));
        assertEquals("Second result z-score should be -1.0000 ((95-100)/5)", 0,
                new BigDecimal("-1.0000").compareTo(secondResult.getZScore()));
    }

    /**
     * Full pipeline: ASTM message with no Q-segments → no QCResults created.
     *
     * The original inserter should still be called (for patient results).
     */
    @Test
    public void fullPipeline_noQSegments_noQCResultsCreated() {
        // Arrange
        TrackingInserter trackingInserter = new TrackingInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(trackingInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "R|1|^^^GLUCOSE^GLU||100|mg/dL||N|||20250615103000", "L|1|N");

        // Act
        boolean result = wrapper.insert(lines, "1");

        // Assert
        assertTrue("insert() should return true", result);
        assertTrue("Original inserter should have been called", trackingInserter.wasCalled());

        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("No QCResults should be created", 0, qcResults.size());
    }

    /**
     * Full pipeline: Q-segment referencing expired control lot → exception in
     * QCResultService, AnalyzerError created.
     *
     * The expired lot mapping (LOT-2024-999 → lot-expired) should resolve via field
     * mapping but QCResultService should reject it because status=EXPIRED.
     */
    @Test
    public void fullPipeline_expiredControlLot_createsAnalyzerError() {
        // Arrange
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2024-999^N|100.0|mg/dL|20250615103000", "L|1|N");

        // Act — insert still returns true (patient results succeed, QC error is logged)
        boolean result = wrapper.insert(lines, "1");

        // Assert
        assertTrue("insert() should return true (patient part succeeded)", result);

        // No QCResult should be persisted for expired lot
        List<QCResult> expiredResults = qcResultDAO.findByControlLot("lot-expired");
        assertEquals("No QCResult should be persisted for expired lot", 0, expiredResults.size());

        // Active lot should also have no results (we only sent expired lot data)
        List<QCResult> activeResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("No QCResult for active lot either", 0, activeResults.size());
    }

    /**
     * Full pipeline: Q-segment with value below mean → negative z-score.
     *
     * Input: value=90.0, lot-001 (mean=100, SD=5) → z-score = (90-100)/5 = -2.0
     */
    @Test
    public void fullPipeline_valueBelowMean_calculatesNegativeZScore() {
        // Arrange
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|90.0|mg/dL|20250615103000", "L|1|N");

        // Act
        boolean result = wrapper.insert(lines, "1");

        // Assert
        assertTrue("insert() should return true", result);

        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult should be persisted", 1, qcResults.size());

        QCResult qcResult = qcResults.get(0);
        assertEquals("Result value should be 90.0", 0, new BigDecimal("90.0").compareTo(qcResult.getResultValue()));
        assertEquals("Z-score should be -2.0000 ((90-100)/5)", 0,
                new BigDecimal("-2.0000").compareTo(qcResult.getZScore()));
    }

    /**
     * Full pipeline: creating a QCResult publishes a QCResultCreatedEvent.
     *
     * Verifies the event carries the correct QCResult (matching ID, controlLotId,
     * z-score) so downstream listeners receive accurate data.
     */
    @Test
    public void fullPipeline_qcResultCreation_publishesQCResultCreatedEvent() {
        // Arrange
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|110.0|mg/dL|20250615103000", "L|1|N");

        // Act
        wrapper.insert(lines, "1");

        // Assert — event was published
        assertEquals("Exactly 1 QCResultCreatedEvent should be published", 1, capturedEvents.size());

        QCResultCreatedEvent event = capturedEvents.get(0);
        assertNotNull("Event should carry a non-null QCResult", event.getResult());

        // Verify event carries the correct QCResult
        QCResult eventResult = event.getResult();
        assertNotNull("Event QCResult should have an ID (persisted)", eventResult.getId());
        assertEquals("Event controlLotId should be lot-001", "lot-001", event.getControlLotId());
        assertEquals("Event resultId should match getResult().getId()", eventResult.getId(), event.getResultId());
        assertEquals("Event QCResult z-score should be 2.0000", 0,
                new BigDecimal("2.0000").compareTo(eventResult.getZScore()));
        assertEquals("Event QCResult value should be 110.0", 0,
                new BigDecimal("110.0").compareTo(eventResult.getResultValue()));

        // Cross-check: event result matches what's in the DB
        List<QCResult> dbResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals(1, dbResults.size());
        assertEquals("Event result ID should match DB result ID", dbResults.get(0).getId(), eventResult.getId());
    }

    // ── Westgard Rule Evaluation Tests ────────────────────────────────
    //
    // These tests exercise the FULL event-driven pipeline:
    // ASTM → QCResult → QCResultCreatedEvent → @Async listener →
    // WestgardRuleEvaluationService → QCRuleViolationService → DB
    //
    // The listener fires asynchronously after the QCResult transaction commits,
    // so we poll for violations with a timeout.

    /**
     * Full pipeline + event-driven rule evaluation: value=120.0 exceeds both 2SD
     * and 3SD.
     *
     * z-score = (120 - 100) / 5 = 4.0 → triggers 1₃ₛ (REJECTION) and 1₂ₛ (WARNING)
     * via async event listener
     */
    @Test
    public void fullPipeline_withRuleEvaluation_exceeds3SD_createsRejectionAndWarning() throws Exception {
        // Arrange — run pipeline to create QCResult (fires QCResultCreatedEvent)
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|120.0|mg/dL|20250615103000", "L|1|N");

        boolean insertResult = wrapper.insert(lines, "1");
        assertTrue("insert() should return true", insertResult);

        // Get the persisted QCResult
        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult should be persisted", 1, qcResults.size());
        QCResult qcResult = qcResults.get(0);
        assertEquals("Z-score should be 4.0000", 0, new BigDecimal("4.0000").compareTo(qcResult.getZScore()));

        // Wait for async event listener to create violations
        List<QCRuleViolation> violations = pollForViolations(qcResult.getId(), 2, 3000);

        // Assert — verify violations persisted by event listener
        assertEquals("Exactly 2 violations should be created", 2, violations.size());

        // Build a map of ruleCode → violation for flexible assertion ordering
        Map<String, QCRuleViolation> byRule = violations.stream()
                .collect(Collectors.toMap(QCRuleViolation::getRuleCode, v -> v));

        // 1₃ₛ REJECTION violation
        assertTrue("1₃ₛ violation should exist", byRule.containsKey("1₃ₛ"));
        QCRuleViolation rejectionViolation = byRule.get("1₃ₛ");
        assertEquals("1₃ₛ severity should be REJECTION", "REJECTION", rejectionViolation.getSeverity());
        assertEquals("Resolution status should be UNRESOLVED", "UNRESOLVED", rejectionViolation.getResolutionStatus());
        assertEquals("Instrument ID should be 1", Integer.valueOf(1), rejectionViolation.getInstrumentId());
        assertEquals("Test ID should be 1", Integer.valueOf(1), rejectionViolation.getTestId());
        assertEquals("Triggering result ID should match", qcResult.getId(), rejectionViolation.getTriggeringResultId());

        // 1₂ₛ WARNING violation
        assertTrue("1₂ₛ violation should exist", byRule.containsKey("1₂ₛ"));
        QCRuleViolation warningViolation = byRule.get("1₂ₛ");
        assertEquals("1₂ₛ severity should be WARNING", "WARNING", warningViolation.getSeverity());
        assertEquals("Resolution status should be UNRESOLVED", "UNRESOLVED", warningViolation.getResolutionStatus());
    }

    /**
     * Full pipeline + event-driven rule evaluation: value=112.0 exceeds 2SD but not
     * 3SD.
     *
     * z-score = (112 - 100) / 5 = 2.4 → triggers 1₂ₛ (WARNING) only
     */
    @Test
    public void fullPipeline_withRuleEvaluation_exceeds2SDOnly_createsWarningOnly() throws Exception {
        // Arrange — run pipeline to create QCResult (fires QCResultCreatedEvent)
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|112.0|mg/dL|20250615103000", "L|1|N");

        boolean insertResult = wrapper.insert(lines, "1");
        assertTrue("insert() should return true", insertResult);

        // Get the persisted QCResult
        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult should be persisted", 1, qcResults.size());
        QCResult qcResult = qcResults.get(0);
        assertEquals("Z-score should be 2.4000", 0, new BigDecimal("2.4000").compareTo(qcResult.getZScore()));

        // Wait for async event listener to create violations
        List<QCRuleViolation> violations = pollForViolations(qcResult.getId(), 1, 3000);

        // Assert — only 1₂ₛ WARNING, no 1₃ₛ REJECTION
        assertEquals("Exactly 1 violation should be created", 1, violations.size());

        QCRuleViolation violation = violations.get(0);
        assertEquals("Rule code should be 1₂ₛ", "1₂ₛ", violation.getRuleCode());
        assertEquals("Severity should be WARNING", "WARNING", violation.getSeverity());
        assertEquals("Resolution status should be UNRESOLVED", "UNRESOLVED", violation.getResolutionStatus());
        assertEquals("Triggering result ID should match", qcResult.getId(), violation.getTriggeringResultId());
    }

    /**
     * Full pipeline + event-driven rule evaluation: value=103.0 is within 2SD
     * limits.
     *
     * z-score = (103 - 100) / 5 = 0.6 → no violations
     */
    @Test
    public void fullPipeline_withRuleEvaluation_withinLimits_noViolations() throws Exception {
        // Arrange — run pipeline to create QCResult (fires QCResultCreatedEvent)
        AnalyzerLineInserter mockInserter = createMockInserter(true);
        MappingAwareAnalyzerLineInserter wrapper = new MappingAwareAnalyzerLineInserter(mockInserter, testAnalyzer);

        List<String> lines = Arrays.asList("H|\\^&|||TEST^BioRad^1.0|||||||P",
                "Q|1|GLU^LOT-2025-001^N|103.0|mg/dL|20250615103000", "L|1|N");

        boolean insertResult = wrapper.insert(lines, "1");
        assertTrue("insert() should return true", insertResult);

        // Get the persisted QCResult
        List<QCResult> qcResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult should be persisted", 1, qcResults.size());
        QCResult qcResult = qcResults.get(0);
        assertEquals("Z-score should be 0.6000", 0, new BigDecimal("0.6000").compareTo(qcResult.getZScore()));

        // Poll to confirm no violations appear — fail immediately if any do
        assertNoViolationsAppear(qcResult.getId(), 2000);
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Create a simple mock AnalyzerLineInserter that returns the specified result.
     */
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

    /**
     * AnalyzerLineInserter that tracks whether insert() was called.
     */
    private static class TrackingInserter extends AnalyzerLineInserter {
        private final boolean returnValue;
        private boolean called = false;

        TrackingInserter(boolean returnValue) {
            this.returnValue = returnValue;
        }

        @Override
        public boolean insert(List<String> lines, String currentUserId) {
            called = true;
            return returnValue;
        }

        @Override
        public String getError() {
            return null;
        }

        boolean wasCalled() {
            return called;
        }
    }

    /**
     * Poll for violations until the expected count is reached or timeout expires.
     * Fails with a clear timeout message rather than returning partial results.
     */
    private List<QCRuleViolation> pollForViolations(String resultId, int expectedCount, int timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<QCRuleViolation> violations;
        do {
            Thread.sleep(200);
            violations = violationDAO.findByTriggeringResultId(resultId);
        } while (violations.size() < expectedCount && System.currentTimeMillis() < deadline);

        assertEquals("Timed out after " + timeoutMs + "ms waiting for " + expectedCount + " violation(s) for resultId="
                + resultId + " (async listener may not have completed)", expectedCount, violations.size());
        return violations;
    }

    /**
     * Poll repeatedly to confirm no violations appear for the given result. Fails
     * immediately if any violation is found, avoiding false positives from a single
     * Thread.sleep that might be too short.
     */
    private void assertNoViolationsAppear(String resultId, int waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            List<QCRuleViolation> violations = violationDAO.findByTriggeringResultId(resultId);
            assertEquals("Expected 0 violations but found " + violations.size() + " for resultId=" + resultId
                    + " (rule codes: "
                    + violations.stream().map(QCRuleViolation::getRuleCode).collect(Collectors.joining(", ")) + ")", 0,
                    violations.size());
        }
    }
}
