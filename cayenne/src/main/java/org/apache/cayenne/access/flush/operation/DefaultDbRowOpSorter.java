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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.di.Provider;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.EntitySorter;
import org.apache.cayenne.map.ObjEntity;

/**
 * @since 4.2
 */
public class DefaultDbRowOpSorter implements DbRowOpSorter {

    protected final Provider<DataDomain> dataDomainProvider;
    protected volatile Comparator<DbRowOp> comparator;
    private volatile DbRowOpSorter dependencySorter;

    public DefaultDbRowOpSorter(@Inject Provider<DataDomain> dataDomainProvider) {
        this.dataDomainProvider = dataDomainProvider;
    }

    @Override
    public List<DbRowOp> sort(List<DbRowOp> dbRows) {
        // sort by id, operation type and entity relations
        dbRows.sort(getComparator());
        // sort reflexively dependent objects
        sortReflexive(dbRows);
        // resolve ties the entity comparator can't, i.e. operations within one strongly connected component
        sortWithinComponents(dbRows);

        return dbRows;
    }

    /**
     * Re-sorts every maximal run of operations the comparator can't separate -- same operation type, same strongly
     * connected component of the entity graph -- when that run spans more than one entity. Such a run is ordered by
     * row-level dependencies, which is the only thing that can tell apart two entities that reference each other.
     * On any failure, including a dependency cycle, the run keeps the order it already had.
     *
     * @since 5.0
     */
    protected void sortWithinComponents(List<DbRowOp> sortedDbRows) {
        // a single-entity flush has no cross-entity tie to break; returning here also keeps this step from
        // consulting the entity comparator at all, which is the common case
        if(!spansMultipleEntities(sortedDbRows)) {
            return;
        }

        Comparator<DbRowOp> rowComparator = getComparator();
        int start = 0;
        for(int i = 1; i <= sortedDbRows.size(); i++) {
            if(i == sortedDbRows.size() || rowComparator.compare(sortedDbRows.get(i - 1), sortedDbRows.get(i)) != 0) {
                sortByRowDependency(sortedDbRows.subList(start, i));
                start = i;
            }
        }
    }

    private void sortByRowDependency(List<DbRowOp> tiedRun) {
        if(!spansMultipleEntities(tiedRun)) {
            return;
        }
        try {
            List<DbRowOp> sorted = getDependencySorter().sort(new ArrayList<>(tiedRun));
            if(sorted.size() == tiedRun.size()) {
                Collections.copy(tiedRun, sorted);
            }
        } catch (RuntimeException ex) {
            // a row-level cycle or an unresolvable dependency must not turn a flush that would otherwise run into
            // an internal failure, so the run keeps the order the comparator gave it
        }
    }

    private boolean spansMultipleEntities(List<DbRowOp> dbRows) {
        if(dbRows.size() < 2) {
            return false;
        }
        DbEntity first = dbRows.get(0).getEntity();
        for(int i = 1; i < dbRows.size(); i++) {
            if(dbRows.get(i).getEntity() != first) {
                return true;
            }
        }
        return false;
    }

    private DbRowOpSorter getDependencySorter() {
        DbRowOpSorter local = dependencySorter;
        if(local == null) {
            synchronized (this) {
                local = dependencySorter;
                if(local == null) {
                    local = new GraphBasedDbRowOpSorter(dataDomainProvider);
                    dependencySorter = local;
                }
            }
        }
        return local;
    }

    protected void sortReflexive(List<DbRowOp> sortedDbRows) {
        DataDomain dataDomain = dataDomainProvider.get();
        EntitySorter sorter = dataDomain.getEntitySorter();
        EntityResolver resolver = dataDomain.getEntityResolver();

        DbEntity lastEntity = null;
        int start = 0;
        int idx = 0;
        DbRowOp lastRow = null;
        for (int i = 0; i < sortedDbRows.size(); i++) {
            DbRowOp row = sortedDbRows.get(i);
            if (row.getEntity() != lastEntity) {
                // we do not sort update operations
                if(lastEntity != null && !(lastRow instanceof UpdateDbRowOp) && sorter.isReflexive(lastEntity)) {
                    ObjEntity objEntity = resolver.getObjEntity(lastRow.getObject().getObjectId().getEntityName());
                    List<DbRowOp> reflexiveSublist = sortedDbRows.subList(start, idx);
                    sorter.sortObjectsForEntity(objEntity, reflexiveSublist, lastRow instanceof DeleteDbRowOp);
                }
                start = idx;
                lastEntity = row.getEntity();
            }
            lastRow = row;
            idx++;
        }
        // sort last chunk
        if(lastEntity != null && !(lastRow instanceof UpdateDbRowOp) && sorter.isReflexive(lastEntity)) {
            ObjEntity objEntity = resolver.getObjEntity(lastRow.getObject().getObjectId().getEntityName());
            List<DbRowOp> reflexiveSublist = sortedDbRows.subList(start, idx);
            sorter.sortObjectsForEntity(objEntity, reflexiveSublist, lastRow instanceof DeleteDbRowOp);
        }
    }

    protected Comparator<DbRowOp> getComparator() {
        Comparator<DbRowOp> local = comparator;
        if(local == null) {
            synchronized (this) {
                local = comparator;
                if(local == null) {
                    local = new DbRowComparator(dataDomainProvider.get().getEntitySorter());
                    comparator = local;
                }
            }
        }
        return local;
    }

    protected static class DbRowComparator implements Comparator<DbRowOp> {

        private final EntitySorter entitySorter;

        protected DbRowComparator(EntitySorter entitySorter) {
            this.entitySorter = entitySorter;
        }

        @Override
        public int compare(DbRowOp left, DbRowOp right) {
            DbRowOpType leftType = left.accept(DbRowTypeVisitor.INSTANCE);
            DbRowOpType rightType = right.accept(DbRowTypeVisitor.INSTANCE);
            int result = leftType.compareTo(rightType);

            // 1. sort by op type
            if(result != 0) {
                return result;
            }

            // 2. sort by entity relations, we don't really need this for updates, but do it for the stable result
            result = entitySorter.getDbEntityComparator().compare(left.getEntity(), right.getEntity());
            if(result != 0) {
                // invert result for delete
                return leftType == DbRowOpType.DELETE ? -result : result;
            }

            // TODO: 3. sort updates by changed and null attributes to batch it better,
            //  need to check cost vs benefit though
            return result;
        }
    }

    protected static class DbRowTypeVisitor implements DbRowOpVisitor<DbRowOpType> {

        private static final DbRowTypeVisitor INSTANCE = new DbRowTypeVisitor();

        @Override
        public DbRowOpType visitInsert(InsertDbRowOp diffSnapshot) {
            return DbRowOpType.INSERT;
        }

        @Override
        public DbRowOpType visitUpdate(UpdateDbRowOp diffSnapshot) {
            return DbRowOpType.UPDATE;
        }

        @Override
        public DbRowOpType visitDelete(DeleteDbRowOp diffSnapshot) {
            return DbRowOpType.DELETE;
        }
    }
}
