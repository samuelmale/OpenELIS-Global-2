package org.openelisglobal.analyzer.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.openelisglobal.analyzer.dao.AnalyzerDAO;
import org.openelisglobal.analyzer.valueholder.Analyzer;
import org.openelisglobal.analyzer.valueholder.AnalyzerError;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.qc.service.QCResultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of QCResultProcessingService for processing QC results from
 * ASTM Q-segments
 * 
 * 
 * This service coordinates the complete QC result processing workflow: (1)
 * Receives QCResultDTO from QCResultExtractionService (after Q-segment parsing
 * and mapping), (2) Calls Feature 003's QCResultService.createQCResult() to
 * persist the QC result, (3) Returns the created QCResult entity.
 * 
 * Transaction Boundary: Uses @Transactional with REQUIRED propagation to ensure
 * it runs within the same transaction as patient result processing. This
 * ensures atomicity: both patient and QC results are persisted together or both
 * fail together (per FR-021 requirement: "within the same transaction").
 * 
 * Integration Pattern: Direct service call from Feature 004's message
 * processing service to Feature 003's QCResultService.createQCResult() method.
 * This ensures immediate consistency and follows the 5-layer architecture
 * pattern (004's service calls 003's service).
 * 
 * Error Handling: If QCResultService throws an exception, this service creates
 * an AnalyzerError with type QC_MAPPING_INCOMPLETE and severity ERROR (per
 * FR-011).
 */
@Service
@Transactional
public class QCResultProcessingServiceImpl implements QCResultProcessingService {

    @Autowired
    private QCResultService qcResultService;

    @Autowired
    private AnalyzerErrorService analyzerErrorService;

    @Autowired
    private AnalyzerDAO analyzerDAO;

    /**
     * Setter for testing purposes (allows Mockito injection)
     */
    public void setQcResultService(QCResultService qcResultService) {
        this.qcResultService = qcResultService;
    }

    /**
     * Setter for testing purposes (allows Mockito injection)
     */
    public void setAnalyzerErrorService(AnalyzerErrorService analyzerErrorService) {
        this.analyzerErrorService = analyzerErrorService;
    }

    /**
     * Setter for testing purposes (allows Mockito injection)
     */
    public void setAnalyzerDAO(AnalyzerDAO analyzerDAO) {
        this.analyzerDAO = analyzerDAO;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public Object processQCResult(QCResultDTO qcResultDTO, String analyzerId) {
        if (qcResultDTO == null) {
            throw new IllegalArgumentException("QCResultDTO cannot be null");
        }
        if (analyzerId == null || analyzerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Analyzer ID cannot be null or empty");
        }

        try {
            // Convert ControlLevel enum → String (QCResultService expects String)
            String controlLevelStr = qcResultDTO.getControlLevel() != null ? qcResultDTO.getControlLevel().name()
                    : null;

            // Convert Date → LocalDateTime (QCResultService expects LocalDateTime)
            Timestamp timestamp = qcResultDTO.getTimestamp() != null
                    ? new Timestamp(qcResultDTO.getTimestamp().getTime())
                    : new Timestamp(System.currentTimeMillis());
            LocalDateTime localTimestamp = timestamp.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

            return qcResultService.createQCResult(
                    qcResultDTO.getAnalyzerId() != null ? qcResultDTO.getAnalyzerId() : analyzerId,
                    qcResultDTO.getTestId(), qcResultDTO.getControlLotId(), controlLevelStr,
                    qcResultDTO.getResultValue(), qcResultDTO.getUnit(), localTimestamp);

        } catch (LIMSRuntimeException e) {
            throw e;
        } catch (Exception e) {
            String errorMessage = String.format(
                    "Failed to create QC result for analyzer %s, test %s, control lot %s: %s", analyzerId,
                    qcResultDTO.getTestId(), qcResultDTO.getControlLotId(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            createQCError(analyzerId, errorMessage, null);
            LogEvent.logError(errorMessage, e);
            throw new LIMSRuntimeException("Failed to process QC result: " + errorMessage, e);
        }
    }

    /**
     * Create AnalyzerError for QC processing failures
     * 
     * @param analyzerId   Analyzer ID
     * @param errorMessage Error message
     * @param rawMessage   Raw ASTM message (if available)
     */
    private void createQCError(String analyzerId, String errorMessage, String rawMessage) {
        try {
            Analyzer analyzer = analyzerDAO.get(analyzerId).orElse(null);
            if (analyzer == null) {
                LogEvent.logError("QCResultProcessingServiceImpl", "createQCError",
                        "Cannot create error: Analyzer not found: " + analyzerId);
                return;
            }

            // Create error with type QC_MAPPING_INCOMPLETE (per FR-011)
            analyzerErrorService.createError(analyzer, AnalyzerError.ErrorType.QC_MAPPING_INCOMPLETE,
                    AnalyzerError.Severity.ERROR, errorMessage, rawMessage);

        } catch (Exception e) {
            // Log error creation failure but don't propagate (error logging shouldn't
            // fail processing)
            LogEvent.logError("Failed to create AnalyzerError: " + e.getMessage(), e);
        }
    }
}
