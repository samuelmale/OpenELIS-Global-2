package org.openelisglobal.analyzer.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.math.BigDecimal;
import java.util.Date;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzer.service.QCResultDTO;
import org.openelisglobal.analyzer.service.QCResultProcessingService;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.qc.valueholder.QCResult;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for QCResultProcessingService with real QCResultService.
 *
 * <p>
 * Verifies the full pipeline: QCResultDTO → parameter mapping →
 * QCResultService.createQCResult() → persisted QCResult with z-score.
 *
 * <p>
 * Test data loaded via DBUnit from testdata/qc-result.xml:
 * <ul>
 * <li>lot-001: ACTIVE control lot with statistics (mean=100.0, SD=5.0)</li>
 * <li>lot-expired: EXPIRED control lot (for error path testing)</li>
 * <li>lot-no-stats: ACTIVE lot without statistics (for error path testing)</li>
 * </ul>
 */
public class QCResultServiceIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private QCResultProcessingService qcResultProcessingService;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/qc-result.xml");
    }

    /**
     * Verifies the full pipeline: processQCResult() maps ControlLevel enum to
     * String, converts Date to LocalDateTime, delegates to QCResultService which
     * calculates z-score and persists the QCResult.
     *
     * Input: value=110, lot-001 (mean=100, SD=5) → z-score = (110-100)/5 = 2.0
     */
    @Test
    public void testProcessQCResult_WithValidData_PersistsQCResultWithZScore() {
        // Arrange
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-001", QCResultDTO.ControlLevel.NORMAL, new BigDecimal("110.0"),
                "mg/dL", new Date());

        // Act
        Object result = qcResultProcessingService.processQCResult(dto, "1");

        // Assert
        assertNotNull("Should return persisted QCResult", result);
        QCResult qcResult = (QCResult) result;
        assertNotNull("Result ID should be generated", qcResult.getId());
        assertEquals("Result value should be 110.0", 0, new BigDecimal("110.0").compareTo(qcResult.getResultValue()));
        assertEquals("Z-score should be 2.0000 ((110-100)/5)", 0,
                new BigDecimal("2.0000").compareTo(qcResult.getZScore()));
        assertEquals("Control lot ID should match", "lot-001", qcResult.getControlLotId());
        assertEquals("Test ID should be 1", Integer.valueOf(1), qcResult.getTestId());
        assertEquals("Instrument ID should be 1", Integer.valueOf(1), qcResult.getInstrumentId());
        assertEquals("Unit should be mg/dL", "mg/dL", qcResult.getUnitOfMeasure());
        assertEquals("Status should be PENDING", "PENDING", qcResult.getResultStatus());
        assertFalse("Non-conformity flag should be false", qcResult.getNonConformityFlag());
    }

    /**
     * Verifies z-score calculation for a value below the mean.
     *
     * Input: value=90, lot-001 (mean=100, SD=5) → z-score = (90-100)/5 = -2.0
     */
    @Test
    public void testProcessQCResult_WithValueBelowMean_CalculatesNegativeZScore() {
        // Arrange
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-001", QCResultDTO.ControlLevel.NORMAL, new BigDecimal("90.0"),
                "mg/dL", new Date());

        // Act
        QCResult qcResult = (QCResult) qcResultProcessingService.processQCResult(dto, "1");

        // Assert
        assertEquals("Z-score should be -2.0000 ((90-100)/5)", 0,
                new BigDecimal("-2.0000").compareTo(qcResult.getZScore()));
    }

    /**
     * Verifies that LOW control level enum is correctly mapped to String and the
     * full pipeline still works.
     */
    @Test
    public void testProcessQCResult_WithLowControlLevel_MapsAndPersists() {
        // Arrange
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-001", QCResultDTO.ControlLevel.LOW, new BigDecimal("100.0"),
                "mg/dL", new Date());

        // Act
        QCResult qcResult = (QCResult) qcResultProcessingService.processQCResult(dto, "1");

        // Assert
        assertNotNull("Should persist QCResult for LOW control level", qcResult);
        assertNotNull("Result ID should be generated", qcResult.getId());
        assertEquals("Result value should be 100.0", 0, new BigDecimal("100.0").compareTo(qcResult.getResultValue()));
        assertEquals("Z-score should be 0.0000 ((100-100)/5)", 0,
                new BigDecimal("0.0000").compareTo(qcResult.getZScore()));
    }

    /**
     * Verifies that processQCResult() propagates the error when the control lot is
     * EXPIRED. QCResultService rejects non-ACTIVE lots.
     */
    @Test(expected = LIMSRuntimeException.class)
    public void testProcessQCResult_WithExpiredControlLot_ThrowsException() {
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-expired", QCResultDTO.ControlLevel.NORMAL,
                new BigDecimal("100.0"), "mg/dL", new Date());

        qcResultProcessingService.processQCResult(dto, "1");
    }

    /**
     * Verifies that processQCResult() propagates the error when the control lot has
     * no statistics (required for z-score calculation).
     */
    @Test(expected = LIMSRuntimeException.class)
    public void testProcessQCResult_WithMissingStatistics_ThrowsException() {
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-no-stats", QCResultDTO.ControlLevel.NORMAL,
                new BigDecimal("100.0"), "mg/dL", new Date());

        qcResultProcessingService.processQCResult(dto, "1");
    }

    /**
     * Verifies null DTO validation.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testProcessQCResult_WithNullDTO_ThrowsIllegalArgumentException() {
        qcResultProcessingService.processQCResult(null, "1");
    }

    /**
     * Verifies null analyzer ID validation.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testProcessQCResult_WithNullAnalyzerId_ThrowsIllegalArgumentException() {
        QCResultDTO dto = new QCResultDTO("1", "1", "lot-001", QCResultDTO.ControlLevel.NORMAL, new BigDecimal("100.0"),
                "mg/dL", new Date());

        qcResultProcessingService.processQCResult(dto, null);
    }
}
