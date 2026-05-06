package org.openelisglobal.resultvalidation.service;

import java.util.List;
import org.openelisglobal.common.service.BaseObjectService;
import org.openelisglobal.resultvalidation.valueholder.ValidationQcAcknowledgment;

/**
 * Service interface for ValidationQcAcknowledgment management.
 */
public interface ValidationQcAcknowledgmentService extends BaseObjectService<ValidationQcAcknowledgment, String> {

    /**
     * Find all acknowledgments for a specific analysis.
     */
    List<ValidationQcAcknowledgment> findByAnalysisId(Integer analysisId);
}
