// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.statistics;

import org.apache.doris.analysis.AnalyzeStmt;
import org.apache.doris.analysis.DropStatsStmt;
import org.apache.doris.analysis.ShowAnalyzeStmt;
import org.apache.doris.analysis.TableName;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.MaterializedIndexMeta;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.catalog.TableIf.TableType;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.FeConstants;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.ShowResultSet;
import org.apache.doris.qe.ShowResultSetMetaData;
import org.apache.doris.statistics.AnalysisTaskInfo.AnalysisMethod;
import org.apache.doris.statistics.AnalysisTaskInfo.AnalysisType;
import org.apache.doris.statistics.AnalysisTaskInfo.JobType;
import org.apache.doris.statistics.AnalysisTaskInfo.ScheduleType;
import org.apache.doris.statistics.util.InternalQueryResult.ResultRow;
import org.apache.doris.statistics.util.StatisticsUtil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AnalysisManager {

    public final AnalysisTaskScheduler taskScheduler;

    private static final Logger LOG = LogManager.getLogger(AnalysisManager.class);

    private static final String UPDATE_JOB_STATE_SQL_TEMPLATE = "UPDATE "
            + FeConstants.INTERNAL_DB_NAME + "." + StatisticConstants.ANALYSIS_JOB_TABLE + " "
            + "SET state = '${jobState}' ${message} ${updateExecTime} WHERE job_id = ${jobId} and task_id=${taskId}";

    private static final String SHOW_JOB_STATE_SQL_TEMPLATE = "SELECT "
            + "job_id, catalog_name, db_name, tbl_name, col_name, job_type, "
            + "analysis_type, message, last_exec_time_in_ms, state, schedule_type "
            + "FROM " + FeConstants.INTERNAL_DB_NAME + "." + StatisticConstants.ANALYSIS_JOB_TABLE;

    // The time field that needs to be displayed
    private static final String LAST_EXEC_TIME_IN_MS = "last_exec_time_in_ms";

    private final ConcurrentMap<Long, Map<Long, AnalysisTaskInfo>> analysisJobIdToTaskMap;

    private StatisticsCache statisticsCache;

    private final AnalysisTaskExecutor taskExecutor;

    public AnalysisManager() {
        analysisJobIdToTaskMap = new ConcurrentHashMap<>();
        this.taskScheduler = new AnalysisTaskScheduler();
        taskExecutor = new AnalysisTaskExecutor(taskScheduler);
        this.statisticsCache = new StatisticsCache();
        taskExecutor.start();
    }

    public StatisticsCache getStatisticsCache() {
        return statisticsCache;
    }

    // Each analyze stmt corresponding to an analysis job.
    public void createAnalysisJob(AnalyzeStmt stmt) throws DdlException {
        long jobId = Env.getCurrentEnv().getNextId();
        AnalysisTaskInfoBuilder taskInfoBuilder = buildCommonTaskInfo(stmt, jobId);
        Map<Long, AnalysisTaskInfo> analysisTaskInfos = new HashMap<>();
        // start build analysis tasks
        createTaskForEachColumns(stmt.getColumnNames(), taskInfoBuilder, analysisTaskInfos, stmt.isSync());
        createTaskForMVIdx(stmt.getTable(), taskInfoBuilder, analysisTaskInfos, stmt.getAnalysisType(), stmt.isSync());

        if (stmt.isSync()) {
            syncExecute(analysisTaskInfos.values());
            return;
        }

        persistAnalysisJob(taskInfoBuilder);
        analysisJobIdToTaskMap.put(jobId, analysisTaskInfos);
        analysisTaskInfos.values().forEach(taskScheduler::schedule);
        sendJobId(jobId);
    }

    private void sendJobId(long jobId) {
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("Job_Id", ScalarType.createVarchar(19)));
        ShowResultSetMetaData commonResultSetMetaData = new ShowResultSetMetaData(columns);
        List<List<String>> resultRows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        row.add(String.valueOf(jobId));
        resultRows.add(row);
        ShowResultSet commonResultSet = new ShowResultSet(commonResultSetMetaData, resultRows);
        try {
            ConnectContext.get().getExecutor().sendResultSet(commonResultSet);
        } catch (Throwable t) {
            LOG.warn("Failed to send job id to user", t);
        }
    }

    private AnalysisTaskInfoBuilder buildCommonTaskInfo(AnalyzeStmt stmt, long jobId) {
        AnalysisTaskInfoBuilder taskInfoBuilder = new AnalysisTaskInfoBuilder();
        String catalogName = stmt.getCatalogName();
        String db = stmt.getDBName();
        TableName tbl = stmt.getTblName();
        StatisticsUtil.convertTableNameToObjects(tbl);
        String tblName = tbl.getTbl();
        int samplePercent = stmt.getSamplePercent();
        int sampleRows = stmt.getSampleRows();
        AnalysisType analysisType = stmt.getAnalysisType();
        AnalysisMethod analysisMethod = stmt.getAnalysisMethod();

        taskInfoBuilder.setJobId(jobId);
        taskInfoBuilder.setCatalogName(catalogName);
        taskInfoBuilder.setDbName(db);
        taskInfoBuilder.setTblName(tblName);
        taskInfoBuilder.setJobType(JobType.MANUAL);
        taskInfoBuilder.setState(AnalysisState.PENDING);
        taskInfoBuilder.setScheduleType(ScheduleType.ONCE);

        if (analysisMethod == AnalysisMethod.SAMPLE) {
            taskInfoBuilder.setAnalysisMethod(AnalysisMethod.SAMPLE);
            taskInfoBuilder.setSamplePercent(samplePercent);
            taskInfoBuilder.setSampleRows(sampleRows);
        } else {
            taskInfoBuilder.setAnalysisMethod(AnalysisMethod.FULL);
        }

        if (analysisType == AnalysisType.HISTOGRAM) {
            taskInfoBuilder.setAnalysisType(AnalysisType.HISTOGRAM);
            int numBuckets = stmt.getNumBuckets();
            int maxBucketNum = numBuckets > 0 ? numBuckets
                    : StatisticConstants.HISTOGRAM_MAX_BUCKET_NUM;
            taskInfoBuilder.setMaxBucketNum(maxBucketNum);
        } else {
            taskInfoBuilder.setAnalysisType(AnalysisType.COLUMN);
        }

        return taskInfoBuilder;
    }

    private void persistAnalysisJob(AnalysisTaskInfoBuilder taskInfoBuilder) throws DdlException {
        try {
            AnalysisTaskInfoBuilder jobInfoBuilder = taskInfoBuilder.copy();
            AnalysisTaskInfo analysisTaskInfo = jobInfoBuilder.setTaskId(-1).build();
            StatisticsRepository.persistAnalysisTask(analysisTaskInfo);
        } catch (Throwable t) {
            throw new DdlException(t.getMessage(), t);
        }
    }

    private void createTaskForMVIdx(TableIf table, AnalysisTaskInfoBuilder taskInfoBuilder,
            Map<Long, AnalysisTaskInfo> analysisTaskInfos, AnalysisType analysisType,
            boolean isSync) throws DdlException {
        TableType type = table.getType();
        if (analysisType != AnalysisType.INDEX || !type.equals(TableType.OLAP)) {
            // not need to collect statistics for materialized view
            return;
        }

        taskInfoBuilder.setAnalysisType(analysisType);
        OlapTable olapTable = (OlapTable) table;

        try {
            olapTable.readLock();
            for (MaterializedIndexMeta meta : olapTable.getIndexIdToMeta().values()) {
                if (meta.getDefineStmt() == null) {
                    continue;
                }
                AnalysisTaskInfoBuilder indexTaskInfoBuilder = taskInfoBuilder.copy();
                long indexId = meta.getIndexId();
                long taskId = Env.getCurrentEnv().getNextId();
                AnalysisTaskInfo analysisTaskInfo = indexTaskInfoBuilder.setIndexId(indexId)
                        .setTaskId(taskId).build();
                if (isSync) {
                    return;
                }
                try {
                    StatisticsRepository.persistAnalysisTask(analysisTaskInfo);
                } catch (Exception e) {
                    throw new DdlException("Failed to create analysis task", e);
                }
            }
        } finally {
            olapTable.readUnlock();
        }
    }

    private void createTaskForEachColumns(Set<String> colNames, AnalysisTaskInfoBuilder taskInfoBuilder,
            Map<Long, AnalysisTaskInfo> analysisTaskInfos, boolean isSync) throws DdlException {
        for (String colName : colNames) {
            AnalysisTaskInfoBuilder colTaskInfoBuilder = taskInfoBuilder.copy();
            long indexId = -1;
            long taskId = Env.getCurrentEnv().getNextId();
            AnalysisTaskInfo analysisTaskInfo = colTaskInfoBuilder.setColName(colName)
                    .setIndexId(indexId).setTaskId(taskId).build();
            analysisTaskInfos.put(taskId, analysisTaskInfo);
            if (isSync) {
                continue;
            }
            try {
                StatisticsRepository.persistAnalysisTask(analysisTaskInfo);
            } catch (Exception e) {
                throw new DdlException("Failed to create analysis task", e);
            }

        }
    }

    public void updateTaskStatus(AnalysisTaskInfo info, AnalysisState jobState, String message, long time) {
        if (analysisJobIdToTaskMap.get(info.jobId) == null) {
            return;
        }
        Map<String, String> params = new HashMap<>();
        params.put("jobState", jobState.toString());
        params.put("message", StringUtils.isNotEmpty(message) ? String.format(", message = '%s'", message) : "");
        params.put("updateExecTime", time == -1 ? "" : ", last_exec_time_in_ms=" + time);
        params.put("jobId", String.valueOf(info.jobId));
        params.put("taskId", String.valueOf(info.taskId));
        try {
            StatisticsUtil.execUpdate(new StringSubstitutor(params).replace(UPDATE_JOB_STATE_SQL_TEMPLATE));
        } catch (Exception e) {
            LOG.warn(String.format("Failed to update state for task: %d, %d", info.jobId, info.taskId), e);
        } finally {
            info.state = jobState;
            if (analysisJobIdToTaskMap.get(info.jobId).values()
                    .stream().allMatch(i -> i.state != null
                            && i.state != AnalysisState.PENDING && i.state != AnalysisState.RUNNING)) {
                analysisJobIdToTaskMap.remove(info.jobId);
                params.put("taskId", String.valueOf(-1));
                try {
                    StatisticsUtil.execUpdate(new StringSubstitutor(params).replace(UPDATE_JOB_STATE_SQL_TEMPLATE));
                } catch (Exception e) {
                    LOG.warn(String.format("Failed to update state for job: %s", info.jobId), e);
                }
            }
        }
    }

    public List<List<Comparable>> showAnalysisJob(ShowAnalyzeStmt stmt) throws DdlException {
        String whereClause = stmt.getWhereClause();
        long limit = stmt.getLimit();
        String executeSql = SHOW_JOB_STATE_SQL_TEMPLATE
                + (whereClause.isEmpty() ? "" : " WHERE " + whereClause)
                + (limit == -1L ? "" : " LIMIT " + limit);

        List<List<Comparable>> results = Lists.newArrayList();
        ImmutableList<String> titleNames = stmt.getTitleNames();
        List<ResultRow> resultRows = StatisticsUtil.execStatisticQuery(executeSql);

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (ResultRow resultRow : resultRows) {
            List<Comparable> result = Lists.newArrayList();
            for (String column : titleNames) {
                String value = resultRow.getColumnValue(column);
                if (LAST_EXEC_TIME_IN_MS.equals(column)) {
                    long timeMillis = Long.parseLong(value);
                    value = dateFormat.format(new Date(timeMillis));
                }
                result.add(value);
            }
            results.add(result);
        }

        return results;
    }

    private void syncExecute(Collection<AnalysisTaskInfo> taskInfos) {
        List<String> colNames = new ArrayList<>();
        for (AnalysisTaskInfo info : taskInfos) {
            try {
                TableIf table = StatisticsUtil.findTable(info.catalogName,
                        info.dbName, info.tblName);
                BaseAnalysisTask analysisTask = table.createAnalysisTask(info);
                analysisTask.execute();
            } catch (Throwable t) {
                colNames.add(info.colName);
                LOG.info("Failed to analyze, info: {}", info);
            }
        }
        if (!colNames.isEmpty()) {
            throw new RuntimeException("Failed to analyze following columns: " + String.join(",", colNames));
        }
    }

    public void dropStats(DropStatsStmt dropStatsStmt) throws DdlException {
        if (dropStatsStmt.dropExpired) {
            Env.getCurrentEnv().getStatisticsCleaner().clear();
            return;
        }
        Set<String> cols = dropStatsStmt.getColumnNames();
        long tblId = dropStatsStmt.getTblId();
        StatisticsRepository.dropStatistics(tblId, cols);
        for (String col : cols) {
            Env.getCurrentEnv().getStatisticsCache().invidate(tblId, -1L, col);
        }
    }

}
