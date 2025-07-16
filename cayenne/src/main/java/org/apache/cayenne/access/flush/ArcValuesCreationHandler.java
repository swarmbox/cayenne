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

package org.apache.cayenne.access.flush;

import java.util.ArrayList;
import java.util.List;

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpType;
import org.apache.cayenne.access.flush.operation.DbRowOpVisitor;
import org.apache.cayenne.access.flush.operation.DbRowOpWithValues;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOp;
import org.apache.cayenne.access.flush.operation.InsertDbRowOp;
import org.apache.cayenne.access.flush.operation.UpdateDbRowOp;
import org.apache.cayenne.exp.parser.ASTDbPath;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.graph.ArcId;
import org.apache.cayenne.graph.GraphChangeHandler;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.util.CayenneMapEntry;

/**
 * Graph handler that collects information about arc changes into
 * {@link org.apache.cayenne.access.flush.operation.Values} and/or {@link org.apache.cayenne.access.flush.operation.Qualifier}.
 *
 * @since 4.2
 */
class ArcValuesCreationHandler implements GraphChangeHandler {

    final DbRowOpFactory factory;
    final DbRowOpType defaultType;

    ArcValuesCreationHandler(DbRowOpFactory factory, DbRowOpType defaultType) {
        this.factory = factory;
        this.defaultType = defaultType;
    }

    public void arcCreated(Object nodeId, Object targetNodeId, ArcId arcId) {
        processArcChange(nodeId, targetNodeId, arcId, true);
    }

    public void arcDeleted(Object nodeId, Object targetNodeId, ArcId arcId) {
        processArcChange(nodeId, targetNodeId, arcId, false);
    }

    private void processArcChange(Object nodeId, Object targetNodeId, ArcId arcId, boolean created) {
        ObjectId actualTargetId = (ObjectId)targetNodeId;
        ObjectId snapshotId = factory.getDiff().getCurrentArcSnapshotValue(arcId.getForwardArc());
        if(snapshotId != null) {
            actualTargetId = snapshotId;
        }
        ArcTarget arcTarget = new ArcTarget((ObjectId) nodeId, actualTargetId, arcId, !created);
        if(factory.getProcessedArcs().contains(arcTarget.getReversed())) {
            return;
        }

        ObjEntity entity = factory.getDescriptor().getEntity();
        ObjRelationship objRelationship = entity.getRelationship(arcTarget.getArcId().getForwardArc());
        if(objRelationship == null) {
            String arc = arcId.getForwardArc();
            if(arc.startsWith(ASTDbPath.DB_PREFIX)) {
                String relName = arc.substring(ASTDbPath.DB_PREFIX.length());
                DbRelationship dbRelationship = entity.getDbEntity().getRelationship(relName);
                processRelationship(dbRelationship, arcTarget.getSourceId(), arcTarget.getTargetId(), created);
            }
            return;
        }

        if(objRelationship.isFlattened()) {
            FlattenedPathProcessingResult result = processFlattenedPath(arcTarget.getSourceId(), arcTarget.getTargetId(), entity.getDbEntity(),
                    objRelationship.getDbRelationshipPath(), created);
            if(result.isProcessed()) {
                factory.getProcessedArcs().add(arcTarget);
            }
        } else {
            DbRelationship dbRelationship = objRelationship.getDbRelationships().get(0);
            processRelationship(dbRelationship, arcTarget.getSourceId(), arcTarget.getTargetId(), created);
            factory.getProcessedArcs().add(arcTarget);
        }
    }

