package org.openelisglobal.qc.dao;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.qc.valueholder.QCControlLot;
import org.openelisglobal.qc.valueholder.QCCorrectiveAction;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for QCControlLotDAO and QCCorrectiveActionDAO.
 *
 * Reproduces Hibernate 6 HQL column resolution bug where camelCase Java field
 * names (e.g. testId, createdDateTime) are lowercased to "testid",
 * "createddatetime" instead of using @Column(name = "test_id"),
 * 
 * @Column(name = "created_date_time").
 *
 *              Test data loaded via DBUnit from testdata/qc-dao-hql.xml: - 2
 *              ACTIVE control lots (lot-dao-001, lot-dao-002) for test_id=1,
 *              instrument_id=1 - 1 EXPIRED control lot (lot-dao-expired) for
 *              test_id=1, instrument_id=1 - 1 ACTIVE control lot
 *              (lot-dao-other) for test_id=1, instrument_id=99 - 1 violation
 *              (viol-dao-001) with 2 corrective actions (PENDING + COMPLETED)
 */
public class QCControlLotDAOIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private QCControlLotDAO controlLotDAO;

    @Autowired
    private QCCorrectiveActionDAO correctiveActionDAO;

    @Before
    public void setUp() throws Exception {
        executeDataSetWithStateManagement("testdata/qc-dao-hql.xml");
    }

    // ===================== QCControlLotDAO tests =====================

    @Test
    public void getActiveByTestAndInstrument_returnsOnlyActiveLotsForCorrectTestAndInstrument() {
        List<QCControlLot> results = controlLotDAO.getActiveByTestAndInstrument(1, 1);

        assertEquals("Should return exactly 2 active lots for test=1, instrument=1", 2, results.size());

        boolean hasLot001 = results.stream().anyMatch(lot -> "lot-dao-001".equals(lot.getId()));
        boolean hasLot002 = results.stream().anyMatch(lot -> "lot-dao-002".equals(lot.getId()));
        assertTrue("Should include lot-dao-001", hasLot001);
        assertTrue("Should include lot-dao-002", hasLot002);

        boolean hasExpired = results.stream().anyMatch(lot -> "lot-dao-expired".equals(lot.getId()));
        assertFalse("Should NOT include expired lot", hasExpired);

        boolean hasOtherInstrument = results.stream().anyMatch(lot -> "lot-dao-other".equals(lot.getId()));
        assertFalse("Should NOT include lot from instrument 99", hasOtherInstrument);
    }

    @Test
    public void getActiveByTestAndInstrument_returnsEmptyForNonexistentInstrument() {
        List<QCControlLot> results = controlLotDAO.getActiveByTestAndInstrument(1, 999);

        assertEquals("Should return 0 lots for nonexistent instrument", 0, results.size());
    }

    @Test
    public void getByTestAndInstrument_returnsAllLotsIncludingExpired() {
        List<QCControlLot> results = controlLotDAO.getByTestAndInstrument(1, 1);

        assertEquals("Should return 3 lots (2 active + 1 expired) for test=1, instrument=1", 3, results.size());

        boolean hasExpired = results.stream().anyMatch(lot -> "lot-dao-expired".equals(lot.getId()));
        assertTrue("Should include expired lot in unfiltered query", hasExpired);
    }

    @Test
    public void getByLotNumber_returnsCorrectLot() {
        QCControlLot result = controlLotDAO.getByLotNumber("LOT-DAO-001");

        assertNotNull("Should find lot by lot number", result);
        assertEquals("lot-dao-001", result.getId());
        assertEquals("Glucose Control Level 1", result.getProductName());
        assertEquals(Integer.valueOf(1), result.getTestId());
        assertEquals(Integer.valueOf(1), result.getInstrumentId());
    }

    @Test
    public void getByLotNumber_returnsNullForNonexistentLot() {
        QCControlLot result = controlLotDAO.getByLotNumber("NONEXISTENT-LOT");

        assertNull("Should return null for nonexistent lot number", result);
    }

    @Test
    public void countActiveByInstrument_countsCorrectly() {
        long count = controlLotDAO.countActiveByInstrument(1);

        assertEquals("Should count 2 active lots for instrument 1", 2, count);
    }

    @Test
    public void countActiveByInstrument_returnsZeroForNonexistentInstrument() {
        long count = controlLotDAO.countActiveByInstrument(999);

        assertEquals("Should count 0 active lots for nonexistent instrument", 0, count);
    }

    // ===================== QCCorrectiveActionDAO tests =====================

    @Test
    public void findByViolation_returnsActionsOrderedByCreatedDateTimeDesc() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findByViolation("viol-dao-001");

        assertEquals("Should return 2 corrective actions for violation", 2, results.size());

        // Should be ordered by createdDateTime DESC (most recent first)
        assertEquals("First action should be ca-dao-002 (created later)", "ca-dao-002", results.get(0).getId());
        assertEquals("Second action should be ca-dao-001 (created earlier)", "ca-dao-001", results.get(1).getId());
    }

    @Test
    public void findByViolation_returnsEmptyForNonexistentViolation() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findByViolation("nonexistent-violation");

        assertEquals("Should return 0 actions for nonexistent violation", 0, results.size());
    }

    @Test
    public void findByStatus_returnsPendingActions() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findByStatus("PENDING");

        assertTrue("Should return at least 1 PENDING action", results.size() >= 1);
        boolean hasPending = results.stream().anyMatch(a -> "ca-dao-001".equals(a.getId()));
        assertTrue("Should include ca-dao-001 (PENDING)", hasPending);

        for (QCCorrectiveAction action : results) {
            assertEquals("All returned actions should be PENDING", "PENDING", action.getStatus());
        }
    }

    @Test
    public void findByStatus_returnsCompletedActions() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findByStatus("COMPLETED");

        assertTrue("Should return at least 1 COMPLETED action", results.size() >= 1);
        boolean hasCompleted = results.stream().anyMatch(a -> "ca-dao-002".equals(a.getId()));
        assertTrue("Should include ca-dao-002 (COMPLETED)", hasCompleted);

        for (QCCorrectiveAction action : results) {
            assertEquals("All returned actions should be COMPLETED", "COMPLETED", action.getStatus());
        }
    }

    @Test
    public void findByAssignedUser_returnsActionsForUser() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findByAssignedUser(1);

        assertEquals("Should return 2 actions assigned to user 1", 2, results.size());
    }

    @Test
    public void findPendingByAssignedUser_returnsOnlyPendingForUser() {
        List<QCCorrectiveAction> results = correctiveActionDAO.findPendingByAssignedUser(1);

        assertEquals("Should return 1 pending action for user 1", 1, results.size());
        assertEquals("ca-dao-001", results.get(0).getId());
        assertEquals("PENDING", results.get(0).getStatus());
    }
}
