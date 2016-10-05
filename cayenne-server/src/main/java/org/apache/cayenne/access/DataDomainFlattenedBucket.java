/*****************************************************************
 *   Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/

package org.apache.cayenne.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.query.BatchQueryRow;
import org.apache.cayenne.query.DeleteBatchQuery;
import org.apache.cayenne.query.InsertBatchQuery;
import org.apache.cayenne.query.Query;

/**
 * A sync bucket that holds flattened queries.
 * 
 * @since 1.2
 */
class DataDomainFlattenedBucket {

    final DataDomainFlushAction parent;
    final Map<DbEntity, List<FlattenedArcKey>> insertArcKeys;
    final Map<DbEntity, DeleteBatchQuery> flattenedDeleteQueries;

    DataDomainFlattenedBucket(DataDomainFlushAction parent) {
        this.parent = parent;
        this.insertArcKeys = new HashMap<DbEntity, List<FlattenedArcKey>>();
        this.flattenedDeleteQueries = new HashMap<DbEntity, DeleteBatchQuery>();
    }

    boolean isEmpty() {
        return insertArcKeys.isEmpty() && flattenedDeleteQueries.isEmpty();
    }

    void addInsertArcKey(DbEntity flattenedEntity, FlattenedArcKey flattenedArcKey) {
        List<FlattenedArcKey> arcKeys = insertArcKeys.get(flattenedEntity);

        if (arcKeys == null) {
            arcKeys = new ArrayList<FlattenedArcKey>();
            insertArcKeys.put(flattenedEntity, arcKeys);
        }

        arcKeys.add(flattenedArcKey);
    }

    void addFlattenedDelete(DbEntity flattenedEntity, FlattenedArcKey flattenedDeleteInfo) {

        DeleteBatchQuery relationDeleteQuery = flattenedDeleteQueries.get(flattenedEntity);
        if (relationDeleteQuery == null) {
            boolean optimisticLocking = false;
            Collection<DbAttribute> pk = flattenedEntity.getPrimaryKeys();
            List<DbAttribute> pkList = pk instanceof List ? (List<DbAttribute>) pk : new ArrayList<DbAttribute>(pk);
            relationDeleteQuery = new DeleteBatchQuery(flattenedEntity, pkList, Collections.<String> emptySet(), 50);
            relationDeleteQuery.setUsingOptimisticLocking(optimisticLocking);
            flattenedDeleteQueries.put(flattenedEntity, relationDeleteQuery);
        }

        DataNode node = parent.getDomain().lookupDataNode(flattenedEntity.getDataMap());
        List flattenedSnapshots = flattenedDeleteInfo.buildJoinSnapshotsForDelete(node);
        if (!flattenedSnapshots.isEmpty()) {
            Iterator snapsIt = flattenedSnapshots.iterator();
            while (snapsIt.hasNext()) {
                relationDeleteQuery.add((Map) snapsIt.next());
            }
        }
    }

    /**
     * responsible for adding the flattened Insert Queries. Its possible an insert query for the same DbEntity/ObjectId
     * already has been added from the insert bucket queries if that Object also has an attribute. So we want to merge
     * the data for each insert into a single insert.
     *
     * @param queries
     */
    void appendInserts(Collection<Query> queries) {
        for (Map.Entry<DbEntity, List<FlattenedArcKey>> entry : insertArcKeys.entrySet()) {
            DbEntity dbEntity = entry.getKey();
            List<FlattenedArcKey> flattenedArcKeys = entry.getValue();

            DataNode node = parent.getDomain().lookupDataNode(dbEntity.getDataMap());

            InsertBatchQuery existingQuery = findInsertBatchQuery(queries, dbEntity);
            InsertBatchQuery newQuery = new InsertBatchQuery(dbEntity, 50);

            for (FlattenedArcKey flattenedArcKey : flattenedArcKeys) {
                Map<String, Object> snapshot = flattenedArcKey.buildJoinSnapshotForInsert(node);

                if (existingQuery != null) {
                    BatchQueryRow existingRow = findRowForObjectId(existingQuery.getRows(), flattenedArcKey.id1.getSourceId());
                    // todo: do we need to worry about flattenedArcKey.id2 ?

                    if (existingRow != null) {
                        List<DbAttribute> existingQueryDbAttributes = existingQuery.getDbAttributes();

                        for(int i=0; i < existingQueryDbAttributes.size(); i++) {
                            Object value = existingRow.getValue(i);
                            if (value != null) {
                                snapshot.put(existingQueryDbAttributes.get(i).getName(), value);
                            }
                        }
                    }
                }

                newQuery.add(snapshot);
            }

            if (existingQuery != null) {
                queries.remove(existingQuery);
            }

            queries.add(newQuery);
        }
    }

    void appendDeletes(Collection<Query> queries) {
        if (!flattenedDeleteQueries.isEmpty()) {
            queries.addAll(flattenedDeleteQueries.values());
        }
    }

    private InsertBatchQuery findInsertBatchQuery(Collection<Query> queries, DbEntity dbEntity) {
        for(Query query : queries) {
            if (query instanceof InsertBatchQuery) {
                InsertBatchQuery insertBatchQuery = (InsertBatchQuery)query;
                if (insertBatchQuery.getDbEntity().equals(dbEntity)) {
                    return insertBatchQuery;
                }
            }
        }
        return null;
    }

    private BatchQueryRow findRowForObjectId(List<BatchQueryRow> rows, ObjectId objectId) {
        for (BatchQueryRow row : rows) {
            if (row.getObjectId().equals(objectId)) {
                return row;
            }
        }
        return null;
    }
}