    FlattenedPathProcessingResult processFlattenedPath(ObjectId id, ObjectId finalTargetId, DbEntity entity, CayennePath dbPath, boolean add) {
        if(shouldSkipFlattenedOp(id, finalTargetId)) {
            return flattenedResultNotProcessed();
        }

        CayennePath flattenedPath = CayennePath.EMPTY_PATH;

        ObjectId srcId = id;
        ObjectId targetId = null;

        List<CayenneMapEntry> entries = new ArrayList<>();
        entity.resolvePathComponents(dbPath).forEachRemaining(entries::add);

        for(int i = 0; i < entries.size(); i++) {
            CayenneMapEntry entry = entries.get(i);
            flattenedPath = flattenedPath.dot(entry.getName());
            if(entry instanceof DbRelationship) {
                boolean isFinalEntry = i == entries.size() - 1;
                DbRelationship relationship = (DbRelationship)entry;
                // intermediate db entity to be inserted
                DbEntity target = relationship.getTargetEntity();
                // if ID is present, just use it, otherwise create new
                // if this is the last segment, and it's a relationship, use known target id from arc creation
                if(isFinalEntry) {
                    targetId = finalTargetId;
                } else {
                    if(!relationship.isToMany()) {
                        targetId = factory.getStore().getFlattenedId(id, flattenedPath);
                    } else {
                        targetId = null;
                    }
                }

                if(targetId == null) {
                    // should insert, regardless of original operation (insert/update)
                    targetId = ObjectId.of(ASTDbPath.DB_PREFIX + target.getName());
                    if(!relationship.isToMany()) {
                        factory.getStore().markFlattenedPath(id, flattenedPath, targetId);
                    }

                    DbRowOpType type;
                    if(relationship.isToMany()) {
                        if(add) {
                            type = DbRowOpType.INSERT;
                        } else if(hasSharedPrimaryKeyNext(entries, i)) {
                            // Vertical-inheritance case (CAY-2890): the next segment is a shared-PK
                            // link, so the intermediate and its next target are the same logical row
                            // split across two tables. Removing the arc is an UPDATE to the
                            // intermediate's FK rather than a DELETE of the row. M:N junctions (whose
                            // next segment joins only part of a composite PK) still take DELETE.
                            type = DbRowOpType.UPDATE;
                        } else {
                            type = DbRowOpType.DELETE;
                        }
                        factory.getOrCreate(target, targetId, type);
                    } else {
                        if (
                            add &&
                            hasSharedPrimaryKeyNext(entries, i) &&
                            targetRowExists(finalTargetId)
                        ) {
                            // Vertical-inheritance case (CAY-2890): when the next segment is a
                            // shared-PK link back to the parent, the intermediate row and the parent
                            // row are the same logical entity split across two tables. If the target
                            // object is already persisted (not NEW), its child row exists too, so
                            // the arc change is an UPDATE to that child's FK rather than an INSERT
                            // of a new child row.
                            type = DbRowOpType.UPDATE;
                        } else if (add) {
                            type = DbRowOpType.INSERT;
                        } else {
                            type = DbRowOpType.UPDATE;
                        }
                        factory.<DbRowOpWithValues>getOrCreate(target, targetId, type)
                                .getValues()
                                .addFlattenedId(flattenedPath, targetId);
                    }
                } else if(!isFinalEntry) {
                    DbRowOpType type;
                    if(add) {
                        type = DbRowOpType.UPDATE;
                    } else if(defaultType == DbRowOpType.DELETE && hasSharedPrimaryKeyNext(entries, i)) {
                        // Vertical-inheritance case (CAY-2890): an existing intermediate row whose
                        // next segment is a shared-PK link should be UPDATEd, not deleted -- the
                        // row represents a real entity. Junction tables (non-shared-PK next
                        // segment) fall through to the default DELETE.
                        type = DbRowOpType.UPDATE;
                    } else {
                        type = defaultType;
                    }
                    // should update existing DB row
                    factory.getOrCreate(target, targetId, type);
                }
                processRelationship(relationship, srcId, targetId, shouldProcessAsAddition(relationship, add));
                srcId = targetId; // use target as next source
            }
        }

        return flattenedResultId(targetId);
    }

