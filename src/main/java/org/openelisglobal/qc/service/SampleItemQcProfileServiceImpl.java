package org.openelisglobal.qc.service;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.common.dao.BaseDAO;
import org.openelisglobal.common.service.BaseObjectServiceImpl;
import org.openelisglobal.qc.dao.SampleItemQcProfileDAO;
import org.openelisglobal.qc.valueholder.SampleItemQcProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for SampleItemQcProfile management.
 *
 * Following Constitution IV.5: @Transactional in services ONLY
 */
@Service
public class SampleItemQcProfileServiceImpl extends BaseObjectServiceImpl<SampleItemQcProfile, String>
        implements SampleItemQcProfileService {

    @Autowired
    private SampleItemQcProfileDAO sampleItemQcProfileDAO;

    public SampleItemQcProfileServiceImpl() {
        super(SampleItemQcProfile.class);
    }

    @Override
    protected BaseDAO<SampleItemQcProfile, String> getBaseObjectDAO() {
        return sampleItemQcProfileDAO;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SampleItemQcProfile> findBySampleItemId(Integer sampleItemId) {
        return sampleItemQcProfileDAO.findBySampleItemId(sampleItemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SampleItemQcProfile> findBySampleItemIds(List<Integer> sampleItemIds) {
        return sampleItemQcProfileDAO.findBySampleItemIds(sampleItemIds);
    }
}
