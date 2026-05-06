package org.openelisglobal.resultvalidation.service;

import java.util.List;
import org.openelisglobal.common.dao.BaseDAO;
import org.openelisglobal.common.service.BaseObjectServiceImpl;
import org.openelisglobal.resultvalidation.dao.ValidationQcAcknowledgmentDAO;
import org.openelisglobal.resultvalidation.valueholder.ValidationQcAcknowledgment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationQcAcknowledgmentServiceImpl extends BaseObjectServiceImpl<ValidationQcAcknowledgment, String>
        implements ValidationQcAcknowledgmentService {

    @Autowired
    private ValidationQcAcknowledgmentDAO validationQcAcknowledgmentDAO;

    public ValidationQcAcknowledgmentServiceImpl() {
        super(ValidationQcAcknowledgment.class);
    }

    @Override
    protected BaseDAO<ValidationQcAcknowledgment, String> getBaseObjectDAO() {
        return validationQcAcknowledgmentDAO;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ValidationQcAcknowledgment> findByAnalysisId(Integer analysisId) {
        return validationQcAcknowledgmentDAO.findByAnalysisId(analysisId);
    }
}
