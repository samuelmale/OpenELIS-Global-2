package org.openelisglobal.analyzer.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.openelisglobal.BaseWebContextSensitiveTest;
import org.openelisglobal.analyzerimport.analyzerreaders.AnalyzerLineInserter;
import org.openelisglobal.common.services.PluginAnalyzerService;
import org.openelisglobal.login.valueholder.UserSessionData;
import org.openelisglobal.plugin.AnalyzerImporterPlugin;
import org.openelisglobal.qc.dao.QCResultDAO;
import org.openelisglobal.qc.event.QCResultCreatedEvent;
import org.openelisglobal.qc.valueholder.QCResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Integration test: Full pipeline from HTTP POST /analyzer/astm to
 * QCResultCreatedEvent.
 *
 * <p>
 * Exercises layers NOT covered by AnalyzerToQCResultPipelineIntegrationTest:
 * <ol>
 * <li>AnalyzerImportController.doPost() — HTTP request handling</li>
 * <li>ASTMAnalyzerReader — plugin resolution, analyzer identification,
 * mapping-aware wrapping</li>
 * </ol>
 *
 * <p>
 * Uses same DBUnit dataset (testdata/analyzer-qc-pipeline.xml) and the same
 * event capture pattern (CopyOnWriteArrayList + ApplicationListener).
 *
 * <p>
 * Since PluginAnalyzerService is a Mockito mock in the test profile, we stub it
 * to return a test plugin that matches our ASTM messages.
 */
public class ASTMImportControllerPipelineIntegrationTest extends BaseWebContextSensitiveTest {

    @Autowired
    private PluginAnalyzerService pluginAnalyzerService; // Mockito mock from AppTestConfig

    @Autowired
    private QCResultDAO qcResultDAO;

    private final CopyOnWriteArrayList<QCResultCreatedEvent> capturedEvents = new CopyOnWriteArrayList<>();
    private final ApplicationListener<QCResultCreatedEvent> eventCapture = capturedEvents::add;

    /**
     * Tracks whether the plugin's inserter was invoked (guards against false
     * positives).
     */
    private final AtomicBoolean inserterCalled = new AtomicBoolean(false);

    /**
     * Captures the userId passed through the controller → reader → inserter chain.
     */
    private final AtomicReference<String> capturedUserId = new AtomicReference<>();

    @Before
    public void setUp() throws Exception {
        super.setUp();
        executeDataSetWithStateManagement("testdata/analyzer-qc-pipeline.xml");

        capturedEvents.clear();
        inserterCalled.set(false);
        capturedUserId.set(null);
        ((ConfigurableApplicationContext) webApplicationContext).addApplicationListener(eventCapture);

        // Stub mock PluginAnalyzerService to return our test plugin
        Mockito.when(pluginAnalyzerService.getAnalyzerPlugins())
                .thenReturn(Collections.singletonList(new TestBioRadPlugin(inserterCalled, capturedUserId)));
    }

    @After
    public void tearDown() {
        capturedEvents.clear();
        inserterCalled.set(false);
        capturedUserId.set(null);
        Mockito.reset(pluginAnalyzerService);
    }

