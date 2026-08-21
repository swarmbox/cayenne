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

package org.apache.cayenne.access.flush.operation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.DataContextObjectStore;
import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.di.Provider;
import org.apache.cayenne.exp.DbPathExp;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;

/**
 * Orders the runs of operations that {@link DefaultDbRowOpSorter}'s comparator can't order, as
 * {@link GraphBasedDbRowOpSorter} does, while reading only the committed rows that can add a dependency within the
 * run: those of its DELETEs, and those of its UPDATEs that write the foreign key of a relationship, for the
 * relationships to an entity the run has operations on. It reads each such row once, and the rows missing from the
 * snapshot cache with one query per entity.
 *
 * @since 5.0
 */
class RowDependencySorter extends GraphBasedDbRowOpSorter {

    RowDependencySorter(Provider<DataDomain> dataDomainProvider) {
        super(dataDomainProvider);
    }

    @Override
    CommittedRows committedRows(List<DbRowOp> dbRows) {
        return new RunRows(dbRows);
    }

    /**
     * The committed rows of one run, read when the run is sorted.
     */
    private static class RunRows implements CommittedRows {

        private final Set<String> entitiesInRun;
        private final Map<ObjectId, DataRow> rows;

        RunRows(List<DbRowOp> run) {
            this.entitiesInRun = new HashSet<>();
            for (DbRowOp op : run) {
                entitiesInRun.add(op.getEntity().getName());
            }
            this.rows = readRows(run);
        }

        /**
         * Returns whether an operation of the run may be the parent of another through the relationship, which
         * needs one on the relationship's target entity.
         */
        @Override
        public boolean follows(DbRelationship relationship) {
            return entitiesInRun.contains(relationship.getTargetEntityName());
        }

        @Override
        public DataRow rowOf(DbRowOp op, DbRelationship relationship) {
            return needsRow(op, relationship) ? rows.get(op.getObject().getObjectId()) : null;
        }

        /**
         * Reads a flattened row's key only for a relationship from that row's table. The flattened rows of an object
         * are rows of other tables, e.g. the sub-table row of a vertical-inheritance sub-entity, so their keys say
         * nothing about the foreign keys of the object's own table: read as one whose target column has the same name,
         * a key would make the object reference itself.
         */
        @Override
        public boolean readsFlattenedId(ObjectId flattenedId, DbRelationship relationship) {
            return flattenedId.getEntityName().equals(DbPathExp.DB_PREFIX + relationship.getSourceEntityName());
        }

        private Map<ObjectId, DataRow> readRows(List<DbRowOp> run) {
            // the operations of a flush all come from one context, so this is a single store
            Map<DataContextObjectStore, Set<ObjectId>> idsByStore = new LinkedHashMap<>();
            for (DbRowOp op : run) {
                if (needsRow(op)) {
                    DataContextObjectStore store = ((DataContext) op.getObject().getObjectContext()).getObjectStore();
                    idsByStore.computeIfAbsent(store, s -> new LinkedHashSet<>()).add(op.getObject().getObjectId());
                }
            }

            Map<ObjectId, DataRow> rows = new HashMap<>();
            idsByStore.forEach((store, ids) -> rows.putAll(store.getSnapshots(ids)));
            return rows;
        }

        private boolean needsRow(DbRowOp op) {
            for (DbRelationship relationship : op.getEntity().getRelationships()) {
                if (relationship.isFromFK() && follows(relationship) && needsRow(op, relationship)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns whether the committed row of the operation's object can tell its parent through the relationship.
         * A DELETE's row holds the foreign key its delete releases. An UPDATE's row holds the foreign key it releases
         * only if it writes that key: the dependency sorter reads no other column of an UPDATE's committed row, and
         * reads none for an UPDATE of a flattened row.
         */
        private static boolean needsRow(DbRowOp op, DbRelationship relationship) {
            return switch (op) {
                case DeleteDbRowOp ignored -> true;
                case UpdateDbRowOp update -> isObjectRow(update) && writesForeignKey(update, relationship);
                default -> false;
            };
        }

        private static boolean isObjectRow(DbRowOp op) {
            return !op.getChangeId().getEntityName().startsWith(DbPathExp.DB_PREFIX);
        }

        private static boolean writesForeignKey(UpdateDbRowOp update, DbRelationship relationship) {
            Map<String, Object> written = update.getValues().getSnapshot();
            for (DbJoin join : relationship.getJoins()) {
                if (written.containsKey(join.getSourceName())) {
                    return true;
                }
            }
            return false;
        }
    }
}
