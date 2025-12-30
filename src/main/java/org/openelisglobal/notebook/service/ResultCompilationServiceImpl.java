package org.openelisglobal.notebook.service;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.notebook.dao.NotebookPageSampleDAO;
import org.openelisglobal.notebook.valueholder.NoteBook;
import org.openelisglobal.notebook.valueholder.NotebookPageSample;
import org.openelisglobal.notebook.valueholder.ValidationStatus;
import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.sampleitem.service.SampleItemService;
import org.openelisglobal.sampleitem.valueholder.SampleItem;
import org.openelisglobal.systemuser.service.SystemUserService;
import org.openelisglobal.systemuser.valueholder.SystemUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for result compilation and dissemination per US7.
 */
@Service
public class ResultCompilationServiceImpl implements ResultCompilationService {

    private static final String VALIDATION_STATUS_KEY = "validationStatus";
    private static final String VALIDATION_REASON_KEY = "validationReason";
    private static final String VALIDATED_BY_KEY = "validatedBy";
    private static final String VALIDATED_AT_KEY = "validatedAt";

    @Autowired
    private NotebookPageSampleDAO notebookPageSampleDAO;

    @Autowired
    private NoteBookService noteBookService;

    @Autowired
    private SampleItemService sampleItemService;

    @Autowired
    private SystemUserService systemUserService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ResultService resultService;

    // In-memory delivery records (in production, use database table)
    private final List<DeliveryRecord> deliveryRecords = new ArrayList<>();
    private int nextDeliveryId = 1;