    /**
     * Returns whether the row for {@code targetId} is already present in the
     * database. Answered by consulting the {@link org.apache.cayenne.access.ObjectStore}:
     * any registered object that is not in the {@code NEW} state has been (or
     * was) persisted. The {@code ObjectStore} is authoritative for this
     * question -- unlike the data row cache, it is not evictable.
     */
    private boolean targetRowExists(ObjectId targetId) {
        Persistent target = (Persistent) factory.getStore().getNode(targetId);
        return target != null && target.getPersistenceState() != PersistenceState.NEW;
    }

    /**
     * Returns whether the next path segment is a shared-PK link traversed from
     * the dependent side toward the master -- i.e. vertical-inheritance
     * child-to-parent. Such a link represents a single logical row spread
     * across two tables, so the intermediate row at step {@code i} is an update
     * target rather than a row to insert or delete.
     * <p>
     * Two conditions must both hold:
     * <ul>
     *     <li>{@link DbRelationship#isSharedPrimaryKey()} -- all joined columns
     *     are the full PK of both sides, which excludes M:N junction tables
     *     where only part of the composite PK participates;</li>
     *     <li>{@link DbRelationship#isToMasterPK()} -- direction is toward
     *     the master, not the dependent. The dependent direction
     *     ({@code toDependentPK}) still requires an insert/update of the child
     *     row itself.</li>
     * </ul>
     */
    private static boolean hasSharedPrimaryKeyNext(List<CayenneMapEntry> entries, int i) {
        if (i + 1 >= entries.size()) {
            return false;
        }
        CayenneMapEntry nextEntry = entries.get(i + 1);
        if (!(nextEntry instanceof DbRelationship)) {
            return false;
        }
        DbRelationship next = (DbRelationship) nextEntry;
        return isSharedPrimaryKey(next) && next.isToMasterPK();
    }

    /**
     * Returns whether the relationship is a full primary key identity join,
     * meaning it joins on all primary key columns of both entities. This
     * intentionally excludes partial PK joins such as join tables with
     * composite primary keys (e.g. many-to-many association tables where each
     * FK column is part of the composite PK).
     */
    private static boolean isSharedPrimaryKey(DbRelationship relationship) {
        List<DbJoin> joins = relationship.getJoins();
        if (joins.isEmpty()) {
            return false;
        }
        for (DbJoin join : joins) {
            DbAttribute source = join.getSource();
            DbAttribute target = join.getTarget();
            if (source == null || target == null
                    || !source.isPrimaryKey() || !target.isPrimaryKey()) {
                return false;
            }
        }
        int joinCount = joins.size();
        return joinCount == relationship.getSourceEntity().getPrimaryKeys().size()
                && joinCount == relationship.getTargetEntity().getPrimaryKeys().size();
    }

    private boolean shouldSkipFlattenedOp(ObjectId id, ObjectId finalTargetId) {
        // as we get two sides of the relationship processed,
        // check if we got more information for a reverse operation
        return finalTargetId != null
                && factory.getStore().getFlattenedIds(id).isEmpty()
                && !factory.getStore().getFlattenedIds(finalTargetId).isEmpty();
    }

    private boolean shouldProcessAsAddition(DbRelationship relationship, boolean add) {
        if(add) {
            return true;
        }

        // should always add data from one-to-one relationships
        for(DbJoin join : relationship.getJoins()) {
            if(!join.getSource().isPrimaryKey() || !join.getTarget().isPrimaryKey()) {
                return false;
            }
        }
        return true;
    }

