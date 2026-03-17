/**
 * InstrumentDetailModal Component
 *
 * Modal showing detailed information about a laboratory instrument's QC status.
 * Launched from the "View" button in InstrumentsTab.
 *
 * Features:
 * - Instrument metadata header with compliance status
 * - Analytes Monitored cards with sigma values
 * - Activity Timeline tab (merged violations + corrective actions)
 * - Control Chart tab with analyte selector and embedded Levey-Jennings chart
 */

import React, { useState, useEffect, useMemo, useCallback } from "react";
import {
  ComposedModal,
  ModalHeader,
  ModalBody,
  ModalFooter,
  Button,
  Tag,
  Tile,
  Tabs,
  TabList,
  Tab,
  TabPanels,
  TabPanel,
  Dropdown,
  Loading,
  Grid,
  Column,
} from "@carbon/react";
import { useIntl } from "react-intl";
import PropTypes from "prop-types";
import { getFromOpenElisServer } from "../../utils/Utils";
import LeveyJenningsChart from "../charts/LeveyJenningsChart";
import {
  getComplianceTagType,
  getComplianceLabelKey,
  getZScoreBadgeType,
  getSeverityTagType,
  formatTimestamp,
} from "./qcDashboardUtils";
import "./InstrumentDetailModal.css";