    @Override
    @Transactional
    public boolean flagSample(Integer pageId, String sampleItemId, ValidationStatus status, String reason,
            String userId) {
        // Validate inputs
        if (status == ValidationStatus.INVALID || status == ValidationStatus.INCONCLUSIVE) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason is required for INVALID or INCONCLUSIVE status");
            }
        }

        NotebookPageSample pageSample = notebookPageSampleDAO.getBySampleItemIdAndPageId(sampleItemId, pageId);
        if (pageSample == null) {
            LogEvent.logWarn(this.getClass().getName(), "flagSample",
                    "Sample not found: pageId=" + pageId + ", sampleItemId=" + sampleItemId);
            return false;
        }

        // Update data JSONB with validation info
        Map<String, Object> data = pageSample.getData();
        if (data == null) {
            data = new HashMap<>();
        }

        data.put(VALIDATION_STATUS_KEY, status.name());
        data.put(VALIDATION_REASON_KEY, reason);
        data.put(VALIDATED_BY_KEY, userId);
        data.put(VALIDATED_AT_KEY, System.currentTimeMillis());

        pageSample.setData(data);

        // Note: Flagging a sample only updates the validationStatus in the data JSONB.
        // The pageStatus (PENDING/IN_PROGRESS/COMPLETED) is NOT changed by flagging.
        // The user must explicitly use "Send to Reporting" to mark samples as COMPLETED
        // and trigger the T150 flow to the next page.

        notebookPageSampleDAO.update(pageSample);

        return true;
    }

    @Override
    @Transactional
    public int bulkFlagSamples(Integer pageId, List<String> sampleItemIds, ValidationStatus status, String reason,
            String userId) {
        int flagged = 0;
        for (String sampleItemId : sampleItemIds) {
            try {
                if (flagSample(pageId, sampleItemId, status, reason, userId)) {
                    flagged++;
                }
            } catch (Exception e) {
                LogEvent.logError(this.getClass().getName(), "bulkFlagSamples",
                        "Failed to flag sample " + sampleItemId + ": " + e.getMessage());
            }
        }
        return flagged;
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationSummary getValidationSummary(Integer pageId) {
        List<NotebookPageSample> samples = notebookPageSampleDAO.getByPageId(pageId);

        long valid = 0, invalid = 0, inconclusive = 0, pending = 0;

        for (NotebookPageSample sample : samples) {
            ValidationStatus status = getValidationStatus(sample);
            switch (status) {
            case VALID:
                valid++;
                break;
            case INVALID:
                invalid++;
                break;
            case INCONCLUSIVE:
                inconclusive++;
                break;
            default:
                pending++;
            }
        }

        return new ValidationSummary(samples.size(), valid, invalid, inconclusive, pending);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationSummary getNotebookValidationSummary(Integer notebookId) {
        List<NotebookPageSample> samples = notebookPageSampleDAO.getByNotebookId(notebookId);

        long valid = 0, invalid = 0, inconclusive = 0, pending = 0;

        for (NotebookPageSample sample : samples) {
            ValidationStatus status = getValidationStatus(sample);
            switch (status) {
            case VALID:
                valid++;
                break;
            case INVALID:
                invalid++;
                break;
            case INCONCLUSIVE:
                inconclusive++;
                break;
            default:
                pending++;
            }
        }

        return new ValidationSummary(samples.size(), valid, invalid, inconclusive, pending);
    }

    private ValidationStatus getValidationStatus(NotebookPageSample sample) {
        if (sample.getData() == null) {
            return ValidationStatus.PENDING;
        }
        Object statusObj = sample.getData().get(VALIDATION_STATUS_KEY);
        if (statusObj == null) {
            return ValidationStatus.PENDING;
        }
        return ValidationStatus.fromString(statusObj.toString());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] compileToExcel(Integer notebookId, ExportOptions options) {
        LogEvent.logInfo(this.getClass().getName(), "compileToExcel",
                "Starting comprehensive Excel export for notebook ID: " + notebookId);

        NoteBook notebook = noteBookService.get(notebookId);
        if (notebook == null) {
            LogEvent.logError(this.getClass().getName(), "compileToExcel", "Notebook not found: " + notebookId);
            throw new IllegalArgumentException("Notebook not found: " + notebookId);
        }
        LogEvent.logInfo(this.getClass().getName(), "compileToExcel",
                "Found notebook: " + notebook.getTitle() + " (ID: " + notebookId + ")");

        List<NotebookPageSample> samples = notebookPageSampleDAO.getByNotebookId(notebookId);
        LogEvent.logInfo(this.getClass().getName(), "compileToExcel",
                "Found " + samples.size() + " samples for notebook " + notebookId);

        try (Workbook workbook = new XSSFWorkbook()) {
            // Create Summary sheet first
            createSummarySheet(workbook, notebook, samples);

            // Create main Results sheet
            Sheet sheet = workbook.createSheet(options.title() != null ? options.title() : "Sample Results");

            // Create header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Create status styles
            CellStyle validStyle = workbook.createCellStyle();
            validStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            validStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle invalidStyle = workbook.createCellStyle();
            invalidStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            invalidStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle inconclusiveStyle = workbook.createCellStyle();
            inconclusiveStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            inconclusiveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Define comprehensive headers in logical order
            List<String> allHeaders = getComprehensiveHeaders();

            // Build header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < allHeaders.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(allHeaders.get(i));
                cell.setCellStyle(headerStyle);
            }

            // Data rows
            int rowNum = 1;
            for (NotebookPageSample pageSample : samples) {
                try {
                    ValidationStatus validationStatus = getValidationStatus(pageSample);

                    // Filter based on options
                    if (!options.includeInvalid() && validationStatus == ValidationStatus.INVALID) {
                        continue;
                    }
                    if (!options.includeInconclusive() && validationStatus == ValidationStatus.INCONCLUSIVE) {
                        continue;
                    }

                    Row row = sheet.createRow(rowNum++);
                    Map<String, Object> data = pageSample.getData() != null ? pageSample.getData() : new HashMap<>();

                    // Get sample details
                    String externalId = "";
                    String sampleTypeDesc = "";
                    String accessionNumber = "";
                    String collectionDate = "";
                    String patientName = "";
                    String patientId = "";
                    try {
                        SampleItem sampleItem = sampleItemService.get(pageSample.getSampleItemId());
                        if (sampleItem != null) {
                            externalId = sampleItem.getExternalId() != null ? sampleItem.getExternalId() : "";
                            if (sampleItem.getTypeOfSample() != null) {
                                sampleTypeDesc = sampleItem.getTypeOfSample().getDescription();
                            }
                            if (sampleItem.getSample() != null) {
                                accessionNumber = sampleItem.getSample().getAccessionNumber() != null
                                        ? sampleItem.getSample().getAccessionNumber()
                                        : "";
                                if (sampleItem.getSample().getCollectionDate() != null) {
                                    collectionDate = sampleItem.getSample().getCollectionDate().toString();
                                }
                            }
                        }
                    } catch (Exception e) {
                        LogEvent.logDebug(this.getClass().getName(), "compileToExcel",
                                "Sample not found: " + pageSample.getSampleItemId());
                    }

                    // Populate row with comprehensive data
                    int colIdx = 0;
                    row.createCell(colIdx++)
                            .setCellValue(pageSample.getSampleItemId() != null ? pageSample.getSampleItemId() : "");
                    row.createCell(colIdx++).setCellValue(externalId);
                    row.createCell(colIdx++).setCellValue(accessionNumber);
                    row.createCell(colIdx++).setCellValue(sampleTypeDesc);
                    row.createCell(colIdx++).setCellValue(collectionDate);

                    // Status and Validation
                    row.createCell(colIdx++)
                            .setCellValue(pageSample.getStatus() != null ? pageSample.getStatus().name() : "");
                    Cell statusCell = row.createCell(colIdx++);
                    statusCell.setCellValue(validationStatus.getDisplayName());
                    switch (validationStatus) {
                    case VALID:
                        statusCell.setCellStyle(validStyle);
                        break;
                    case INVALID:
                        statusCell.setCellStyle(invalidStyle);
                        break;
                    case INCONCLUSIVE:
                        statusCell.setCellStyle(inconclusiveStyle);
                        break;
                    default:
                        break;
                    }
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, VALIDATION_REASON_KEY));

                    // Test Results
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "testResult"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "ctValue"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "concentration"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "absorbance"));

                    // Test Assignment Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "experimentCategory"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "subcategory"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "specificAssay"));

                    // Test Execution Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "runId"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "runCompleted"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "runIssues"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "executionDate"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "executionTime"));

                    // Instrument Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "instrument"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "instrumentId"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "machineType"));

                    // Reagent Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "kitLot"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "selectedReagents"));

                    // Machine Scheduling
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "scheduledDate"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "timeSlot"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "startTime"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "endTime"));

                    // Operator Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "operator"));

                    // Sample Processing Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "extractionMethod"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "extractionKit"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "elutionVolume"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "inputVolume"));

                    // QC Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcStatus"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcConcentration"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcPurity260280"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcPurity260230"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcNotes"));

                    // Storage Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageLocation"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageBox"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageWell"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageCondition"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageTemperature"));

                    // Aliquoting Info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "aliquotCount"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "aliquotVolume"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "parentSampleId"));

                    // Notes
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "notes"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "executionNotes"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "assignmentNotes"));

                    // Completed info
                    Timestamp completedAt = pageSample.getCompletedAt();
                    row.createCell(colIdx++).setCellValue(completedAt != null ? completedAt.toString() : "");
                    String completedByName = "";
                    try {
                        SystemUser completedBy = pageSample.getCompletedBy();
                        if (completedBy != null) {
                            String firstName = completedBy.getFirstName() != null ? completedBy.getFirstName() : "";
                            String lastName = completedBy.getLastName() != null ? completedBy.getLastName() : "";
                            completedByName = (firstName + " " + lastName).trim();
                        }
                    } catch (Exception e) {
                        LogEvent.logDebug(this.getClass().getName(), "compileToExcel",
                                "Could not load completedBy for sample: " + pageSample.getSampleItemId());
                    }
                    row.createCell(colIdx++).setCellValue(completedByName);

                } catch (Exception e) {
                    LogEvent.logError(this.getClass().getName(), "compileToExcel",
                            "Error processing sample " + pageSample.getSampleItemId() + ": " + e.getMessage());
                }
            }

            // Auto-size columns (limit to prevent very wide columns)
            for (int i = 0; i < allHeaders.size(); i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) > 10000) {
                    sheet.setColumnWidth(i, 10000);
                }
            }

            // Write to byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            LogEvent.logError(this.getClass().getName(), "compileToExcel",
                    "Failed to generate Excel: " + e.getMessage());
            throw new RuntimeException("Failed to generate Excel report", e);
        }
    }

    /**
     * Get comprehensive headers for the export in logical order.
     */
    private List<String> getComprehensiveHeaders() {
        List<String> headers = new ArrayList<>();

        // Sample Identification
        headers.add("Sample ID");
        headers.add("External ID");
        headers.add("Accession Number");
        headers.add("Sample Type");
        headers.add("Collection Date");

        // Status and Validation
        headers.add("Page Status");
        headers.add("Validation Status");
        headers.add("Validation Reason");

        // Test Results
        headers.add("Test Result");
        headers.add("CT Value");
        headers.add("Concentration");
        headers.add("Absorbance");

        // Test Assignment
        headers.add("Experiment Category");
        headers.add("Subcategory");
        headers.add("Specific Assay");

        // Test Execution
        headers.add("Run ID");
        headers.add("Run Completed");
        headers.add("Run Issues");
        headers.add("Execution Date");
        headers.add("Execution Time");

        // Instrument
        headers.add("Instrument");
        headers.add("Instrument ID");
        headers.add("Machine Type");

        // Reagents
        headers.add("Kit Lot Number");
        headers.add("Selected Reagents");

        // Machine Scheduling
        headers.add("Scheduled Date");
        headers.add("Time Slot");
        headers.add("Start Time");
        headers.add("End Time");

        // Operator
        headers.add("Operator");

        // Sample Processing
        headers.add("Extraction Method");
        headers.add("Extraction Kit");
        headers.add("Elution Volume");
        headers.add("Input Volume");

        // QC
        headers.add("QC Status");
        headers.add("QC Concentration");
        headers.add("QC Purity 260/280");
        headers.add("QC Purity 260/230");
        headers.add("QC Notes");

        // Storage
        headers.add("Storage Location");
        headers.add("Storage Box");
        headers.add("Storage Well");
        headers.add("Storage Condition");
        headers.add("Storage Temperature");

        // Aliquoting
        headers.add("Aliquot Count");
        headers.add("Aliquot Volume");
        headers.add("Parent Sample ID");

        // Notes
        headers.add("Notes");
        headers.add("Execution Notes");
        headers.add("Assignment Notes");

        // Completion
        headers.add("Completed At");
        headers.add("Completed By");

        return headers;
    }

    /**
     * Create a summary sheet with notebook metadata and statistics.
     */
    private void createSummarySheet(Workbook workbook, NoteBook notebook, List<NotebookPageSample> samples) {
        Sheet summarySheet = workbook.createSheet("Summary");

        // Create styles
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int rowNum = 0;

        // Title
        Row titleRow = summarySheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("MNTD Notebook Export Report");
        titleCell.setCellStyle(titleStyle);

        rowNum++; // Empty row

        // Notebook Info
        Row notebookHeaderRow = summarySheet.createRow(rowNum++);
        Cell nbHeaderCell = notebookHeaderRow.createCell(0);
        nbHeaderCell.setCellValue("Notebook Information");
        nbHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Notebook ID:", String.valueOf(notebook.getId()));
        addSummaryRow(summarySheet, rowNum++, "Notebook Title:",
                notebook.getTitle() != null ? notebook.getTitle() : "");
        addSummaryRow(summarySheet, rowNum++, "Objective:",
                notebook.getObjective() != null ? notebook.getObjective() : "");
        addSummaryRow(summarySheet, rowNum++, "Export Date:",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        rowNum++; // Empty row

        // Sample Statistics
        Row statsHeaderRow = summarySheet.createRow(rowNum++);
        Cell statsHeaderCell = statsHeaderRow.createCell(0);
        statsHeaderCell.setCellValue("Sample Statistics");
        statsHeaderCell.setCellStyle(headerStyle);

        // Count validation statuses
        long valid = 0, invalid = 0, inconclusive = 0, pending = 0;
        for (NotebookPageSample sample : samples) {
            ValidationStatus status = getValidationStatus(sample);
            switch (status) {
            case VALID:
                valid++;
                break;
            case INVALID:
                invalid++;
                break;
            case INCONCLUSIVE:
                inconclusive++;
                break;
            default:
                pending++;
            }
        }

        addSummaryRow(summarySheet, rowNum++, "Total Samples:", String.valueOf(samples.size()));
        addSummaryRow(summarySheet, rowNum++, "Valid Samples:", String.valueOf(valid));
        addSummaryRow(summarySheet, rowNum++, "Invalid Samples:", String.valueOf(invalid));
        addSummaryRow(summarySheet, rowNum++, "Inconclusive Samples:", String.valueOf(inconclusive));
        addSummaryRow(summarySheet, rowNum++, "Pending Validation:", String.valueOf(pending));

        // Collect unique instruments and reagents
        java.util.Set<String> instruments = new java.util.HashSet<>();
        java.util.Set<String> reagents = new java.util.HashSet<>();
        java.util.Set<String> assays = new java.util.HashSet<>();

        for (NotebookPageSample sample : samples) {
            Map<String, Object> data = sample.getData();
            if (data != null) {
                String instrument = getStringFromData(data, "instrument");
                if (!instrument.isEmpty()) {
                    instruments.add(instrument);
                }
                String kitLot = getStringFromData(data, "kitLot");
                if (!kitLot.isEmpty()) {
                    reagents.add(kitLot);
                }
                String assay = getStringFromData(data, "specificAssay");
                if (!assay.isEmpty()) {
                    assays.add(assay);
                }
            }
        }

        rowNum++; // Empty row

        // Instruments Used
        Row instrumentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell instrumentsHeaderCell = instrumentsHeaderRow.createCell(0);
        instrumentsHeaderCell.setCellValue("Instruments Used");
        instrumentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(instruments.size()));
        if (!instruments.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", instruments));
        }

        rowNum++; // Empty row

        // Reagents Used
        Row reagentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell reagentsHeaderCell = reagentsHeaderRow.createCell(0);
        reagentsHeaderCell.setCellValue("Reagent Lots Used");
        reagentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(reagents.size()));
        if (!reagents.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", reagents));
        }

        rowNum++; // Empty row

        // Assays Performed
        Row assaysHeaderRow = summarySheet.createRow(rowNum++);
        Cell assaysHeaderCell = assaysHeaderRow.createCell(0);
        assaysHeaderCell.setCellValue("Assays Performed");
        assaysHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(assays.size()));
        if (!assays.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", assays));
        }

        // Auto-size columns
        summarySheet.autoSizeColumn(0);
        summarySheet.autoSizeColumn(1);
    }

    private void addSummaryRow(Sheet sheet, int rowNum, String label, String value) {
        Row row = sheet.createRow(rowNum);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(value);
    }

    /**
     * Safely get a string value from the data map.
     */
    private String getStringFromData(Map<String, Object> data, String key) {
        if (data == null || data.get(key) == null) {
            return "";
        }
        Object val = data.get(key);
        if (val instanceof List) {
            return String.join(", ", ((List<?>) val).stream().map(Object::toString).toList());
        }
        return val.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] compileToCsv(Integer notebookId, ExportOptions options) {
        LogEvent.logInfo(this.getClass().getName(), "compileToCsv",
                "Starting comprehensive CSV export for notebook ID: " + notebookId);

        NoteBook notebook = noteBookService.get(notebookId);
        if (notebook == null) {
            throw new IllegalArgumentException("Notebook not found: " + notebookId);
        }

        List<NotebookPageSample> samples = notebookPageSampleDAO.getByNotebookId(notebookId);
        LogEvent.logInfo(this.getClass().getName(), "compileToCsv",
                "Found " + samples.size() + " samples for notebook " + notebookId);

        StringBuilder csv = new StringBuilder();

        // Use the same comprehensive headers as Excel
        List<String> allHeaders = getComprehensiveHeaders();
        csv.append(String.join(",", allHeaders)).append("\n");

        // Data rows
        for (NotebookPageSample pageSample : samples) {
            try {
                ValidationStatus validationStatus = getValidationStatus(pageSample);

                // Filter based on options
                if (!options.includeInvalid() && validationStatus == ValidationStatus.INVALID) {
                    continue;
                }
                if (!options.includeInconclusive() && validationStatus == ValidationStatus.INCONCLUSIVE) {
                    continue;
                }

                Map<String, Object> data = pageSample.getData() != null ? pageSample.getData() : new HashMap<>();

                // Get sample details
                String externalId = "";
                String sampleTypeDesc = "";
                String accessionNumber = "";
                String collectionDate = "";
                try {
                    SampleItem sampleItem = sampleItemService.get(pageSample.getSampleItemId());
                    if (sampleItem != null) {
                        externalId = sampleItem.getExternalId() != null ? sampleItem.getExternalId() : "";
                        if (sampleItem.getTypeOfSample() != null) {
                            sampleTypeDesc = sampleItem.getTypeOfSample().getDescription();
                        }
                        if (sampleItem.getSample() != null) {
                            accessionNumber = sampleItem.getSample().getAccessionNumber() != null
                                    ? sampleItem.getSample().getAccessionNumber()
                                    : "";
                            if (sampleItem.getSample().getCollectionDate() != null) {
                                collectionDate = sampleItem.getSample().getCollectionDate().toString();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Sample not found - use empty strings
                }

                List<String> rowValues = new ArrayList<>();

                // Sample Identification
                rowValues.add(escapeCsv(pageSample.getSampleItemId() != null ? pageSample.getSampleItemId() : ""));
                rowValues.add(escapeCsv(externalId));
                rowValues.add(escapeCsv(accessionNumber));
                rowValues.add(escapeCsv(sampleTypeDesc));
                rowValues.add(escapeCsv(collectionDate));

                // Status and Validation
                rowValues.add(escapeCsv(pageSample.getStatus() != null ? pageSample.getStatus().name() : ""));
                rowValues.add(escapeCsv(validationStatus.getDisplayName()));
                rowValues.add(escapeCsv(getStringFromData(data, VALIDATION_REASON_KEY)));

                // Test Results
                rowValues.add(escapeCsv(getStringFromData(data, "testResult")));
                rowValues.add(escapeCsv(getStringFromData(data, "ctValue")));
                rowValues.add(escapeCsv(getStringFromData(data, "concentration")));
                rowValues.add(escapeCsv(getStringFromData(data, "absorbance")));

                // Test Assignment
                rowValues.add(escapeCsv(getStringFromData(data, "experimentCategory")));
                rowValues.add(escapeCsv(getStringFromData(data, "subcategory")));
                rowValues.add(escapeCsv(getStringFromData(data, "specificAssay")));

                // Test Execution
                rowValues.add(escapeCsv(getStringFromData(data, "runId")));
                rowValues.add(escapeCsv(getStringFromData(data, "runCompleted")));
                rowValues.add(escapeCsv(getStringFromData(data, "runIssues")));
                rowValues.add(escapeCsv(getStringFromData(data, "executionDate")));
                rowValues.add(escapeCsv(getStringFromData(data, "executionTime")));

                // Instrument
                rowValues.add(escapeCsv(getStringFromData(data, "instrument")));
                rowValues.add(escapeCsv(getStringFromData(data, "instrumentId")));
                rowValues.add(escapeCsv(getStringFromData(data, "machineType")));

                // Reagents
                rowValues.add(escapeCsv(getStringFromData(data, "kitLot")));
                rowValues.add(escapeCsv(getStringFromData(data, "selectedReagents")));

                // Machine Scheduling
                rowValues.add(escapeCsv(getStringFromData(data, "scheduledDate")));
                rowValues.add(escapeCsv(getStringFromData(data, "timeSlot")));
                rowValues.add(escapeCsv(getStringFromData(data, "startTime")));
                rowValues.add(escapeCsv(getStringFromData(data, "endTime")));

                // Operator
                rowValues.add(escapeCsv(getStringFromData(data, "operator")));

                // Sample Processing
                rowValues.add(escapeCsv(getStringFromData(data, "extractionMethod")));
                rowValues.add(escapeCsv(getStringFromData(data, "extractionKit")));
                rowValues.add(escapeCsv(getStringFromData(data, "elutionVolume")));
                rowValues.add(escapeCsv(getStringFromData(data, "inputVolume")));

                // QC
                rowValues.add(escapeCsv(getStringFromData(data, "qcStatus")));
                rowValues.add(escapeCsv(getStringFromData(data, "qcConcentration")));
                rowValues.add(escapeCsv(getStringFromData(data, "qcPurity260280")));
                rowValues.add(escapeCsv(getStringFromData(data, "qcPurity260230")));
                rowValues.add(escapeCsv(getStringFromData(data, "qcNotes")));

                // Storage
                rowValues.add(escapeCsv(getStringFromData(data, "storageLocation")));
                rowValues.add(escapeCsv(getStringFromData(data, "storageBox")));
                rowValues.add(escapeCsv(getStringFromData(data, "storageWell")));
                rowValues.add(escapeCsv(getStringFromData(data, "storageCondition")));
                rowValues.add(escapeCsv(getStringFromData(data, "storageTemperature")));

                // Aliquoting
                rowValues.add(escapeCsv(getStringFromData(data, "aliquotCount")));
                rowValues.add(escapeCsv(getStringFromData(data, "aliquotVolume")));
                rowValues.add(escapeCsv(getStringFromData(data, "parentSampleId")));

                // Notes
                rowValues.add(escapeCsv(getStringFromData(data, "notes")));
                rowValues.add(escapeCsv(getStringFromData(data, "executionNotes")));
                rowValues.add(escapeCsv(getStringFromData(data, "assignmentNotes")));

                // Completion
                rowValues.add(
                        escapeCsv(pageSample.getCompletedAt() != null ? pageSample.getCompletedAt().toString() : ""));
                String completedByName = "";
                try {
                    SystemUser completedBy = pageSample.getCompletedBy();
                    if (completedBy != null) {
                        String firstName = completedBy.getFirstName() != null ? completedBy.getFirstName() : "";
                        String lastName = completedBy.getLastName() != null ? completedBy.getLastName() : "";
                        completedByName = (firstName + " " + lastName).trim();
                    }
                } catch (Exception e) {
                    // Lazy loading failed
                }
                rowValues.add(escapeCsv(completedByName));

                csv.append(String.join(",", rowValues)).append("\n");

            } catch (Exception e) {
                LogEvent.logError(this.getClass().getName(), "compileToCsv",
                        "Error processing sample " + pageSample.getSampleItemId() + ": " + e.getMessage());
            }
        }

        return csv.toString().getBytes();
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @Override
    public byte[] generatePdfReport(Integer notebookId, ExportOptions options) {
        // PDF generation would require a library like iText or Apache PDFBox
        // For now, return empty - implement when PDF library is added
        throw new UnsupportedOperationException("PDF generation not yet implemented");
    }

    @Override
    @Transactional
    public Integer recordDelivery(Integer notebookId, String recipientName, String recipientEmail, Integer fileId,
            String deliveryType, String regulatoryBody, String notes, String userId) {
        // Get file name (fileId is optional, may be null for direct delivery)
        String fileName = fileId != null ? "File_" + fileId : "Direct Delivery";

        // Get user name
        String deliveredBy = userId;
        SystemUser user = systemUserService.get(userId);
        if (user != null) {
            deliveredBy = user.getFirstName() + " " + user.getLastName();
        }

        DeliveryRecord record = new DeliveryRecord(nextDeliveryId++, recipientName, recipientEmail, fileName,
                deliveryType, regulatoryBody, notes, LocalDateTime.now(), deliveredBy);

        deliveryRecords.add(record);

        LogEvent.logInfo(this.getClass().getName(), "recordDelivery", "Recorded delivery for notebook " + notebookId
                + " to " + recipientName + " (type: " + deliveryType + ", regulatory: " + regulatoryBody + ")");

        return record.id();
    }

    @Override
    public List<DeliveryRecord> getDeliveryHistory(Integer notebookId) {
        // In production, filter by notebookId from database
        return new ArrayList<>(deliveryRecords);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSamplesWithValidation(Integer pageId) {
        List<NotebookPageSample> samples = notebookPageSampleDAO.getByPageId(pageId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (NotebookPageSample pageSample : samples) {
            Map<String, Object> sampleData = new HashMap<>();
            sampleData.put("id", pageSample.getSampleItemId());
            sampleData.put("pageStatus", pageSample.getStatus().name());

            ValidationStatus validationStatus = getValidationStatus(pageSample);
            String validationReason = null;
            boolean inheritedFromParent = false;

            // Build the combined data map (JSONB data + result info)
            Map<String, Object> combinedData = new HashMap<>();
            if (pageSample.getData() != null) {
                combinedData.putAll(pageSample.getData());
                Object reasonObj = pageSample.getData().get(VALIDATION_REASON_KEY);
                validationReason = reasonObj != null ? reasonObj.toString() : null;
            }

            // Get sample details and result data from Result/Analysis tables
            SampleItem sampleItem = null;
            try {
                String sampleItemId = pageSample.getSampleItemId();
                sampleItem = sampleItemService.get(sampleItemId);
                if (sampleItem != null) {
                    sampleData.put("externalId", sampleItem.getExternalId());
                    if (sampleItem.getTypeOfSample() != null) {
                        sampleData.put("sampleType", sampleItem.getTypeOfSample().getDescription());
                    }

                    // Always get result data from Analysis/Result tables (analyzer results)
                    List<Analysis> analyses = analysisService.getAnalysesBySampleItem(sampleItem);
                    LogEvent.logDebug(this.getClass().getName(), "getSamplesWithValidation", "Sample " + sampleItemId
                            + " has " + (analyses != null ? analyses.size() : 0) + " analyses");

                    if (analyses != null && !analyses.isEmpty()) {
                        StringBuilder resultSummary = new StringBuilder();
                        for (Analysis analysis : analyses) {
                            List<Result> results = resultService.getResultsByAnalysis(analysis);
                            for (Result res : results) {
                                String testName = analysis.getTest() != null ? analysis.getTest().getLocalizedName()
                                        : "Unknown";
                                String value = resultService.getResultValue(res, true);
                                if (value != null && !value.isEmpty()) {
                                    if (resultSummary.length() > 0) {
                                        resultSummary.append("; ");
                                    }
                                    resultSummary.append(testName).append(": ").append(value);
                                }
                            }
                        }
                        if (resultSummary.length() > 0) {
                            combinedData.put("result", resultSummary.toString());
                        }
                    }
                } else {
                    LogEvent.logDebug(this.getClass().getName(), "getSamplesWithValidation",
                            "SampleItem not found for ID: " + sampleItemId);
                }
            } catch (Exception e) {
                // Sample not found or error getting results
                LogEvent.logWarn(this.getClass().getName(), "getSamplesWithValidation",
                        "Error getting sample/result data for " + pageSample.getSampleItemId() + ": " + e.getMessage());
            }

            // If this is a child sample with PENDING validation, check parent's validation
            if (validationStatus == ValidationStatus.PENDING && sampleItem != null) {
                SampleItem parentSampleItem = sampleItem.getParentSampleItem();
                if (parentSampleItem != null) {
                    // Find parent's NotebookPageSample record on this page
                    NotebookPageSample parentPageSample = notebookPageSampleDAO.getByPageIdAndSampleItemId(pageId,
                            Integer.parseInt(parentSampleItem.getId()));

                    if (parentPageSample != null) {
                        ValidationStatus parentValidation = getValidationStatus(parentPageSample);
                        if (parentValidation != ValidationStatus.PENDING) {
                            // Inherit parent's validation status
                            validationStatus = parentValidation;
                            inheritedFromParent = true;

                            // Get parent's validation reason
                            if (parentPageSample.getData() != null) {
                                Object parentReasonObj = parentPageSample.getData().get(VALIDATION_REASON_KEY);
                                if (parentReasonObj != null) {
                                    validationReason = parentReasonObj.toString() + " (inherited from parent)";
                                }
                            }

                            LogEvent.logDebug(this.getClass().getName(), "getSamplesWithValidation",
                                    "Child sample " + pageSample.getSampleItemId()
                                            + " inherited validation status from parent " + parentSampleItem.getId()
                                            + ": " + parentValidation.name());
                        }
                    }
                }
            }

            sampleData.put("validationStatus", validationStatus.name());
            sampleData.put("validationDisplayName", validationStatus.getDisplayName());
            sampleData.put("validationColor", validationStatus.getTagColor());
            sampleData.put("validationReason", validationReason);
            sampleData.put("inheritedFromParent", inheritedFromParent);
            sampleData.put("data", combinedData);
            result.add(sampleData);
        }

        return result;
    }

    @Override
    @Transactional
    public Integer attachReportToNotebook(Integer notebookId, ExportOptions options, String userId) {
        LogEvent.logInfo(this.getClass().getName(), "attachReportToNotebook",
                "Generating and attaching report for notebook ID: " + notebookId);

        // Generate the Excel report
        byte[] excelData = compileToExcel(notebookId, options);

        // Create filename with timestamp
        String timestamp = LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        String fileName = String.format("Results_%s_%s.xlsx", notebookId, timestamp);

        // Attach to notebook using NoteBookService
        Integer fileId = noteBookService.attachFile(notebookId, excelData, fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", userId);

        LogEvent.logInfo(this.getClass().getName(), "attachReportToNotebook",
                "Attached report " + fileName + " to notebook " + notebookId + " with file ID: " + fileId);

        return fileId;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] compilePathologyEntryToExcel(Integer entryId, ExportOptions options) {
        LogEvent.logInfo(this.getClass().getName(), "compilePathologyEntryToExcel",
                "Starting pathology entry Excel export for entry ID: " + entryId);

        NoteBook notebook = noteBookService.get(entryId);
        if (notebook == null) {
            LogEvent.logError(this.getClass().getName(), "compilePathologyEntryToExcel",
                    "Notebook entry not found: " + entryId);
            throw new IllegalArgumentException("Notebook entry not found: " + entryId);
        }
        LogEvent.logInfo(this.getClass().getName(), "compilePathologyEntryToExcel",
                "Found notebook entry: " + notebook.getTitle() + " (ID: " + entryId + ")");

        // Get all samples across all pages for this notebook entry
        List<NotebookPageSample> samples = notebookPageSampleDAO.getByNotebookId(entryId);
        LogEvent.logInfo(this.getClass().getName(), "compilePathologyEntryToExcel",
                "Found " + samples.size() + " page-sample records for entry " + entryId);

        try (Workbook workbook = new XSSFWorkbook()) {
            // Create Summary sheet first
            createPathologySummarySheet(workbook, notebook, samples);

            // Create main Analysis Results sheet
            Sheet sheet = workbook.createSheet(options.title() != null ? options.title() : "Analysis Results");

            // Create header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Create status styles
            CellStyle validStyle = workbook.createCellStyle();
            validStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            validStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle invalidStyle = workbook.createCellStyle();
            invalidStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            invalidStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle inconclusiveStyle = workbook.createCellStyle();
            inconclusiveStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            inconclusiveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Define pathology-specific headers
            List<String> allHeaders = getPathologyComprehensiveHeaders();

            // Build header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < allHeaders.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(allHeaders.get(i));
                cell.setCellStyle(headerStyle);
            }

            // Aggregate sample data across pages - use Map to deduplicate by sampleItemId
            Map<String, Map<String, Object>> aggregatedSamples = new HashMap<>();
            for (NotebookPageSample pageSample : samples) {
                String sampleItemId = pageSample.getSampleItemId();
                if (sampleItemId == null)
                    continue;

                Map<String, Object> existingData = aggregatedSamples.get(sampleItemId);
                if (existingData == null) {
                    existingData = new HashMap<>();
                    existingData.put("sampleItemId", sampleItemId);
                    existingData.put("pageStatus", pageSample.getStatus() != null ? pageSample.getStatus().name() : "");
                    existingData.put("completedAt", pageSample.getCompletedAt());
                    existingData.put("completedBy", pageSample.getCompletedBy());
                    aggregatedSamples.put(sampleItemId, existingData);
                }

                // Merge data from this page's sample
                if (pageSample.getData() != null) {
                    existingData.putAll(pageSample.getData());
                }

                // Update status if this page's sample has a more complete status
                if (pageSample.getStatus() != null) {
                    String currentStatus = (String) existingData.get("pageStatus");
                    String newStatus = pageSample.getStatus().name();
                    // COMPLETED > IN_PROGRESS > PENDING
                    if ("COMPLETED".equals(newStatus)
                            || ("IN_PROGRESS".equals(newStatus) && !"COMPLETED".equals(currentStatus))) {
                        existingData.put("pageStatus", newStatus);
                    }
                }

                // Update completion info if available
                if (pageSample.getCompletedAt() != null) {
                    existingData.put("completedAt", pageSample.getCompletedAt());
                }
                if (pageSample.getCompletedBy() != null) {
                    existingData.put("completedBy", pageSample.getCompletedBy());
                }
            }

            // Data rows
            int rowNum = 1;
            for (Map.Entry<String, Map<String, Object>> entry : aggregatedSamples.entrySet()) {
                String sampleItemId = entry.getKey();
                Map<String, Object> data = entry.getValue();

                try {
                    ValidationStatus validationStatus = ValidationStatus.PENDING;
                    Object statusObj = data.get(VALIDATION_STATUS_KEY);
                    if (statusObj != null) {
                        validationStatus = ValidationStatus.fromString(statusObj.toString());
                    }

                    // Filter based on options
                    if (!options.includeInvalid() && validationStatus == ValidationStatus.INVALID) {
                        continue;
                    }
                    if (!options.includeInconclusive() && validationStatus == ValidationStatus.INCONCLUSIVE) {
                        continue;
                    }

                    Row row = sheet.createRow(rowNum++);

                    // Get sample details
                    String externalId = "";
                    String sampleTypeDesc = "";
                    String accessionNumber = "";
                    String collectionDate = "";

                    // First check if collectionDate is in the data JSON (from sample creation page)
                    String dataCollectionDate = getStringFromData(data, "collectionDate");
                    if (dataCollectionDate.isEmpty()) {
                        dataCollectionDate = getStringFromData(data, "collectionDateTime");
                    }

                    // Extract base numeric ID from composite sample IDs (e.g., "5_cassette_0" ->
                    // "5")
                    // Composite IDs are used in pathology workflows for cassettes, blocks, slides
                    String baseSampleId = sampleItemId;
                    if (sampleItemId != null && sampleItemId.contains("_")) {
                        baseSampleId = sampleItemId.split("_")[0];
                    }

                    // Only lookup if we have a valid numeric ID
                    if (baseSampleId != null && baseSampleId.matches("\\d+")) {
                        try {
                            SampleItem sampleItem = sampleItemService.get(baseSampleId);
                            if (sampleItem != null) {
                                externalId = sampleItem.getExternalId() != null ? sampleItem.getExternalId() : "";
                                if (sampleItem.getTypeOfSample() != null) {
                                    sampleTypeDesc = sampleItem.getTypeOfSample().getDescription();
                                }
                                if (sampleItem.getSample() != null) {
                                    accessionNumber = sampleItem.getSample().getAccessionNumber() != null
                                            ? sampleItem.getSample().getAccessionNumber()
                                            : "";
                                    // Only use SampleItem collection date as fallback
                                    if (dataCollectionDate.isEmpty()
                                            && sampleItem.getSample().getCollectionDate() != null) {
                                        collectionDate = sampleItem.getSample().getCollectionDate().toString();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LogEvent.logDebug(this.getClass().getName(), "compilePathologyEntryToExcel",
                                    "Sample not found: " + baseSampleId);
                        }
                    } else {
                        LogEvent.logDebug(this.getClass().getName(), "compilePathologyEntryToExcel",
                                "Non-numeric sample ID, skipping lookup: " + sampleItemId);
                    }

                    // Use data JSON collection date if available
                    if (!dataCollectionDate.isEmpty()) {
                        collectionDate = dataCollectionDate;
                    }

                    // Populate row with pathology-specific data
                    int colIdx = 0;
                    row.createCell(colIdx++).setCellValue(sampleItemId);
                    row.createCell(colIdx++).setCellValue(externalId);
                    row.createCell(colIdx++).setCellValue(accessionNumber);
                    row.createCell(colIdx++).setCellValue(sampleTypeDesc);
                    row.createCell(colIdx++).setCellValue(collectionDate);

                    // Status and Validation
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "pageStatus"));
                    Cell statusCell = row.createCell(colIdx++);
                    statusCell.setCellValue(validationStatus.getDisplayName());
                    switch (validationStatus) {
                    case VALID:
                        statusCell.setCellStyle(validStyle);
                        break;
                    case INVALID:
                        statusCell.setCellStyle(invalidStyle);
                        break;
                    case INCONCLUSIVE:
                        statusCell.setCellStyle(inconclusiveStyle);
                        break;
                    default:
                        break;
                    }
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, VALIDATION_REASON_KEY));

                    // Reception data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "receptionDate"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "receptionTime"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "projectName"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "manifestReference"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "sourceFacility"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "transportTemperature"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "packageCondition"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "receivingPersonnel"));

                    // Grossing data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "grossDescription"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "specimenWeight"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "specimenDimensions"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfCassettes"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "cassetteLabels"));

                    // Processing data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingProtocol"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "processorId"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingStartTime"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingEndTime"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "fixativeType"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "fixationDuration"));

                    // Block/Embedding data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfBlocks"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingMedium"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingStation"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingQuality"));

                    // Microtomy/Cutting data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfSlides"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "sectionThickness"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "microtomeId"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "cuttingQuality"));

                    // Staining data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainingProtocol"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainType"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "specialStains"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "ihcMarkers"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainingQuality"));

                    // QC data from Quality Control page (qcResult, failReason, failAction)
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcResult"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "failReason"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "failAction"));

                    // QC data from individual workflow pages
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcStatus"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcTissueQuality"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcIssues"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcCorrectiveAction"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcReviewedBy"));

                    // Storage data
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageLocation"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageBox"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageWell"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageCondition"));

                    // Technician/Operator info
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "technicianName"));
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "technicianInitials"));

                    // Notes
                    row.createCell(colIdx++).setCellValue(getStringFromData(data, "notes"));

                    // Completed info
                    Timestamp completedAt = (Timestamp) data.get("completedAt");
                    row.createCell(colIdx++).setCellValue(completedAt != null ? completedAt.toString() : "");
                    String completedByName = "";
                    try {
                        SystemUser completedBy = (SystemUser) data.get("completedBy");
                        if (completedBy != null) {
                            String firstName = completedBy.getFirstName() != null ? completedBy.getFirstName() : "";
                            String lastName = completedBy.getLastName() != null ? completedBy.getLastName() : "";
                            completedByName = (firstName + " " + lastName).trim();
                        }
                    } catch (Exception e) {
                        // Ignore if completedBy is not a SystemUser or has issues
                    }
                    row.createCell(colIdx++).setCellValue(completedByName);

                } catch (Exception e) {
                    LogEvent.logError(this.getClass().getName(), "compilePathologyEntryToExcel",
                            "Error processing sample " + sampleItemId + ": " + e.getMessage());
                }
            }

            // Auto-size columns (limit to prevent very wide columns)
            for (int i = 0; i < allHeaders.size(); i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) > 10000) {
                    sheet.setColumnWidth(i, 10000);
                }
            }

            // Write to byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            LogEvent.logError(this.getClass().getName(), "compilePathologyEntryToExcel",
                    "Failed to generate pathology Excel: " + e.getMessage());
            throw new RuntimeException("Failed to generate pathology Excel report", e);
        }
    }

    /**
     * Create a summary sheet for pathology export with notebook metadata and
     * statistics.
     */
    private void createPathologySummarySheet(Workbook workbook, NoteBook notebook, List<NotebookPageSample> samples) {
        Sheet summarySheet = workbook.createSheet("Summary");

        // Create styles
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        int rowNum = 0;

        // Title
        Row titleRow = summarySheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Pathology Laboratory Export Report");
        titleCell.setCellStyle(titleStyle);

        rowNum++; // Empty row

        // Notebook Info
        Row notebookHeaderRow = summarySheet.createRow(rowNum++);
        Cell nbHeaderCell = notebookHeaderRow.createCell(0);
        nbHeaderCell.setCellValue("Notebook Information");
        nbHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Notebook ID:", String.valueOf(notebook.getId()));
        addSummaryRow(summarySheet, rowNum++, "Notebook Title:",
                notebook.getTitle() != null ? notebook.getTitle() : "");
        addSummaryRow(summarySheet, rowNum++, "Objective:",
                notebook.getObjective() != null ? notebook.getObjective() : "");
        addSummaryRow(summarySheet, rowNum++, "Export Date:",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        rowNum++; // Empty row

        // Sample Statistics - deduplicate by sampleItemId
        java.util.Set<String> uniqueSampleIds = new java.util.HashSet<>();
        long valid = 0, invalid = 0, inconclusive = 0, pending = 0;
        for (NotebookPageSample sample : samples) {
            if (sample.getSampleItemId() == null || uniqueSampleIds.contains(sample.getSampleItemId())) {
                continue;
            }
            uniqueSampleIds.add(sample.getSampleItemId());

            ValidationStatus status = getValidationStatus(sample);
            switch (status) {
            case VALID:
                valid++;
                break;
            case INVALID:
                invalid++;
                break;
            case INCONCLUSIVE:
                inconclusive++;
                break;
            default:
                pending++;
            }
        }

        Row statsHeaderRow = summarySheet.createRow(rowNum++);
        Cell statsHeaderCell = statsHeaderRow.createCell(0);
        statsHeaderCell.setCellValue("Sample Statistics");
        statsHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Total Samples:", String.valueOf(uniqueSampleIds.size()));
        addSummaryRow(summarySheet, rowNum++, "Valid Samples:", String.valueOf(valid));
        addSummaryRow(summarySheet, rowNum++, "Invalid Samples:", String.valueOf(invalid));
        addSummaryRow(summarySheet, rowNum++, "Inconclusive Samples:", String.valueOf(inconclusive));
        addSummaryRow(summarySheet, rowNum++, "Pending Validation:", String.valueOf(pending));

        // Collect unique instruments, reagents, assays
        java.util.Set<String> instruments = new java.util.HashSet<>();
        java.util.Set<String> reagents = new java.util.HashSet<>();
        java.util.Set<String> assays = new java.util.HashSet<>();

        for (NotebookPageSample sample : samples) {
            Map<String, Object> data = sample.getData();
            if (data != null) {
                // Instruments
                String processor = getStringFromData(data, "processorId");
                if (!processor.isEmpty())
                    instruments.add(processor);
                String microtome = getStringFromData(data, "microtomeId");
                if (!microtome.isEmpty())
                    instruments.add(microtome);
                String embeddingStation = getStringFromData(data, "embeddingStation");
                if (!embeddingStation.isEmpty())
                    instruments.add(embeddingStation);

                // Reagents/Kits
                String fixative = getStringFromData(data, "fixativeType");
                if (!fixative.isEmpty())
                    reagents.add(fixative);
                String embeddingMedium = getStringFromData(data, "embeddingMedium");
                if (!embeddingMedium.isEmpty())
                    reagents.add(embeddingMedium);

                // Assays/Stains
                String stainType = getStringFromData(data, "stainType");
                if (!stainType.isEmpty())
                    assays.add(stainType);
                String specialStains = getStringFromData(data, "specialStains");
                if (!specialStains.isEmpty())
                    assays.add(specialStains);
                String ihcMarkers = getStringFromData(data, "ihcMarkers");
                if (!ihcMarkers.isEmpty())
                    assays.add(ihcMarkers);
            }
        }

        rowNum++; // Empty row

        // Instruments Used
        Row instrumentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell instrumentsHeaderCell = instrumentsHeaderRow.createCell(0);
        instrumentsHeaderCell.setCellValue("Instruments Used");
        instrumentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(instruments.size()));
        if (!instruments.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", instruments));
        }

        rowNum++; // Empty row

        // Reagents Used
        Row reagentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell reagentsHeaderCell = reagentsHeaderRow.createCell(0);
        reagentsHeaderCell.setCellValue("Reagent Lots Used");
        reagentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(reagents.size()));
        if (!reagents.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", reagents));
        }

        rowNum++; // Empty row

        // Assays/Stains Performed
        Row assaysHeaderRow = summarySheet.createRow(rowNum++);
        Cell assaysHeaderCell = assaysHeaderRow.createCell(0);
        assaysHeaderCell.setCellValue("Assays/Stains Performed");
        assaysHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(assays.size()));
        if (!assays.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", assays));
        }

        rowNum++; // Empty row

        // Collect unique approvers and verifiers from sample data
        java.util.Set<String> approvers = new java.util.LinkedHashSet<>();
        java.util.Set<String> verifiers = new java.util.LinkedHashSet<>();

        for (NotebookPageSample sample : samples) {
            Map<String, Object> data = sample.getData();
            if (data != null) {
                // QC Reviewers (Approvers)
                String qcReviewedBy = getStringFromData(data, "qcReviewedBy");
                if (!qcReviewedBy.isEmpty())
                    approvers.add(qcReviewedBy);

                // Pathologist Verifiers
                String verifyingPathologist = getStringFromData(data, "verifyingPathologistName");
                if (!verifyingPathologist.isEmpty())
                    verifiers.add(verifyingPathologist);

                // Also check for pathologist signature as a verifier
                String pathologistSignature = getStringFromData(data, "pathologistSignature");
                if (!pathologistSignature.isEmpty() && !pathologistSignature.equals(verifyingPathologist))
                    verifiers.add(pathologistSignature);
            }
        }

        // Approved By section
        Row approvedByHeaderRow = summarySheet.createRow(rowNum++);
        Cell approvedByHeaderCell = approvedByHeaderRow.createCell(0);
        approvedByHeaderCell.setCellValue("Approved By");
        approvedByHeaderCell.setCellStyle(headerStyle);
        if (!approvers.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "Names:", String.join(", ", approvers));
        } else {
            addSummaryRow(summarySheet, rowNum++, "Names:", "(None recorded)");
        }

        rowNum++; // Empty row

        // Verified By section
        Row verifiedByHeaderRow = summarySheet.createRow(rowNum++);
        Cell verifiedByHeaderCell = verifiedByHeaderRow.createCell(0);
        verifiedByHeaderCell.setCellValue("Verified By");
        verifiedByHeaderCell.setCellStyle(headerStyle);
        if (!verifiers.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "Names:", String.join(", ", verifiers));
        } else {
            addSummaryRow(summarySheet, rowNum++, "Names:", "(None recorded)");
        }

        // Auto-size columns
        summarySheet.autoSizeColumn(0);
        summarySheet.autoSizeColumn(1);
    }

    /**
     * Get pathology-specific comprehensive headers for the Analysis Results sheet.
     */
    private List<String> getPathologyComprehensiveHeaders() {
        List<String> headers = new ArrayList<>();

        // Sample Identification
        headers.add("Sample ID");
        headers.add("External ID");
        headers.add("Accession Number");
        headers.add("Sample Type");
        headers.add("Collection Date");

        // Status and Validation
        headers.add("Page Status");
        headers.add("Validation Status");
        headers.add("Validation Reason");

        // Reception
        headers.add("Reception Date");
        headers.add("Reception Time");
        headers.add("Project Name");
        headers.add("Manifest Reference");
        headers.add("Source Facility");
        headers.add("Transport Temperature");
        headers.add("Package Condition");
        headers.add("Receiving Personnel");

        // Grossing
        headers.add("Gross Description");
        headers.add("Specimen Weight");
        headers.add("Specimen Dimensions");
        headers.add("Number of Cassettes");
        headers.add("Cassette Labels");

        // Processing
        headers.add("Processing Protocol");
        headers.add("Processor ID");
        headers.add("Processing Start Time");
        headers.add("Processing End Time");
        headers.add("Fixative Type");
        headers.add("Fixation Duration");

        // Blocking/Embedding
        headers.add("Number of Blocks");
        headers.add("Embedding Medium");
        headers.add("Embedding Station");
        headers.add("Embedding Quality");

        // Microtomy/Cutting
        headers.add("Number of Slides");
        headers.add("Section Thickness");
        headers.add("Microtome ID");
        headers.add("Cutting Quality");

        // Staining
        headers.add("Staining Protocol");
        headers.add("Stain Type");
        headers.add("Special Stains");
        headers.add("IHC Markers");
        headers.add("Staining Quality");

        // QC (from Quality Control page)
        headers.add("QC Result");
        headers.add("Fail Reason");
        headers.add("Fail Action");

        // QC (from individual workflow pages)
        headers.add("QC Status");
        headers.add("QC Tissue Quality");
        headers.add("QC Issues");
        headers.add("QC Corrective Action");
        headers.add("QC Reviewed By");

        // Storage
        headers.add("Storage Location");
        headers.add("Storage Box");
        headers.add("Storage Well");
        headers.add("Storage Condition");

        // Technician
        headers.add("Technician Name");
        headers.add("Technician Initials");

        // Notes
        headers.add("Notes");

        // Completion
        headers.add("Completed At");
        headers.add("Completed By");

        return headers;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] compilePathologyProjectToExcel(Integer projectId, ExportOptions options) {
        LogEvent.logInfo(this.getClass().getName(), "compilePathologyProjectToExcel",
                "Starting pathology project Excel export for project ID: " + projectId);

        NoteBook project = noteBookService.get(projectId);
        if (project == null) {
            LogEvent.logError(this.getClass().getName(), "compilePathologyProjectToExcel",
                    "Project notebook not found: " + projectId);
            throw new IllegalArgumentException("Project notebook not found: " + projectId);
        }

        // Get all entries for this project using the service method that initializes
        // lazy collections
        List<NoteBook> entries = noteBookService.getNoteBookEntries(projectId);
        LogEvent.logInfo(this.getClass().getName(), "compilePathologyProjectToExcel",
                "Found " + entries.size() + " entries for project " + projectId);

        try (Workbook workbook = new XSSFWorkbook()) {
            // Create Project Summary sheet first
            createProjectSummarySheet(workbook, project, entries, options);

            // Create a results sheet for each entry (no separate summary sheets per entry)
            int entryNum = 1;
            for (NoteBook entry : entries) {
                // Get samples for this entry
                List<NotebookPageSample> entrySamples = notebookPageSampleDAO.getByNotebookId(entry.getId());

                // Create entry analysis results sheet only (entry summary sheets removed per
                // user request)
                String resultsSheetName = sanitizeSheetName("Entry " + entryNum + " - Results", workbook);
                createPathologyEntryResultsSheet(workbook, entry, entrySamples, resultsSheetName, options);

                entryNum++;
            }

            // Write to byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            LogEvent.logError(this.getClass().getName(), "compilePathologyProjectToExcel",
                    "Failed to generate pathology project Excel: " + e.getMessage());
            throw new RuntimeException("Failed to generate pathology project Excel report", e);
        }
    }

    /**
     * Sanitize sheet name to be valid for Excel (max 31 chars, no special chars).
     */
    private String sanitizeSheetName(String name, Workbook workbook) {
        // Remove invalid characters
        String sanitized = name.replaceAll("[\\[\\]\\*\\?/\\\\:]", "_");
        // Truncate to 31 characters
        if (sanitized.length() > 31) {
            sanitized = sanitized.substring(0, 31);
        }
        // Ensure uniqueness
        int suffix = 1;
        String baseName = sanitized;
        while (workbook.getSheet(sanitized) != null) {
            String suffixStr = "_" + suffix;
            int maxBase = 31 - suffixStr.length();
            sanitized = baseName.substring(0, Math.min(baseName.length(), maxBase)) + suffixStr;
            suffix++;
        }
        return sanitized;
    }

    /**
     * Create project summary sheet with aggregated statistics across all entries.
     */
    private void createProjectSummarySheet(Workbook workbook, NoteBook project, List<NoteBook> entries,
            ExportOptions options) {
        Sheet summarySheet = workbook.createSheet("Project Summary");

        // Create styles
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 16);
        titleStyle.setFont(titleFont);

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle subHeaderStyle = workbook.createCellStyle();
        Font subHeaderFont = workbook.createFont();
        subHeaderFont.setBold(true);
        subHeaderFont.setFontHeightInPoints((short) 12);
        subHeaderStyle.setFont(subHeaderFont);

        int rowNum = 0;

        // Title
        Row titleRow = summarySheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Pathology Project Report");
        titleCell.setCellStyle(titleStyle);

        rowNum++; // Empty row

        // Project Info
        Row projectHeaderRow = summarySheet.createRow(rowNum++);
        Cell projHeaderCell = projectHeaderRow.createCell(0);
        projHeaderCell.setCellValue("Project Information");
        projHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Project ID:", String.valueOf(project.getId()));
        addSummaryRow(summarySheet, rowNum++, "Project Title:", project.getTitle() != null ? project.getTitle() : "");
        addSummaryRow(summarySheet, rowNum++, "Objective:",
                project.getObjective() != null ? project.getObjective() : "");
        addSummaryRow(summarySheet, rowNum++, "Status:", project.getStatus() != null ? project.getStatus().name() : "");
        addSummaryRow(summarySheet, rowNum++, "Export Date:",
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        rowNum++; // Empty row

        // Entries Overview
        Row entriesHeaderRow = summarySheet.createRow(rowNum++);
        Cell entriesHeaderCell = entriesHeaderRow.createCell(0);
        entriesHeaderCell.setCellValue("Entries Overview");
        entriesHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Total Entries:", String.valueOf(entries.size()));

        // Aggregate statistics across all entries
        long totalSamples = 0;
        long totalValid = 0;
        long totalInvalid = 0;
        long totalInconclusive = 0;
        long totalPending = 0;
        java.util.Set<String> allInstruments = new java.util.HashSet<>();
        java.util.Set<String> allReagents = new java.util.HashSet<>();
        java.util.Set<String> allAssays = new java.util.HashSet<>();
        java.util.Set<String> allApprovers = new java.util.LinkedHashSet<>();
        java.util.Set<String> allVerifiers = new java.util.LinkedHashSet<>();

        for (NoteBook entry : entries) {
            List<NotebookPageSample> entrySamples = notebookPageSampleDAO.getByNotebookId(entry.getId());

            // Deduplicate by sampleItemId within each entry
            java.util.Set<String> uniqueSampleIds = new java.util.HashSet<>();
            for (NotebookPageSample sample : entrySamples) {
                if (sample.getSampleItemId() == null || uniqueSampleIds.contains(sample.getSampleItemId())) {
                    continue;
                }
                uniqueSampleIds.add(sample.getSampleItemId());
                totalSamples++;

                ValidationStatus status = getValidationStatus(sample);
                switch (status) {
                case VALID:
                    totalValid++;
                    break;
                case INVALID:
                    totalInvalid++;
                    break;
                case INCONCLUSIVE:
                    totalInconclusive++;
                    break;
                default:
                    totalPending++;
                }
            }

            // Collect instruments, reagents, assays from all samples
            for (NotebookPageSample sample : entrySamples) {
                Map<String, Object> data = sample.getData();
                if (data != null) {
                    // Instruments
                    String processor = getStringFromData(data, "processorId");
                    if (!processor.isEmpty())
                        allInstruments.add(processor);
                    String microtome = getStringFromData(data, "microtomeId");
                    if (!microtome.isEmpty())
                        allInstruments.add(microtome);
                    String embeddingStation = getStringFromData(data, "embeddingStation");
                    if (!embeddingStation.isEmpty())
                        allInstruments.add(embeddingStation);

                    // Reagents/Kits
                    String fixative = getStringFromData(data, "fixativeType");
                    if (!fixative.isEmpty())
                        allReagents.add(fixative);
                    String embeddingMedium = getStringFromData(data, "embeddingMedium");
                    if (!embeddingMedium.isEmpty())
                        allReagents.add(embeddingMedium);

                    // Assays/Stains
                    String stainType = getStringFromData(data, "stainType");
                    if (!stainType.isEmpty())
                        allAssays.add(stainType);
                    String specialStains = getStringFromData(data, "specialStains");
                    if (!specialStains.isEmpty())
                        allAssays.add(specialStains);
                    String ihcMarkers = getStringFromData(data, "ihcMarkers");
                    if (!ihcMarkers.isEmpty())
                        allAssays.add(ihcMarkers);

                    // Approvers and Verifiers
                    String qcReviewedBy = getStringFromData(data, "qcReviewedBy");
                    if (!qcReviewedBy.isEmpty())
                        allApprovers.add(qcReviewedBy);
                    String verifyingPathologist = getStringFromData(data, "verifyingPathologistName");
                    if (!verifyingPathologist.isEmpty())
                        allVerifiers.add(verifyingPathologist);
                    String pathologistSignature = getStringFromData(data, "pathologistSignature");
                    if (!pathologistSignature.isEmpty() && !pathologistSignature.equals(verifyingPathologist))
                        allVerifiers.add(pathologistSignature);
                }
            }
        }

        rowNum++; // Empty row

        // Sample Statistics (Aggregated)
        Row statsHeaderRow = summarySheet.createRow(rowNum++);
        Cell statsHeaderCell = statsHeaderRow.createCell(0);
        statsHeaderCell.setCellValue("Aggregated Sample Statistics");
        statsHeaderCell.setCellStyle(headerStyle);

        addSummaryRow(summarySheet, rowNum++, "Total Samples (All Entries):", String.valueOf(totalSamples));
        addSummaryRow(summarySheet, rowNum++, "Valid Samples:", String.valueOf(totalValid));
        addSummaryRow(summarySheet, rowNum++, "Invalid Samples:", String.valueOf(totalInvalid));
        addSummaryRow(summarySheet, rowNum++, "Inconclusive Samples:", String.valueOf(totalInconclusive));
        addSummaryRow(summarySheet, rowNum++, "Pending Validation:", String.valueOf(totalPending));

        rowNum++; // Empty row

        // Instruments Used (Aggregated)
        Row instrumentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell instrumentsHeaderCell = instrumentsHeaderRow.createCell(0);
        instrumentsHeaderCell.setCellValue("Instruments Used (All Entries)");
        instrumentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(allInstruments.size()));
        if (!allInstruments.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", allInstruments));
        }

        rowNum++; // Empty row

        // Reagents Used (Aggregated)
        Row reagentsHeaderRow = summarySheet.createRow(rowNum++);
        Cell reagentsHeaderCell = reagentsHeaderRow.createCell(0);
        reagentsHeaderCell.setCellValue("Reagent Lots Used (All Entries)");
        reagentsHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(allReagents.size()));
        if (!allReagents.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", allReagents));
        }

        rowNum++; // Empty row

        // Assays/Stains (Aggregated)
        Row assaysHeaderRow = summarySheet.createRow(rowNum++);
        Cell assaysHeaderCell = assaysHeaderRow.createCell(0);
        assaysHeaderCell.setCellValue("Assays/Stains Performed (All Entries)");
        assaysHeaderCell.setCellStyle(headerStyle);
        addSummaryRow(summarySheet, rowNum++, "Count:", String.valueOf(allAssays.size()));
        if (!allAssays.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "List:", String.join(", ", allAssays));
        }

        rowNum++; // Empty row

        // Approved By (Aggregated)
        Row approvedByHeaderRow = summarySheet.createRow(rowNum++);
        Cell approvedByHeaderCell = approvedByHeaderRow.createCell(0);
        approvedByHeaderCell.setCellValue("Approved By (All Entries)");
        approvedByHeaderCell.setCellStyle(headerStyle);
        if (!allApprovers.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "Names:", String.join(", ", allApprovers));
        } else {
            addSummaryRow(summarySheet, rowNum++, "Names:", "(None recorded)");
        }

        rowNum++; // Empty row

        // Verified By (Aggregated)
        Row verifiedByHeaderRow = summarySheet.createRow(rowNum++);
        Cell verifiedByHeaderCell = verifiedByHeaderRow.createCell(0);
        verifiedByHeaderCell.setCellValue("Verified By (All Entries)");
        verifiedByHeaderCell.setCellStyle(headerStyle);
        if (!allVerifiers.isEmpty()) {
            addSummaryRow(summarySheet, rowNum++, "Names:", String.join(", ", allVerifiers));
        } else {
            addSummaryRow(summarySheet, rowNum++, "Names:", "(None recorded)");
        }

        rowNum++; // Empty row
        rowNum++; // Extra empty row

        // Entries List
        Row entriesListHeaderRow = summarySheet.createRow(rowNum++);
        Cell entriesListHeaderCell = entriesListHeaderRow.createCell(0);
        entriesListHeaderCell.setCellValue("Entries List");
        entriesListHeaderCell.setCellStyle(headerStyle);

        // Table header for entries
        Row tableHeaderRow = summarySheet.createRow(rowNum++);
        String[] tableHeaders = { "#", "Entry ID", "Title", "Status", "Samples", "Valid", "Invalid" };
        for (int i = 0; i < tableHeaders.length; i++) {
            Cell cell = tableHeaderRow.createCell(i);
            cell.setCellValue(tableHeaders[i]);
            cell.setCellStyle(headerStyle);
        }

        // Add each entry as a row
        int entryNum = 1;
        for (NoteBook entry : entries) {
            List<NotebookPageSample> entrySamples = notebookPageSampleDAO.getByNotebookId(entry.getId());

            // Count samples for this entry
            java.util.Set<String> uniqueIds = new java.util.HashSet<>();
            long entryValid = 0, entryInvalid = 0;
            for (NotebookPageSample sample : entrySamples) {
                if (sample.getSampleItemId() == null || uniqueIds.contains(sample.getSampleItemId())) {
                    continue;
                }
                uniqueIds.add(sample.getSampleItemId());
                ValidationStatus status = getValidationStatus(sample);
                if (status == ValidationStatus.VALID)
                    entryValid++;
                else if (status == ValidationStatus.INVALID)
                    entryInvalid++;
            }

            Row entryRow = summarySheet.createRow(rowNum++);
            entryRow.createCell(0).setCellValue(entryNum);
            entryRow.createCell(1).setCellValue(entry.getId());
            entryRow.createCell(2).setCellValue(entry.getTitle() != null ? entry.getTitle() : "");
            entryRow.createCell(3).setCellValue(entry.getStatus() != null ? entry.getStatus().name() : "");
            entryRow.createCell(4).setCellValue(uniqueIds.size());
            entryRow.createCell(5).setCellValue(entryValid);
            entryRow.createCell(6).setCellValue(entryInvalid);
            entryNum++;
        }

        // Auto-size columns
        for (int i = 0; i < 7; i++) {
            summarySheet.autoSizeColumn(i);
        }
    }

    /**
     * Create entry analysis results sheet for project report.
     */
    private void createPathologyEntryResultsSheet(Workbook workbook, NoteBook entry, List<NotebookPageSample> samples,
            String sheetName, ExportOptions options) {
        Sheet sheet = workbook.createSheet(sheetName);

        // Create header style
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Create status styles
        CellStyle validStyle = workbook.createCellStyle();
        validStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        validStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle invalidStyle = workbook.createCellStyle();
        invalidStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        invalidStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle inconclusiveStyle = workbook.createCellStyle();
        inconclusiveStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        inconclusiveStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Define headers
        List<String> allHeaders = getPathologyComprehensiveHeaders();

        // Build header row
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < allHeaders.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(allHeaders.get(i));
            cell.setCellStyle(headerStyle);
        }

        // Aggregate sample data across pages
        Map<String, Map<String, Object>> aggregatedSamples = new HashMap<>();
        for (NotebookPageSample pageSample : samples) {
            String sampleItemId = pageSample.getSampleItemId();
            if (sampleItemId == null)
                continue;

            Map<String, Object> existingData = aggregatedSamples.get(sampleItemId);
            if (existingData == null) {
                existingData = new HashMap<>();
                existingData.put("sampleItemId", sampleItemId);
                existingData.put("pageStatus", pageSample.getStatus() != null ? pageSample.getStatus().name() : "");
                existingData.put("completedAt", pageSample.getCompletedAt());
                existingData.put("completedBy", pageSample.getCompletedBy());
                aggregatedSamples.put(sampleItemId, existingData);
            }

            if (pageSample.getData() != null) {
                existingData.putAll(pageSample.getData());
            }

            if (pageSample.getStatus() != null) {
                String currentStatus = (String) existingData.get("pageStatus");
                String newStatus = pageSample.getStatus().name();
                if ("COMPLETED".equals(newStatus)
                        || ("IN_PROGRESS".equals(newStatus) && !"COMPLETED".equals(currentStatus))) {
                    existingData.put("pageStatus", newStatus);
                }
            }

            if (pageSample.getCompletedAt() != null) {
                existingData.put("completedAt", pageSample.getCompletedAt());
            }
            if (pageSample.getCompletedBy() != null) {
                existingData.put("completedBy", pageSample.getCompletedBy());
            }
        }

        // Data rows
        int rowNum = 1;
        for (Map.Entry<String, Map<String, Object>> sampleEntry : aggregatedSamples.entrySet()) {
            String sampleItemId = sampleEntry.getKey();
            Map<String, Object> data = sampleEntry.getValue();

            try {
                ValidationStatus validationStatus = ValidationStatus.PENDING;
                Object statusObj = data.get(VALIDATION_STATUS_KEY);
                if (statusObj != null) {
                    validationStatus = ValidationStatus.fromString(statusObj.toString());
                }

                // Filter based on options
                if (!options.includeInvalid() && validationStatus == ValidationStatus.INVALID) {
                    continue;
                }
                if (!options.includeInconclusive() && validationStatus == ValidationStatus.INCONCLUSIVE) {
                    continue;
                }

                Row row = sheet.createRow(rowNum++);

                // Get sample details
                String externalId = "";
                String sampleTypeDesc = "";
                String accessionNumber = "";
                String collectionDate = "";

                // First check if collectionDate is in the data JSON (from sample creation page)
                String dataCollectionDate = getStringFromData(data, "collectionDate");
                if (dataCollectionDate.isEmpty()) {
                    dataCollectionDate = getStringFromData(data, "collectionDateTime");
                }

                String baseSampleId = sampleItemId;
                if (sampleItemId != null && sampleItemId.contains("_")) {
                    baseSampleId = sampleItemId.split("_")[0];
                }

                if (baseSampleId != null && baseSampleId.matches("\\d+")) {
                    try {
                        SampleItem sampleItem = sampleItemService.get(baseSampleId);
                        if (sampleItem != null) {
                            externalId = sampleItem.getExternalId() != null ? sampleItem.getExternalId() : "";
                            if (sampleItem.getTypeOfSample() != null) {
                                sampleTypeDesc = sampleItem.getTypeOfSample().getDescription();
                            }
                            if (sampleItem.getSample() != null) {
                                accessionNumber = sampleItem.getSample().getAccessionNumber() != null
                                        ? sampleItem.getSample().getAccessionNumber()
                                        : "";
                                // Only use SampleItem collection date as fallback
                                if (dataCollectionDate.isEmpty()
                                        && sampleItem.getSample().getCollectionDate() != null) {
                                    collectionDate = sampleItem.getSample().getCollectionDate().toString();
                                }
                            }
                        }
                    } catch (Exception e) {
                        LogEvent.logDebug(this.getClass().getName(), "createPathologyEntryResultsSheet",
                                "Sample not found: " + baseSampleId);
                    }
                }

                // Use data JSON collection date if available
                if (!dataCollectionDate.isEmpty()) {
                    collectionDate = dataCollectionDate;
                }

                // Populate row - same as compilePathologyEntryToExcel
                int colIdx = 0;
                row.createCell(colIdx++).setCellValue(sampleItemId);
                row.createCell(colIdx++).setCellValue(externalId);
                row.createCell(colIdx++).setCellValue(accessionNumber);
                row.createCell(colIdx++).setCellValue(sampleTypeDesc);
                row.createCell(colIdx++).setCellValue(collectionDate);

                row.createCell(colIdx++).setCellValue(getStringFromData(data, "pageStatus"));
                Cell statusCell = row.createCell(colIdx++);
                statusCell.setCellValue(validationStatus.getDisplayName());
                switch (validationStatus) {
                case VALID:
                    statusCell.setCellStyle(validStyle);
                    break;
                case INVALID:
                    statusCell.setCellStyle(invalidStyle);
                    break;
                case INCONCLUSIVE:
                    statusCell.setCellStyle(inconclusiveStyle);
                    break;
                default:
                    break;
                }
                row.createCell(colIdx++).setCellValue(getStringFromData(data, VALIDATION_REASON_KEY));

                // Reception data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "receptionDate"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "receptionTime"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "projectName"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "manifestReference"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "sourceFacility"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "transportTemperature"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "packageCondition"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "receivingPersonnel"));

                // Grossing data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "grossDescription"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "specimenWeight"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "specimenDimensions"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfCassettes"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "cassetteLabels"));

                // Processing data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingProtocol"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "processorId"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingStartTime"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "processingEndTime"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "fixativeType"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "fixationDuration"));

                // Block/Embedding data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfBlocks"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingMedium"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingStation"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "embeddingQuality"));

                // Microtomy/Cutting data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "numberOfSlides"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "sectionThickness"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "microtomeId"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "cuttingQuality"));

                // Staining data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainingProtocol"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainType"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "specialStains"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "ihcMarkers"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "stainingQuality"));

                // QC data from Quality Control page (qcResult, failReason, failAction)
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcResult"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "failReason"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "failAction"));

                // QC data from individual workflow pages
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcStatus"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcTissueQuality"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcIssues"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcCorrectiveAction"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "qcReviewedBy"));

                // Storage data
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageLocation"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageBox"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageWell"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "storageCondition"));

                // Technician/Operator info
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "technicianName"));
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "technicianInitials"));

                // Notes
                row.createCell(colIdx++).setCellValue(getStringFromData(data, "notes"));

                // Completed info
                Timestamp completedAt = (Timestamp) data.get("completedAt");
                row.createCell(colIdx++).setCellValue(completedAt != null ? completedAt.toString() : "");
                String completedByName = "";
                try {
                    SystemUser completedBy = (SystemUser) data.get("completedBy");
                    if (completedBy != null) {
                        String firstName = completedBy.getFirstName() != null ? completedBy.getFirstName() : "";
                        String lastName = completedBy.getLastName() != null ? completedBy.getLastName() : "";
                        completedByName = (firstName + " " + lastName).trim();
                    }
                } catch (Exception e) {
                    // Ignore
                }
                row.createCell(colIdx++).setCellValue(completedByName);

            } catch (Exception e) {
                LogEvent.logError(this.getClass().getName(), "createPathologyEntryResultsSheet",
                        "Error processing sample " + sampleItemId + ": " + e.getMessage());
            }
        }

        // Auto-size columns (limit width)
        for (int i = 0; i < allHeaders.size(); i++) {
            sheet.autoSizeColumn(i);
            if (sheet.getColumnWidth(i) > 10000) {
                sheet.setColumnWidth(i, 10000);
            }
        }
    }
}
