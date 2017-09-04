/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.table;

import org.apache.log4j.Logger;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.state.StateEvent;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.processor.stream.window.FindableProcessor;
import org.wso2.siddhi.core.util.ExceptionUtil;
import org.wso2.siddhi.core.util.collection.AddingStreamEventExtractor;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.BackoffRetryCounter;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.execution.query.output.stream.UpdateSet;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interface class to represent Tables in Siddhi. There are multiple implementations. Ex: {@link InMemoryTable}. Table
 * will support basic operations of add, delete, update, update or add and contains. *
 */
public abstract class Table implements FindableProcessor {

    private static final Logger LOG = Logger.getLogger(Table.class);

    protected TableDefinition tableDefinition;

    private AtomicBoolean isTryingToConnect = new AtomicBoolean(false);
    private BackoffRetryCounter backoffRetryCounter = new BackoffRetryCounter();
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private ScheduledExecutorService scheduledExecutorService;
    private SiddhiAppContext siddhiAppContext;

    public void initTable(TableDefinition tableDefinition, StreamEventPool storeEventPool,
                          StreamEventCloner storeEventCloner,
                          ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        this.tableDefinition = tableDefinition;
        this.scheduledExecutorService = siddhiAppContext.getScheduledExecutorService();
        this.siddhiAppContext = siddhiAppContext;
        init(tableDefinition, storeEventPool, storeEventCloner, configReader, siddhiAppContext);
    }

    protected abstract void init(TableDefinition tableDefinition, StreamEventPool storeEventPool,
                                 StreamEventCloner storeEventCloner,
                                 ConfigReader configReader, SiddhiAppContext siddhiAppContext);

    public TableDefinition getTableDefinition() {
        return tableDefinition;
    }

    public void addEvents(ComplexEventChunk<StreamEvent> addingEventChunk) {
        if (isConnected.get()) {
            try {
                add(addingEventChunk);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                addEvents(addingEventChunk);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Dropping event at Table '" +
                    tableDefinition.getId() + "' as its still trying to reconnect!, events dropped '" +
                    addingEventChunk + "'");
        } else {
            connectWithRetry();
            addEvents(addingEventChunk);
        }
    }

    protected abstract void add(ComplexEventChunk<StreamEvent> addingEventChunk) throws ConnectionUnavailableException;

    public StreamEvent find(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        if (isConnected.get()) {
            try {
                return find(compiledCondition, matchingEvent);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                return find(matchingEvent, compiledCondition);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Find operation failed for event '" +
                    matchingEvent + "', at Table '" + tableDefinition.getId() + "' as its still trying to reconnect!");
            return null;
        } else {
            connectWithRetry();
            return find(matchingEvent, compiledCondition);
        }
    }

    protected abstract StreamEvent find(CompiledCondition compiledCondition, StateEvent matchingEvent)
            throws ConnectionUnavailableException;

    public void deleteEvents(ComplexEventChunk<StateEvent> deletingEventChunk, CompiledCondition compiledCondition) {
        if (isConnected.get()) {
            try {
                delete(deletingEventChunk, compiledCondition);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                deleteEvents(deletingEventChunk, compiledCondition);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Dropping event at Table '" +
                    tableDefinition.getId() + "' as its still trying to reconnect!, events dropped '" +
                    deletingEventChunk + "'");
        } else {
            connectWithRetry();
            deleteEvents(deletingEventChunk, compiledCondition);
        }
    }

    protected abstract void delete(ComplexEventChunk<StateEvent> deletingEventChunk,
                                   CompiledCondition compiledCondition) throws ConnectionUnavailableException;


    public void updateEvents(ComplexEventChunk<StateEvent> updatingEventChunk,
                             CompiledCondition compiledCondition,
                             CompiledUpdateSet compiledUpdateSet) {
        if (isConnected.get()) {
            try {
                update(updatingEventChunk, compiledCondition, compiledUpdateSet);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                updateEvents(updatingEventChunk, compiledCondition, compiledUpdateSet);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Dropping event at Table '" +
                    tableDefinition.getId() + "' as its still trying to reconnect!, events dropped '" +
                    updatingEventChunk + "'");
        } else {
            connectWithRetry();
            updateEvents(updatingEventChunk, compiledCondition, compiledUpdateSet);
        }
    }

    protected abstract void update(ComplexEventChunk<StateEvent> updatingEventChunk,
                                   CompiledCondition compiledCondition,
                                   CompiledUpdateSet compiledUpdateSet)
            throws ConnectionUnavailableException;

