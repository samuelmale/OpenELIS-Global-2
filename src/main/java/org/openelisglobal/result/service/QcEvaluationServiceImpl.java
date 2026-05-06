package org.openelisglobal.result.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.qc.service.SampleItemQcProfileService;
import org.openelisglobal.qc.valueholder.SampleItemQcProfile;
import org.openelisglobal.result.valueholder.QcEvaluation;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sampleitem.valueholder.SampleItem;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates QC acceptance criteria for Blank, Duplicate, and Control samples.
 *
 * <ul>
 * <li>BLANK: result ≤ qcBlankThreshold</li>
 * <li>DUPLICATE: RPD = |parent - dup| / mean(parent, dup) × 100 ≤
 * qcRpdThreshold</li>
 * <li>CONTROL: recovery = (result / expectedValue) × 100 within 100 ±
 * qcRecoveryWindowPct</li>
 * </ul>
 */
@Service
public class QcEvaluationServiceImpl implements QcEvaluationService {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    @Autowired
    private SampleItemQcProfileService qcProfileService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ResultService resultService;

    @Override
    @Transactional
    public void evaluateQc(Result result) {
        if (result == null || result.getAnalysis() == null) {
            return;
        }

        Analysis analysis = result.getAnalysis();
        SampleItem sampleItem = analysis.getSampleItem();
        if (sampleItem == null || sampleItem.getId() == null) {
            return;
        }

        SampleItemQcProfile qcProfile = qcProfileService.findBySampleItemId(Integer.valueOf(sampleItem.getId()))
                .orElse(null);
        if (qcProfile == null) {
            return;
        }

        Test test = analysis.getTest();
        if (test == null) {
            return;
        }

        BigDecimal resultValue = parseNumericValue(result.getValue());
        if (resultValue == null) {
            setEvaluation(result, QcEvaluation.N_A, "Non-numeric result");
            return;
        }

        switch (qcProfile.getQcType()) {
        case "BLANK":
            evaluateBlank(result, resultValue, test);
            break;
        case "DUPLICATE":
            evaluateDuplicate(result, resultValue, test, qcProfile, analysis);
            break;
        case "CONTROL":
            evaluateControl(result, resultValue, test, qcProfile);
            break;
        default:
            setEvaluation(result, QcEvaluation.N_A, "Unknown QC type: " + qcProfile.getQcType());
            break;
        }
    }

    private void evaluateBlank(Result result, BigDecimal resultValue, Test test) {
        Double threshold = test.getQcBlankThreshold();
        if (threshold == null) {
            setEvaluation(result, QcEvaluation.N_A, "Blank threshold not configured");
            return;
        }

        BigDecimal thresholdBd = BigDecimal.valueOf(threshold);
        boolean pass = resultValue.compareTo(thresholdBd) <= 0;

        String detail = String.format("Result = %s (threshold %s)", resultValue.toPlainString(),
                thresholdBd.toPlainString());
        setEvaluation(result, pass ? QcEvaluation.PASS : QcEvaluation.FAIL, detail);
    }

    private void evaluateDuplicate(Result result, BigDecimal dupValue, Test test, SampleItemQcProfile qcProfile,
            Analysis dupAnalysis) {
        if (qcProfile.getParentSampleItemId() == null) {
            setEvaluation(result, QcEvaluation.N_A, "Parent sample item not linked");
            return;
        }

        Double thresholdPct = test.getQcRpdThreshold();
        if (thresholdPct == null) {
            thresholdPct = 20.0;
        }

        BigDecimal parentValue = findParentResultValue(qcProfile.getParentSampleItemId(), dupAnalysis.getTest());
        if (parentValue == null) {
            setEvaluation(result, QcEvaluation.N_A, "Parent result not found");
            return;
        }

        BigDecimal mean = parentValue.add(dupValue).divide(TWO, MC);

        BigDecimal rpd;
        if (mean.compareTo(BigDecimal.ZERO) == 0) {
            rpd = BigDecimal.ZERO;
        } else {
            rpd = parentValue.subtract(dupValue).abs().divide(mean, MC).multiply(HUNDRED, MC);
        }

        BigDecimal thresholdBd = BigDecimal.valueOf(thresholdPct);
        boolean pass = rpd.compareTo(thresholdBd) <= 0;

        String detail = String.format("RPD = %s%% (threshold %s%%)",
                rpd.setScale(1, RoundingMode.HALF_UP).toPlainString(), thresholdBd.toPlainString());
        setEvaluation(result, pass ? QcEvaluation.PASS : QcEvaluation.FAIL, detail);
    }

    private void evaluateControl(Result result, BigDecimal resultValue, Test test, SampleItemQcProfile qcProfile) {
        BigDecimal expectedValue = qcProfile.getExpectedValue();
        if (expectedValue == null || expectedValue.compareTo(BigDecimal.ZERO) == 0) {
            setEvaluation(result, QcEvaluation.N_A, "Expected value not set");
            return;
        }

        Double windowPct = test.getQcRecoveryWindowPct();
        if (windowPct == null) {
            windowPct = 20.0;
        }

        BigDecimal recovery = resultValue.divide(expectedValue, MC).multiply(HUNDRED, MC);
        BigDecimal lowerBound = HUNDRED.subtract(BigDecimal.valueOf(windowPct));
        BigDecimal upperBound = HUNDRED.add(BigDecimal.valueOf(windowPct));

        boolean pass = recovery.compareTo(lowerBound) >= 0 && recovery.compareTo(upperBound) <= 0;

        String detail = String.format("Recovery = %s%% (window %s%% - %s%%)",
                recovery.setScale(1, RoundingMode.HALF_UP).toPlainString(), lowerBound.toPlainString(),
                upperBound.toPlainString());
        setEvaluation(result, pass ? QcEvaluation.PASS : QcEvaluation.FAIL, detail);
    }

    private BigDecimal findParentResultValue(Integer parentSampleItemId, Test test) {
        try {
            Analysis parentAnalysis = analysisService.getAnalysisBySampleItemAndTest(parentSampleItemId.toString(),
                    test.getId());
            if (parentAnalysis == null) {
                return null;
            }

            List<Result> parentResults = resultService.getResultsByAnalysis(parentAnalysis);
            if (parentResults == null || parentResults.isEmpty()) {
                return null;
            }

            return parseNumericValue(parentResults.get(0).getValue());
        } catch (Exception e) {
            LogEvent.logError(this.getClass().getSimpleName(), "findParentResultValue",
                    "Error finding parent result for sample item " + parentSampleItemId);
            return null;
        }
    }

    private BigDecimal parseNumericValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setEvaluation(Result result, QcEvaluation evaluation, String detail) {
        result.setQcEvaluation(evaluation.name());
        result.setQcEvaluationDetail(detail);
    }
}
