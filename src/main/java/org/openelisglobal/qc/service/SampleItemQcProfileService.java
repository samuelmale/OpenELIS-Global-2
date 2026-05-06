package org.openelisglobal.qc.service;

import java.util.List;
import java.util.Optional;
import org.openelisglobal.common.service.BaseObjectService;
import org.openelisglobal.qc.valueholder.SampleItemQcProfile;

/**
 * Service interface for SampleItemQcProfile management.
 */
public interface SampleItemQcProfileService extends BaseObjectService<SampleItemQcProfile, String> {

    /**
     * Find the QC profile for a specific sample item.
     *
     * @return the profile, or empty if the sample item is not a QC sample
     */
    Optional<SampleItemQcProfile> findBySampleItemId(Integer sampleItemId);

    /**
     * Find all QC profiles for a list of sample item IDs.
     */
    List<SampleItemQcProfile> findBySampleItemIds(List<Integer> sampleItemIds);
}