    protected void processRelationship(DbRelationship dbRelationship, ObjectId srcId, ObjectId targetId, boolean add) {
        for(DbJoin join : dbRelationship.getJoins()) {
            boolean srcPK = join.getSource().isPrimaryKey();
            boolean targetPK = join.getTarget().isPrimaryKey();

            Object valueToUse;
            DbRowOp rowOp;
            DbAttribute attribute;
            ObjectId id;
            boolean processDelete;

            // We manage 3 cases here:
            // 1. PK -> FK: just propagate value from PK and to FK
            // 2. PK -> PK: check isToDep flag and set dependent one
            // 3. NON-PK -> FK (not supported fully for now, see CAY-2488): also check isToDep flag,
            //    but get value from DbRow, not ObjID
            if(srcPK != targetPK) {
                // case 1
                processDelete = true;
                id = null;
                if(srcPK) {
                    valueToUse = ObjectIdValueSupplier.getFor(srcId, join.getSourceName());
                    rowOp = factory.getOrCreate(dbRelationship.getTargetEntity(), targetId, DbRowOpType.UPDATE);
                    attribute = join.getTarget();
                } else {
                    valueToUse = ObjectIdValueSupplier.getFor(targetId, join.getTargetName());
                    rowOp = factory.getOrCreate(dbRelationship.getSourceEntity(), srcId, defaultType);
                    attribute = join.getSource();
                }
            } else {
                // case 2 and 3
                processDelete = false;
                if(dbRelationship.isToDependentPK()) {
                    valueToUse = ObjectIdValueSupplier.getFor(srcId, join.getSourceName());
                    rowOp = factory.getOrCreate(dbRelationship.getTargetEntity(), targetId, DbRowOpType.UPDATE);
                    attribute = join.getTarget();
                    id = targetId;
                    if(dbRelationship.isToMany()) {
                        // strange mapping toDepPK and toMany, but just skip it
                        rowOp = null;
                    }
                } else {
                    valueToUse = ObjectIdValueSupplier.getFor(targetId, join.getTargetName());
                    rowOp = factory.getOrCreate(dbRelationship.getSourceEntity(), srcId, defaultType);
                    attribute = join.getSource();
                    id = srcId;
                    if(dbRelationship.getReverseRelationship().isToMany()) {
                        // strange mapping toDepPK and toMany, but just skip it
                        rowOp = null;
                    }
                }
            }

            // propagated master -> child PK
            if(id != null && attribute.isPrimaryKey()) {
                id.getReplacementIdMap().put(attribute.getName(), valueToUse);
            }
            if(rowOp != null) {
                rowOp.accept(new ValuePropagationVisitor(attribute, add, valueToUse, processDelete));
            }
        }
    }

    private static class ValuePropagationVisitor implements DbRowOpVisitor<Void> {
        private final DbAttribute attribute;
        private final boolean add;
        private final Object valueToUse;
        private final boolean processDelete;

        private ValuePropagationVisitor(DbAttribute attribute, boolean add, Object valueToUse, boolean processDelete) {
            this.attribute = attribute;
            this.add = add;
            this.valueToUse = valueToUse;
            this.processDelete = processDelete;
        }

        @Override
        public Void visitInsert(InsertDbRowOp dbRow) {
            dbRow.getValues().addValue(attribute, add ? valueToUse : null, true);
            return null;
        }

        @Override
        public Void visitUpdate(UpdateDbRowOp dbRow) {
            dbRow.getValues().addValue(attribute, add ? valueToUse : null, true);
            return null;
        }

        @Override
        public Void visitDelete(DeleteDbRowOp dbRow) {
            if(processDelete) {
                dbRow.getQualifier().addAdditionalQualifier(attribute, valueToUse);
            }
            return null;
        }
    }

    static FlattenedPathProcessingResult flattenedResultId(ObjectId id) {
        return new FlattenedPathProcessingResult(true, id);
    }

    static FlattenedPathProcessingResult flattenedResultNotProcessed() {
        return new FlattenedPathProcessingResult(false, null);
    }

    final static class FlattenedPathProcessingResult {
        private final boolean processed;
        private final ObjectId id;

        private FlattenedPathProcessingResult(boolean processed, ObjectId id) {
            this.processed = processed;
            this.id = id;
        }

        public boolean isProcessed() {
            return processed;
        }

        public ObjectId getId() {
            return id;
        }
    }
}