    /**
     * POST /analyzer/astm with Q-segment → QCResult persisted + event published.
     *
     * Exercises: HTTP → ASTMAnalyzerReader → plugin resolution → analyzer
     * identification → MappingAwareAnalyzerLineInserter wrapping → QC pipeline →
     * QCResultCreatedEvent
     */
    @Test
    public void astmImport_fullPipeline_publishesQCResultCreatedEvent() throws Exception {
        // Arrange
        String astmMessage = "H|\\^&|||TEST^BioRad^1.0|||||||P\n"
                + "Q|1|GLU^LOT-2025-001^N|110.0|mg/dL|20250615103000\n" + "L|1|N";

        UserSessionData usd = new UserSessionData();
        usd.setSytemUserId(1);

        // Act
        mockMvc.perform(post("/analyzer/astm").content(astmMessage.getBytes(StandardCharsets.UTF_8))
                .requestAttr("userSessionData", usd)).andExpect(status().isOk());

        // Assert — pipeline executed (guards against false positive)
        assertTrue("Plugin inserter should have been invoked", inserterCalled.get());
        assertEquals("Controller should propagate userId from UserSessionData", "1", capturedUserId.get());

        // Assert — event was published
        assertEquals("Exactly 1 QCResultCreatedEvent should be published", 1, capturedEvents.size());

        QCResultCreatedEvent event = capturedEvents.get(0);
        assertNotNull("Event should carry a non-null QCResult", event.getResult());

        QCResult eventResult = event.getResult();
        assertNotNull("Event QCResult should have an ID (persisted)", eventResult.getId());
        assertEquals("Event controlLotId should be lot-001", "lot-001", event.getControlLotId());
        assertEquals("Event QCResult z-score should be 2.0000", 0,
                new BigDecimal("2.0000").compareTo(eventResult.getZScore()));
        assertEquals("Event QCResult value should be 110.0", 0,
                new BigDecimal("110.0").compareTo(eventResult.getResultValue()));

        // Cross-check: event result matches DB
        List<QCResult> dbResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("Exactly 1 QCResult in DB", 1, dbResults.size());
        assertEquals("Event result ID should match DB result ID", dbResults.get(0).getId(), eventResult.getId());
    }

    /**
     * POST /analyzer/astm with no Q-segments → no QCResult, no event.
     */
    @Test
    public void astmImport_noQSegments_noEventPublished() throws Exception {
        // Arrange — only R-segment (patient result), no Q-segment
        String astmMessage = "H|\\^&|||TEST^BioRad^1.0|||||||P\n"
                + "R|1|^^^GLUCOSE^GLU||100|mg/dL||N|||20250615103000\n" + "L|1|N";

        UserSessionData usd = new UserSessionData();
        usd.setSytemUserId(1);

        // Act
        mockMvc.perform(post("/analyzer/astm").content(astmMessage.getBytes(StandardCharsets.UTF_8))
                .requestAttr("userSessionData", usd)).andExpect(status().isOk());

        // Assert — pipeline executed (guards against false positive from broken setup)
        assertTrue("Plugin inserter should have been invoked", inserterCalled.get());

        // Assert — no event published (pipeline ran but no Q-segments to process)
        assertEquals("No QCResultCreatedEvent should be published", 0, capturedEvents.size());

        // No QCResult in DB
        List<QCResult> dbResults = qcResultDAO.findByControlLot("lot-001");
        assertEquals("No QCResult should be in DB", 0, dbResults.size());
    }

    // ── Test Plugin ────────────────────────────────────────────────────

    /**
     * Minimal AnalyzerImporterPlugin that matches ASTM messages with "TEST" in the
     * H-segment. The inserter records invocation state for false-positive
     * prevention.
     */
    private static class TestBioRadPlugin implements AnalyzerImporterPlugin {

        private final AtomicBoolean inserterCalled;
        private final AtomicReference<String> capturedUserId;

        TestBioRadPlugin(AtomicBoolean inserterCalled, AtomicReference<String> capturedUserId) {
            this.inserterCalled = inserterCalled;
            this.capturedUserId = capturedUserId;
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public boolean isTargetAnalyzer(List<String> lines) {
            if (lines == null) {
                return false;
            }
            return lines.stream().anyMatch(line -> line != null && line.startsWith("H|") && line.contains("TEST"));
        }

        @Override
        public AnalyzerLineInserter getAnalyzerLineInserter() {
            return new AnalyzerLineInserter() {
                @Override
                public boolean insert(List<String> lines, String currentUserId) {
                    inserterCalled.set(true);
                    capturedUserId.set(currentUserId);
                    return true;
                }

                @Override
                public String getError() {
                    return null;
                }
            };
        }
    }
}
