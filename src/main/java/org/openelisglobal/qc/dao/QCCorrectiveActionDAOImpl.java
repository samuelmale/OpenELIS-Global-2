package org.openelisglobal.qc.dao;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import org.openelisglobal.common.daoimpl.BaseDAOImpl;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.qc.valueholder.QCCorrectiveAction;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * DAO implementation for QCCorrectiveAction entity.
 *
 * Uses JPA Criteria API instead of HQL to avoid Hibernate 6 column name
 * resolution issues where camelCase field names (e.g. createdDateTime) are
 * lowercased to "createddatetime" instead of using
 * {@code @Column(name = "created_date_time")}.
 */
@Component
@Transactional
public class QCCorrectiveActionDAOImpl extends BaseDAOImpl<QCCorrectiveAction, String>
        implements QCCorrectiveActionDAO {

    public QCCorrectiveActionDAOImpl() {
        super(QCCorrectiveAction.class);
    }

    @Override
    public List<QCCorrectiveAction> findByViolation(String violationId) throws LIMSRuntimeException {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<QCCorrectiveAction> cq = cb.createQuery(QCCorrectiveAction.class);
            Root<QCCorrectiveAction> root = cq.from(QCCorrectiveAction.class);
            cq.where(cb.equal(root.get("violationId"), violationId));
            cq.orderBy(cb.desc(root.get("createdDateTime")));
            return entityManager.createQuery(cq).getResultList();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error retrieving corrective actions by violation", e);
        }
    }

    @Override
    public List<QCCorrectiveAction> findByAssignedUser(Integer userId) throws LIMSRuntimeException {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<QCCorrectiveAction> cq = cb.createQuery(QCCorrectiveAction.class);
            Root<QCCorrectiveAction> root = cq.from(QCCorrectiveAction.class);
            cq.where(cb.equal(root.get("assignedUserId"), userId));
            cq.orderBy(cb.desc(root.get("createdDateTime")));
            return entityManager.createQuery(cq).getResultList();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error retrieving corrective actions by assigned user", e);
        }
    }

    @Override
    public List<QCCorrectiveAction> findByStatus(String status) throws LIMSRuntimeException {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<QCCorrectiveAction> cq = cb.createQuery(QCCorrectiveAction.class);
            Root<QCCorrectiveAction> root = cq.from(QCCorrectiveAction.class);
            cq.where(cb.equal(root.get("status"), status));
            cq.orderBy(cb.desc(root.get("createdDateTime")));
            return entityManager.createQuery(cq).getResultList();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error retrieving corrective actions by status", e);
        }
    }

    @Override
    public List<QCCorrectiveAction> findPendingByAssignedUser(Integer userId) throws LIMSRuntimeException {
        try {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<QCCorrectiveAction> cq = cb.createQuery(QCCorrectiveAction.class);
            Root<QCCorrectiveAction> root = cq.from(QCCorrectiveAction.class);
            cq.where(cb.equal(root.get("assignedUserId"), userId), cb.equal(root.get("status"), "PENDING"));
            cq.orderBy(cb.desc(root.get("createdDateTime")));
            return entityManager.createQuery(cq).getResultList();
        } catch (RuntimeException e) {
            throw new LIMSRuntimeException("Error retrieving pending corrective actions by assigned user", e);
        }
    }
}
