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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.di.Provider;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.EntitySorter;
import org.apache.cayenne.map.ObjEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @since 4.2
 */
public class DefaultDbRowOpSorter implements DbRowOpSorter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDbRowOpSorter.class);

    // the operations of a run that a log message names, so that its size doesn't grow with the run
    private static final int LOGGED_OPERATIONS = 5;

    protected final Provider<DataDomain> dataDomainProvider;
    protected volatile Comparator<DbRowOp> comparator;
    private volatile DbRowOpSorter dependencySorter;
    // the columns the foreign keys reference, by the name of their entity
    private volatile Map<String, Set<String>> referencedColumns;

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
     * On any failure, including a dependency cycle, the run keeps the order it already had. A cycle is logged at
     * DEBUG, as the database accepts the order or reports the constraint it breaks, and any other failure as a
     * warning.
     */
    private void sortWithinComponents(List<DbRowOp> sortedDbRows) {
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
        if(!canHaveRowDependencies(tiedRun)) {
            return;
        }

        // a row-level cycle or an unresolvable dependency must not turn a flush that would otherwise run into an
        // internal failure, so in each case below the run keeps the order the comparator gave it
        try {
            List<DbRowOp> sorted = getDependencySorter().sort(new ArrayList<>(tiedRun));
            if(sorted.size() == tiedRun.size()) {
                Collections.copy(tiedRun, sorted);
            } else if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("The row dependency sort returned {} of {} operations, keeping their order: {}",
                        sorted.size(), tiedRun.size(), describeRun(tiedRun));
            }
        } catch (IllegalStateException ex) {
            // DbRowOpGraph reports a cycle this way. The order kept is the comparator's, and the DB either accepts it,
            // e.g. with deferred constraints, or reports the failing constraint itself
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Operations whose rows depend on each other in a cycle, keeping their order: {}",
                        describeRun(tiedRun));
            }
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to sort operations by row dependency, keeping their order: {}",
                    describeRun(tiedRun), ex);
        }
    }

    /**
     * Describes a run for the log in a bounded size: its entities and its first few operations.
     */
    static String describeRun(List<DbRowOp> run) {
        Set<String> entities = new LinkedHashSet<>();
        for(DbRowOp op : run) {
            entities.add(op.getEntity().getName());
        }

        List<DbRowOp> logged = run.subList(0, Math.min(LOGGED_OPERATIONS, run.size()));
        int notLogged = run.size() - logged.size();
        String rest = notLogged > 0 ? " and " + notLogged + " more" : "";
        return run.size() + " operations on " + entities + ": " + logged + rest;
    }

    /**
     * Whether ordering a run by row dependency can change it. All the operations of a run have the same type. Two
     * UPDATEs depend on each other only when one of them writes a column that a foreign key references, i.e. changes
     * a primary key, so a run of UPDATEs that write no such column has no dependencies, and keeps its order without
     * being handed to the dependency sorter.
     */
    private boolean canHaveRowDependencies(List<DbRowOp> tiedRun) {
        if(!isUpdateRun(tiedRun)) {
            return true;
        }

        Map<String, Set<String>> referencedColumns = getReferencedColumns();
        for(DbRowOp op : tiedRun) {
            if(writesReferencedColumn((UpdateDbRowOp) op, referencedColumns)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUpdateRun(List<DbRowOp> tiedRun) {
        for(DbRowOp op : tiedRun) {
            if(!(op instanceof UpdateDbRowOp)) {
                return false;
            }
        }
        return true;
    }

    private static boolean writesReferencedColumn(UpdateDbRowOp update, Map<String, Set<String>> referencedColumns) {
        Set<String> referencedInEntity = referencedColumns.get(update.getEntity().getName());
        if(referencedInEntity == null) {
            return false;
        }
        for(DbAttribute attribute : update.getValues().getUpdatedAttributes()) {
            if(referencedInEntity.contains(attribute.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the columns the foreign keys reference, by the name of their entity: the target columns of the
     * relationships the dependency sorter follows, i.e. those whose source holds the foreign key.
     */
    private Map<String, Set<String>> getReferencedColumns() {
        Map<String, Set<String>> local = referencedColumns;
        if(local == null) {
            synchronized (this) {
                local = referencedColumns;
                if(local == null) {
                    local = new HashMap<>();
                    for(DbEntity entity : dataDomainProvider.get().getEntityResolver().getDbEntities()) {
                        for(DbRelationship relationship : entity.getRelationships()) {
                            if(relationship.isFromFK()) {
                                addReferencedColumns(local, relationship);
                            }
                        }
                    }
                    referencedColumns = local;
                }
            }
        }
        return local;
    }

    private static void addReferencedColumns(Map<String, Set<String>> referencedColumns, DbRelationship relationship) {
        Set<String> columns = referencedColumns.computeIfAbsent(
                relationship.getTargetEntityName(), name -> new HashSet<>());
        for(DbJoin join : relationship.getJoins()) {
            columns.add(join.getTargetName());
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
                    local = new RowDependencySorter(dataDomainProvider);
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