    public void updateOrAddEvents(ComplexEventChunk<StateEvent> updateOrAddingEventChunk,
                                  CompiledCondition compiledCondition,
                                  CompiledUpdateSet compiledUpdateSet,
                                  AddingStreamEventExtractor addingStreamEventExtractor) {
        if (isConnected.get()) {
            try {
                updateOrAdd(updateOrAddingEventChunk, compiledCondition, compiledUpdateSet,
                        addingStreamEventExtractor);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                updateOrAddEvents(updateOrAddingEventChunk, compiledCondition, compiledUpdateSet,
                        addingStreamEventExtractor);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Dropping event at Table '" +
                    tableDefinition.getId() + "' as its still trying to reconnect!, events dropped '" +
                    updateOrAddingEventChunk + "'");
        } else {
            connectWithRetry();
            updateOrAddEvents(updateOrAddingEventChunk, compiledCondition, compiledUpdateSet,
                    addingStreamEventExtractor);
        }
    }

    protected abstract void updateOrAdd(ComplexEventChunk<StateEvent> updateOrAddingEventChunk,
                                        CompiledCondition compiledCondition,
                                        CompiledUpdateSet compiledUpdateSet,
                                        AddingStreamEventExtractor addingStreamEventExtractor)
            throws ConnectionUnavailableException;

    public boolean containsEvent(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        if (isConnected.get()) {
            try {
                return contains(matchingEvent, compiledCondition);
            } catch (ConnectionUnavailableException e) {
                isConnected.set(false);
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " Connection unavailable at Table '" + tableDefinition.getId() +
                        "', will retry connection immediately.", e);
                connectWithRetry();
                return containsEvent(matchingEvent, compiledCondition);
            }
        } else if (isTryingToConnect.get()) {
            LOG.error("Error on '" + siddhiAppContext.getName() + "'. Dropping event at Table '" +
                    tableDefinition.getId() + "' as its still trying to reconnect!, event matching failed for event '"
                    + matchingEvent + "'.");
            return false;
        } else {
            connectWithRetry();
            return containsEvent(matchingEvent, compiledCondition);
        }
    }

    protected abstract boolean contains(StateEvent matchingEvent, CompiledCondition compiledCondition)
            throws ConnectionUnavailableException;

    public void connectWithRetry() {
        if (!isConnected.get()) {
            isTryingToConnect.set(true);
            try {
                connect();
                isConnected.set(true);
                isTryingToConnect.set(false);
                backoffRetryCounter.reset();
            } catch (ConnectionUnavailableException e) {
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) + " Error while " +
                        "connecting to Table '" + tableDefinition.getId() + "', will retry in '" +
                        backoffRetryCounter.getTimeInterval() + "'.", e);
                scheduledExecutorService.schedule(new Runnable() {
                    @Override
                    public void run() {
                        connectWithRetry();
                    }
                }, backoffRetryCounter.getTimeIntervalMillis(), TimeUnit.MILLISECONDS);
                backoffRetryCounter.increment();
            } catch (RuntimeException e) {
                LOG.error(ExceptionUtil.getMessageWithContext(e, siddhiAppContext) +
                        " . Error while connecting to Table '" + tableDefinition.getId() + "'.", e);
                throw e;
            }
        }
    }

    /**
     * Builds the "compiled" set clause of an update query.
     * Here, all the pre-processing that can be done prior to receiving the update event is done,
     * so that such pre-processing work will not be done at each update-event-arrival.
     *
     * @param updateSet                   the set of assignment expressions, each containing the table column to be
     *                                    updated and the expression to be assigned.
     * @param matchingMetaInfoHolder      the meta structure of the incoming matchingEvent
     * @param siddhiAppContext            current siddhi app context
     * @param variableExpressionExecutors the list of variable ExpressionExecutors already created
     * @param tableMap                    map of event tables
     * @param queryName                   query name to which the update statement belongs.
     * @return
     */
    public abstract CompiledUpdateSet compileUpdateSet(UpdateSet updateSet,
                                                       MatchingMetaInfoHolder matchingMetaInfoHolder,
                                                       SiddhiAppContext siddhiAppContext,
                                                       List<VariableExpressionExecutor> variableExpressionExecutors,
                                                       Map<String, Table> tableMap, String queryName);

    protected abstract void connect() throws ConnectionUnavailableException;

    protected abstract void disconnect();

    protected abstract void destroy();

    public void shutdown() {
        disconnect();
        destroy();
        isConnected.set(false);
        isTryingToConnect.set(false);
    }
}