const InstrumentDetailModal = ({ instrument, open, onClose }) => {
  const intl = useIntl();

  // Activity Timeline state
  const [violations, setViolations] = useState([]);
  const [correctiveActions, setCorrectiveActions] = useState([]);
  const [timelineLoading, setTimelineLoading] = useState(false);

  // Control Chart state
  const [selectedAnalyteIndex, setSelectedAnalyteIndex] = useState(0);
  const [chartData, setChartData] = useState([]);
  const [chartStatistics, setChartStatistics] = useState(null);
  const [chartLoading, setChartLoading] = useState(false);

  // Active QC Rules state
  const [activeRules, setActiveRules] = useState([]);

  // Sub-tab state
  const [activeSubTab, setActiveSubTab] = useState(0);

  // Reset state when modal opens/closes or instrument changes
  useEffect(() => {
    if (!open || !instrument) {
      setViolations([]);
      setCorrectiveActions([]);
      setChartData([]);
      setChartStatistics(null);
      setActiveRules([]);
      setSelectedAnalyteIndex(0);
      setActiveSubTab(0);
      return;
    }

    // Load timeline data on open
    setTimelineLoading(true);
    let completedCalls = 0;
    const totalCalls = 2;

    const checkComplete = () => {
      completedCalls++;
      if (completedCalls >= totalCalls) {
        setTimelineLoading(false);
      }
    };

    getFromOpenElisServer(
      `/rest/qc/violations?instrumentId=${instrument.instrumentId}`,
      (response) => {
        const data = Array.isArray(response) ? response : response?.data || [];
        setViolations(data);
        checkComplete();
      },
    );

    getFromOpenElisServer(`/rest/qc/corrective-actions`, (response) => {
      const data = Array.isArray(response) ? response : response?.data || [];
      setCorrectiveActions(data);
      checkComplete();
    });
  }, [open, instrument]);

  // Transform backend dataPoints to LeveyJenningsChart format
  const transformDataPoints = (dataPoints) => {
    return (dataPoints || []).map((pt) => ({
      id: pt.resultId,
      runDateTime: pt.timestamp,
      resultValue: pt.value,
      value: pt.value,
      zScore: pt.zscore ?? pt.zScore,
      violated: pt.hasViolation,
      violations: (pt.violatedRules || []).map((rule) => ({
        code: rule,
      })),
    }));
  };

  // Load chart data for a specific control lot (two parallel calls)
  const loadChartForControlLot = useCallback((controlLotId) => {
    setChartLoading(true);
    setChartData([]);
    setChartStatistics(null);

    let completedCalls = 0;
    const checkDone = () => {
      completedCalls++;
      if (completedCalls >= 2) {
        setChartLoading(false);
      }
    };

    // Fetch data points
    getFromOpenElisServer(`/rest/qc/charts/${controlLotId}`, (response) => {
      const dataPoints =
        response?.dataPoints || response?.data?.dataPoints || [];
      setChartData(transformDataPoints(dataPoints));
      checkDone();
    });

    // Fetch statistics (mean, SD for reference lines)
    getFromOpenElisServer(
      `/rest/qc/charts/${controlLotId}/statistics`,
      (response) => {
        if (response && response.mean != null) {
          setChartStatistics(response);
        } else {
          setChartStatistics(null);
        }
        checkDone();
      },
    );
  }, []);

  // When chart tab activates or analyte changes, fetch control lots then chart data
  useEffect(() => {
    if (!open || !instrument || activeSubTab !== 1) return;

    const analyte = instrument.analyteDetails?.[selectedAnalyteIndex];
    if (!analyte) return;

    setChartLoading(true);

    getFromOpenElisServer(
      `/rest/qc/controlLots?testId=${analyte.testId}&instrumentId=${instrument.instrumentId}`,
      (response) => {
        const lots = Array.isArray(response) ? response : response?.data || [];
        if (lots.length > 0) {
          loadChartForControlLot(lots[0].id);
        } else {
          setChartData([]);
          setChartStatistics(null);
          setChartLoading(false);
        }
      },
    );

    // Fetch active QC rules for this analyte+instrument
    getFromOpenElisServer(
      `/rest/qc/ruleConfig/enabled?testId=${analyte.testId}&instrumentId=${instrument.instrumentId}`,
      (response) => {
        const rules = Array.isArray(response) ? response : response?.data || [];
        setActiveRules(rules.filter((r) => r.enabled));
      },
    );
  }, [
    open,
    instrument,
    activeSubTab,
    selectedAnalyteIndex,
    loadChartForControlLot,
  ]);

  // Build analyte dropdown options
  const analyteOptions = useMemo(() => {
    return (instrument?.analyteDetails || []).map((analyte, idx) => ({
      id: String(analyte.testId),
      index: idx,
      label: analyte.testName,
      testId: analyte.testId,
    }));
  }, [instrument]);

  // Merge violations + corrective actions into timeline
  const timelineItems = useMemo(() => {
    const violationIds = new Set(violations.map((v) => v.id));

    const violationItems = violations.map((v) => ({
      type: "violation",
      timestamp: v.violationDateTime,
      severity: v.severity,
      ruleCode: v.ruleCode,
      testName: v.testName,
      resolutionStatus: v.resolutionStatus || v.status,
      id: v.id,
    }));

    const actionItems = correctiveActions
      .filter((a) => violationIds.has(a.violationId))
      .map((a) => ({
        type: "correctiveAction",
        timestamp: a.createdDate || a.createdDateTime,
        actionType: a.actionType,
        status: a.status,
        assignedUserName: a.assignedUserName || a.assignedUser?.displayName,
        id: a.id,
      }));

    return [...violationItems, ...actionItems].sort(
      (a, b) => new Date(b.timestamp) - new Date(a.timestamp),
    );
  }, [violations, correctiveActions]);

  // Get corrective action status tag type
  const getActionStatusTagType = (status) => {
    switch (status) {
      case "COMPLETED":
        return "green";
      case "IN_PROGRESS":
        return "blue";
      default:
        return "gray";
    }
  };

  // Get violation resolution status tag type
  const getResolutionStatusTagType = (status) => {
    switch (status) {
      case "RESOLVED":
        return "green";
      case "CORRECTIVE_ACTION_PENDING":
        return "blue";
      case "ACKNOWLEDGED":
        return "gray";
      default:
        return "red";
    }
  };

  if (!instrument) return null;

  const analyteDetails = instrument.analyteDetails || [];

  return (
    <ComposedModal
      open={open}
      onClose={onClose}
      size="lg"
      data-testid="instrument-detail-modal"
    >
      <ModalHeader
        title={instrument.instrumentName}
        label={
          <span className="instrument-detail-subtitle">
            <span>{instrument.instrumentId}</span>
            <span className="instrument-detail-subtitle__separator">
              &middot;
            </span>
            <span>{instrument.instrumentType}</span>
            <span className="instrument-detail-subtitle__separator">
              &middot;
            </span>
            <span>{instrument.instrumentLocation}</span>
            <span className="instrument-detail-subtitle__separator">
              &middot;
            </span>
            <Tag
              type={getComplianceTagType(instrument.complianceColor)}
              size="sm"
            >
              {intl.formatMessage({
                id: getComplianceLabelKey(instrument.complianceColor),
              })}
            </Tag>
          </span>
        }
        data-testid="instrument-detail-modal-header"
      />
      <ModalBody data-testid="instrument-detail-modal-body">
        {/* Analytes Monitored Section */}
        <div className="instrument-detail-section">
          <h4>
            {intl.formatMessage({
              id: "qc.instrumentDetail.analytesMonitored",
            })}
          </h4>
          <div className="instrument-detail-analytes">
            {analyteDetails.map((analyte) => (
              <Tile
                key={analyte.testId}
                className="instrument-detail-analyte-card"
              >
                <div className="instrument-detail-analyte-card__name">
                  {analyte.testName}
                </div>
                <div className="instrument-detail-analyte-card__meta">
                  {analyte.latestZScore != null && (
                    <Tag
                      type={getZScoreBadgeType(analyte.latestZScore)}
                      size="sm"
                    >
                      {Math.abs(parseFloat(analyte.latestZScore)).toFixed(1)}
                      &sigma;
                    </Tag>
                  )}
                </div>
                <div className="instrument-detail-analyte-card__detail">
                  {analyte.lastRunTime && (
                    <span className="instrument-detail-analyte-card__time">
                      {formatTimestamp(analyte.lastRunTime)}
                    </span>
                  )}
                </div>
              </Tile>
            ))}
          </div>
        </div>

        {/* Sub-tabs: Activity Timeline + Control Chart */}
        <Tabs
          selectedIndex={activeSubTab}
          onChange={({ selectedIndex }) => setActiveSubTab(selectedIndex)}
        >
          <TabList aria-label="Instrument detail tabs">
            <Tab>
              {intl.formatMessage({
                id: "qc.instrumentDetail.tab.activityTimeline",
              })}
            </Tab>
            <Tab>
              {intl.formatMessage({
                id: "qc.instrumentDetail.tab.controlChart",
              })}
            </Tab>
          </TabList>
          <TabPanels>
            {/* Activity Timeline Tab */}
            <TabPanel>
              {timelineLoading ? (
                <div className="instrument-detail-timeline__loading">
                  <Loading
                    withOverlay={false}
                    small
                    description={intl.formatMessage({
                      id: "qc.instrumentDetail.timeline.loading",
                    })}
                  />
                </div>
              ) : timelineItems.length === 0 ? (
                <div className="instrument-detail-timeline__empty">
                  {intl.formatMessage({
                    id: "qc.instrumentDetail.timeline.empty",
                  })}
                </div>
              ) : (
                <div className="instrument-detail-timeline">
                  {timelineItems.map((item) => (
                    <div
                      key={`${item.type}-${item.id}`}
                      className="instrument-detail-timeline__item"
                    >
                      <div className="instrument-detail-timeline__item-header">
                        {item.type === "violation" ? (
                          <>
                            <Tag
                              type={getSeverityTagType(item.severity)}
                              size="sm"
                            >
                              {item.severity}
                            </Tag>
                            <span className="instrument-detail-timeline__item-rule">
                              {item.ruleCode}
                            </span>
                            {item.testName && (
                              <span className="instrument-detail-timeline__item-test">
                                {item.testName}
                              </span>
                            )}
                          </>
                        ) : (
                          <>
                            <Tag type="blue" size="sm">
                              {intl.formatMessage({
                                id: "qc.instrumentDetail.timeline.correctiveAction",
                              })}
                            </Tag>
                            <span className="instrument-detail-timeline__item-rule">
                              {item.actionType}
                            </span>
                            {item.assignedUserName && (
                              <span className="instrument-detail-timeline__item-test">
                                {item.assignedUserName}
                              </span>
                            )}
                          </>
                        )}
                        <span className="instrument-detail-timeline__item-time">
                          {formatTimestamp(item.timestamp)}
                        </span>
                      </div>
                      <div className="instrument-detail-timeline__item-status">
                        {item.type === "violation" ? (
                          <Tag
                            type={getResolutionStatusTagType(
                              item.resolutionStatus,
                            )}
                            size="sm"
                          >
                            {item.resolutionStatus}
                          </Tag>
                        ) : (
                          <Tag
                            type={getActionStatusTagType(item.status)}
                            size="sm"
                          >
                            {item.status}
                          </Tag>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </TabPanel>

            {/* Control Chart Tab */}
            <TabPanel>
              {analyteOptions.length > 1 && (
                <div className="instrument-detail-chart__controls">
                  <Dropdown
                    id="instrument-detail-analyte-selector"
                    titleText={intl.formatMessage({
                      id: "qc.instrumentDetail.chart.selectAnalyte",
                    })}
                    items={analyteOptions}
                    selectedItem={analyteOptions[selectedAnalyteIndex]}
                    itemToString={(item) => item?.label || ""}
                    onChange={({ selectedItem }) =>
                      setSelectedAnalyteIndex(selectedItem?.index ?? 0)
                    }
                    data-testid="instrument-detail-analyte-dropdown"
                  />
                </div>
              )}
              {analyteOptions.length === 1 && (
                <div className="instrument-detail-chart__controls">
                  <p className="instrument-detail-chart__analyte-label">
                    {analyteOptions[0]?.label}
                  </p>
                </div>
              )}
              <div className="instrument-detail-chart">
                {chartLoading ? (
                  <div className="instrument-detail-chart__loading">
                    <Loading
                      withOverlay={false}
                      small
                      description={intl.formatMessage({
                        id: "qc.instrumentDetail.chart.loading",
                      })}
                    />
                  </div>
                ) : chartData.length === 0 ? (
                  <div className="instrument-detail-chart__empty">
                    {intl.formatMessage({
                      id: "qc.instrumentDetail.chart.noData",
                    })}
                  </div>
                ) : (
                  <LeveyJenningsChart
                    data={chartData}
                    statistics={chartStatistics}
                    height="350px"
                    showLegend={true}
                  />
                )}
              </div>

              {/* Statistics Cards */}
              {chartStatistics && !chartLoading && (
                <Grid className="instrument-detail-chart__stats-section">
                  <Column lg={4} md={2} sm={2}>
                    <Tile className="instrument-detail-chart__stat-card">
                      <span className="instrument-detail-chart__stat-label">
                        {intl.formatMessage({
                          id: "qc.instrumentDetail.chart.stats.mean",
                        })}
                      </span>
                      <span className="instrument-detail-chart__stat-value">
                        {chartStatistics.mean?.toFixed(2) ?? "-"}
                      </span>
                    </Tile>
                  </Column>
                  <Column lg={4} md={2} sm={2}>
                    <Tile className="instrument-detail-chart__stat-card">
                      <span className="instrument-detail-chart__stat-label">
                        {intl.formatMessage({
                          id: "qc.instrumentDetail.chart.stats.sd",
                        })}
                      </span>
                      <span className="instrument-detail-chart__stat-value">
                        {chartStatistics.standardDeviation?.toFixed(2) ?? "-"}
                      </span>
                    </Tile>
                  </Column>
                  <Column lg={4} md={2} sm={2}>
                    <Tile className="instrument-detail-chart__stat-card">
                      <span className="instrument-detail-chart__stat-label">
                        {intl.formatMessage({
                          id: "qc.instrumentDetail.chart.stats.cv",
                        })}
                      </span>
                      <span className="instrument-detail-chart__stat-value">
                        {chartStatistics.mean &&
                        chartStatistics.standardDeviation
                          ? (
                              (chartStatistics.standardDeviation /
                                Math.abs(chartStatistics.mean)) *
                              100
                            ).toFixed(1) + "%"
                          : "-"}
                      </span>
                    </Tile>
                  </Column>
                  <Column lg={4} md={2} sm={2}>
                    <Tile className="instrument-detail-chart__stat-card">
                      <span className="instrument-detail-chart__stat-label">
                        {intl.formatMessage({
                          id: "qc.instrumentDetail.chart.stats.n",
                        })}
                      </span>
                      <span className="instrument-detail-chart__stat-value">
                        {chartStatistics.resultCount ?? chartData.length}
                      </span>
                    </Tile>
                  </Column>
                </Grid>
              )}

              {/* Active QC Rules */}
              {!chartLoading && (
                <div className="instrument-detail-chart__rules-section">
                  <h5>
                    {intl.formatMessage({
                      id: "qc.instrumentDetail.chart.activeRules",
                    })}
                  </h5>
                  {activeRules.length > 0 ? (
                    <div className="instrument-detail-chart__rules-tags">
                      {activeRules.map((rule) => (
                        <Tag
                          key={rule.id || rule.ruleCode}
                          type={rule.severity === "REJECTION" ? "red" : "teal"}
                          size="sm"
                        >
                          {rule.ruleCode}
                        </Tag>
                      ))}
                    </div>
                  ) : (
                    <p className="instrument-detail-chart__no-rules">
                      {intl.formatMessage({
                        id: "qc.instrumentDetail.chart.noRules",
                      })}
                    </p>
                  )}
                </div>
              )}
            </TabPanel>
          </TabPanels>
        </Tabs>
      </ModalBody>
      <ModalFooter data-testid="instrument-detail-modal-footer">
        <Button kind="secondary" onClick={onClose}>
          {intl.formatMessage({ id: "button.close" })}
        </Button>
      </ModalFooter>
    </ComposedModal>
  );
};

InstrumentDetailModal.propTypes = {
  instrument: PropTypes.shape({
    instrumentId: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    instrumentName: PropTypes.string,
    instrumentType: PropTypes.string,
    instrumentLocation: PropTypes.string,
    complianceColor: PropTypes.string,
    analyteDetails: PropTypes.arrayOf(
      PropTypes.shape({
        testId: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
        testName: PropTypes.string,
        latestZScore: PropTypes.number,
        lastRunTime: PropTypes.string,
      }),
    ),
    triggeredRuleDetails: PropTypes.array,
    unresolvedRejections: PropTypes.number,
    unresolvedWarnings: PropTypes.number,
    activeControlLots: PropTypes.number,
  }),
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
};

export default InstrumentDetailModal;
