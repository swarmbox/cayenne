/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/

package org.apache.cayenne.access;

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.access.flush.DataDomainFlushAction;
import org.apache.cayenne.access.flush.DataDomainFlushActionFactory;
import org.apache.cayenne.access.flush.DefaultDataDomainFlushAction;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOpFactory;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.graph.CompoundDiff;
import org.apache.cayenne.graph.GraphDiff;
import org.apache.cayenne.query.Query;

import java.util.List;

/**
 * A subclass of the default flush action whose {@link #flush} composes the protected steps itself, as an override
 * that wraps or measures each step does.
 */
public class StepwiseFlushAction extends DefaultDataDomainFlushAction {

    StepwiseFlushAction(DataDomain dataDomain, DbRowOpSorter sorter, DeleteDbRowOpFactory deleteDbRowOpFactory) {
        super(dataDomain, sorter, deleteDbRowOpFactory);
    }

    @Override
    public GraphDiff flush(DataContext context, GraphDiff changes) {
        CompoundDiff afterCommitDiff = new CompoundDiff();
        if (changes == null) {
            return afterCommitDiff;
        }
        if (!(changes instanceof ObjectStoreGraphDiff objectStoreGraphDiff)) {
            throw new CayenneRuntimeException("Instance of ObjectStoreGraphDiff expected, got %s", changes.getClass());
        }

        DataContextObjectStore objectStore = context.getObjectStore();

        List<DbRowOp> dbRowOps = createDbRowOps(objectStore, objectStoreGraphDiff);
        updateObjectIds(dbRowOps);
        List<DbRowOp> deduplicatedOps = mergeSameObjectIds(dbRowOps);
        List<DbRowOp> filteredOps = filterOps(deduplicatedOps);
        List<DbRowOp> sortedOps = sort(filteredOps);
        List<? extends Query> queries = createQueries(sortedOps);
        executeQueries(queries);
        createReplacementIds(objectStore, afterCommitDiff, sortedOps);
        postprocess(context, objectStoreGraphDiff, afterCommitDiff, deduplicatedOps);

        return afterCommitDiff;
    }

    public static class Factory implements DataDomainFlushActionFactory {

        @Inject
        private DbRowOpSorter sorter;

        @Inject
        private DeleteDbRowOpFactory deleteDbRowOpFactory;

        @Override
        public DataDomainFlushAction createFlushAction(DataDomain dataDomain) {
            return new StepwiseFlushAction(dataDomain, sorter, deleteDbRowOpFactory);
        }
    }
}
