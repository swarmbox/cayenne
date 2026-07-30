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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import org.apache.cayenne.exp.DbPathExp;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.graph.ArcId;
import org.apache.cayenne.graph.GraphChangeHandler;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.reflect.AdditionalDbEntityDescriptor;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.util.CayenneMapEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graph handler that collects information about arc changes into
 * {@link org.apache.cayenne.access.flush.operation.Values} and/or {@link org.apache.cayenne.access.flush.operation.Qualifier}.
 *
 * @since 4.2
 */
class ArcValuesCreationHandler implements GraphChangeHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArcValuesCreationHandler.class);

    final DbRowOpFactory factory;
    final DbRowOpType defaultType;

    ArcValuesCreationHandler(DbRowOpFactory factory, DbRowOpType defaultType) {
        this.factory = factory;
        this.defaultType = defaultType;
    }

    public void arcCreated(ObjectId id, ObjectId targetId, ArcId arcId) {
        processArcChange(id, targetId, arcId, true);
    }

    public void arcDeleted(ObjectId id, ObjectId targetId, ArcId arcId) {
        processArcChange(id, targetId, arcId, false);
    }

    private void processArcChange(ObjectId id, ObjectId targetId, ArcId arcId, boolean created) {
        ObjectId actualTargetId = (ObjectId)targetId;
        if(created) {
            // A deletion keeps the endpoint it names: the row it unhooks is the one the arc pointed at before,
            // not the one it points at now. Reporting a creation at the arc's current value is what collapses a
            // to-one arc set more than once in one flush onto the target it ends at.
            ObjectId snapshotId = factory.getDiff().getCurrentArcSnapshotValue(arcId.getForwardArc());
            if(snapshotId != null) {
                actualTargetId = snapshotId;
            }
        }
        ArcTarget arcTarget = new ArcTarget(id, actualTargetId, arcId, !created);
        if(factory.getProcessedArcs().contains(arcTarget.getReversed())) {
            return;
        }

        ObjEntity entity = factory.getDescriptor().getEntity();
        ObjRelationship objRelationship = entity.getRelationship(arcTarget.getArcId().getForwardArc());
        if(objRelationship == null) {
            String arc = arcId.getForwardArc();
            if(arc.startsWith(DbPathExp.DB_PREFIX)) {
                String relName = arc.substring(DbPathExp.DB_PREFIX.length());
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
            if(entry instanceof DbRelationship relationship) {
                boolean isFinalEntry = i == entries.size() - 1;
                // intermediate db entity to be inserted
                DbEntity target = relationship.getTargetEntity();
                // Kept apart from targetId: the operation type below turns on the id having been derived.
                ObjectId derivedTargetId = null;
                // if ID is present, just use it, otherwise create new
                // if this is the last segment, and it's a relationship, use known target id from arc creation
                if(isFinalEntry) {
                    targetId = finalTargetId;
                } else {
                    // Derivation takes precedence over the tracked id (CAY-2890). Where the remaining path is a
                    // chain of full shared-PK joins, the intermediate row and the arc's final target are the same
                    // logical row split across tables, so the intermediate's key IS the target's key and is not
                    // free to be anything else. A tracked id that disagrees is therefore stale rather than an
                    // alternative: ObjectResolver.resolveAdditionalIds records every intermediate hop at fetch
                    // time from the foreign key the row held then, while this arc is being reassigned now.
                    // Deriving also makes both directions of the arc name the same row, so DbRowOpMerger performs
                    // a value merge instead of choosing an operation type by whichever side it happened to see
                    // first, which would otherwise make the outcome depend on which diff was walked first.
                    derivedTargetId = deriveIdFromFinalTarget(target, finalTargetId,
                            entries.subList(i + 1, entries.size()));
                    targetId = derivedTargetId;
                    if(targetId == null && !relationship.isToMany()) {
                        targetId = factory.getFlattenedId(id, flattenedPath);
                    }
                }

                if(targetId == null) {
                    // should insert, regardless of original operation (insert/update)
                    targetId = ObjectId.of(DbPathExp.DB_PREFIX + target.getName());
                    if(!relationship.isToMany()) {
                        factory.markFlattenedPath(id, flattenedPath, targetId);
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
                        // Only a derived id needs asking; a tracked id was observed in the database. The
                        // flattened-attribute route passes a null finalTargetId and so never derives.
                        if(derivedTargetId != null && derivedRowPendingInsert(finalTargetId, entries, i)
                                && declaredStorageOfTarget(finalTargetId, entries, i, target)) {
                            type = DbRowOpType.INSERT;
                            markDerivedRowOnOwner(finalTargetId, entries, i, derivedTargetId);
                        } else {
                            type = DbRowOpType.UPDATE;
                        }
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
     * database. Answered by consulting the {@link org.apache.cayenne.ObjectStore}:
     * any registered object that is not in the {@code NEW} state has been (or
     * was) persisted. The {@code ObjectStore} is authoritative for this
     * question -- unlike the data row cache, it is not evictable.
     */
    private boolean targetRowExists(ObjectId targetId) {
        Persistent target = factory.getStore().getObject(targetId);
        return target != null && target.getPersistenceState() != PersistenceState.NEW;
    }

    /**
     * Returns whether a derived intermediate row is one this flush has still to insert: the remaining path is a
     * shared-PK chain and the arc target is positively {@code NEW}.
     * <p>
     * Absence from the {@link org.apache.cayenne.ObjectStore} is deliberately not read as "not there" --
     * the default object map holds values weakly and a deleted object is unregistered, so absence would insert
     * over a row that exists.
     */
    private boolean derivedRowPendingInsert(ObjectId finalTargetId, List<CayenneMapEntry> entries, int i) {
        if(!sharedPrimaryKeyChainAhead(entries, i)) {
            return false;
        }
        Persistent target = factory.getStore().getObject(finalTargetId);
        return target != null && target.getPersistenceState() == PersistenceState.NEW;
    }

    /**
     * Records the row this flush is about to insert against the object that owns it, so a later flush in the same
     * context does not insert it again: the owner was created here rather than fetched, so
     * {@code ObjectResolver.resolveAdditionalIds} never recorded the route and a later flattened write to it would
     * fail on a duplicate key. Marking goes through the factory.
     */
    private void markDerivedRowOnOwner(ObjectId ownerId, List<CayenneMapEntry> entries, int i, ObjectId derivedId) {
        CayennePath ownerPath = ownerRelativePath(entries, i);
        if(ownerPath != null) {
            factory.markFlattenedPath(ownerId, ownerPath, derivedId);
        }
    }

    /**
     * Condition 4 of the flip: whether the intermediate row is one of the arc target's <em>own declared storage
     * tables</em>, rather than another entity's row that merely shares its key value.
     * <p>
     * A refusal degrades to the {@code UPDATE} branch and so can never fabricate a row, but on a genuine
     * vertical-inheritance sub-table that update matches nothing -- hence the warning. No test mapping produces
     * that shape, so it is expected to fire only on a downstream mapping the survey did not anticipate.
     */
    private boolean declaredStorageOfTarget(ObjectId finalTargetId, List<CayenneMapEntry> entries, int i,
                                            DbEntity intermediate) {
        CayennePath ownerPath = ownerRelativePath(entries, i);
        if(ownerPath != null && intermediateRowIsDeclaredStorage(finalTargetId, ownerPath, intermediate)) {
            return true;
        }
        LOGGER.warn("Refusing to insert a derived intermediate row that the target entity does not declare as "
                        + "its own storage; falling back to an update that may match no rows. "
                        + "owner={}, ownerRelativePath={}, intermediateEntity={}",
                finalTargetId, ownerPath, intermediate.getName());
        return false;
    }

    /**
     * Returns whether {@code ownerPath} resolves in the owner's {@link ClassDescriptor#getAdditionalDbEntities()}
     * to an entry naming {@code intermediate}.
     * <p>
     * Identification is <strong>by route</strong>, the {@link DbEntity} only a consistency assertion on the
     * resolved entry: a single descriptor can map the same table at two routes, one of them a <em>different</em>
     * object's child row reached over a non-PK foreign key -- an entity mapping one table both as its own
     * vertical-inheritance child row and as a to-one reaching another instance's. The entry's cascade-delete
     * flag is no anchor either -- it
     * encodes delete rules and flips value for the same table between descriptors.
     * <p>
     * The map is a mapping-level fact rather than a per-instance one, so an arc holder that sets no flattened
     * attribute still has its entry.
     */
    boolean intermediateRowIsDeclaredStorage(ObjectId ownerId, CayennePath ownerPath, DbEntity intermediate) {
        ClassDescriptor ownerDescriptor = factory.getResolver().getClassDescriptor(ownerId.getEntityName());
        if(ownerDescriptor == null) {
            return false;
        }
        AdditionalDbEntityDescriptor declared = ownerDescriptor.getAdditionalDbEntities().get(ownerPath);
        return declared != null && declared.getDbEntity() == intermediate;
    }

    /**
     * Returns the path reaching the intermediate row from the <em>owner's</em> own entity, which is the key the
     * owner's later flattened writes look it up under. This walk arrives from the other direction, so each
     * remaining hop is taken backwards and named by its reverse: a remainder running up the hierarchy is stored
     * as the corresponding path down it. Null when any hop has no reverse mapped.
     * <p>
     * Only called once {@link #sharedPrimaryKeyChainAhead} holds, so every remaining entry is a relationship.
     */
    private static CayennePath ownerRelativePath(List<CayenneMapEntry> entries, int i) {
        CayennePath path = CayennePath.EMPTY_PATH;
        for(int j = entries.size() - 1; j > i; j--) {
            DbRelationship reverse = ((DbRelationship) entries.get(j)).getReverseRelationship();
            if(reverse == null) {
                return null;
            }
            path = path.dot(reverse.getName());
        }
        return path;
    }

    /**
     * Returns whether every remaining segment is a shared primary key link toward the master -- the condition
     * under which the intermediate row and the arc's final target are one logical row split across tables, and
     * so the condition under which the target's persistence state answers whether the intermediate row exists.
     * <p>
     * Deliberately stricter than what {@link #resolvePkMapping} needs to derive an id: derivation also accepts a
     * <em>forward</em> dependent primary key hop, where the key is fully determined but the two rows are distinct
     * entities, so the target being {@code NEW} would say nothing about the intermediate.
     *
     * @see #hasSharedPrimaryKeyNext for the single-hop form used to classify the next segment
     */
    private static boolean sharedPrimaryKeyChainAhead(List<CayenneMapEntry> entries, int i) {
        if(i + 1 >= entries.size()) {
            return false;
        }
        for(int j = i + 1; j < entries.size(); j++) {
            if(!(entries.get(j) instanceof DbRelationship next)
                    || !next.isSharedPrimaryKey() || !next.isToMasterPK()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether the next path segment is a shared-PK link traversed from the dependent side toward the
     * master -- vertical-inheritance child-to-parent, one logical row across two tables, so the intermediate row
     * at step {@code i} is an update target rather than a row to insert or delete. Excludes M:N junctions, whose
     * composite PK is only partly joined, and the dependent direction, which still requires writing the child
     * row itself.
     */
    private static boolean hasSharedPrimaryKeyNext(List<CayenneMapEntry> entries, int i) {
        if (i + 1 >= entries.size()) {
            return false;
        }
        if (!(entries.get(i + 1) instanceof DbRelationship next)) {
            return false;
        }
        return next.isSharedPrimaryKey() && next.isToMasterPK();
    }

    /**
     * Derives the id of an intermediate row from the arc's final target, the rows being joined on their full
     * shared primary key along every remaining segment of the path. Returns null when the key cannot be read
     * off the target, leaving the caller to fall back to a tracked id or to invent a temporary one.
     * <p>
     * The derived id is deliberately not recorded via {@code markFlattenedPath}: recomputing it is cheap, and
     * recording it would add a second source of the stale-tracked-id problem that this derivation exists to
     * defeat.
     *
     * @see #resolvePkMapping
     */
    private static ObjectId deriveIdFromFinalTarget(DbEntity target, ObjectId finalTargetId,
                                                    List<CayenneMapEntry> remainingPath) {
        // Derivation needs the target's real key, which a NEW target does not have yet: getIdSnapshot() on a
        // temporary returns its replacement map, and that map is empty until the insert attaches one. Deriving
        // from it would key the intermediate on nothing, yielding a second operation for a row the target's own
        // diff is already inserting. The reason is the absent key rather than an identity rule:
        // ReplacementAwareObjectId.equals compares entity name and replacement map and never consults
        // isTemporary(), so a temporary and a permanent id do compare equal once both carry the same attached
        // replacement -- which is what processRelationship's propagated-PK branch arranges for the base hop.
        if (finalTargetId == null || finalTargetId.isTemporary()) {
            return null;
        }
        Map<String, Object> finalIdSnapshot = finalTargetId.getIdSnapshot();
        if (finalIdSnapshot == null) {
            return null;
        }
        Map<String, String> targetToFinalPk = resolvePkMapping(target, remainingPath);
        if (targetToFinalPk.isEmpty()) {
            return null;
        }

        Map<String, Object> idSnapshot = new HashMap<>(targetToFinalPk.size());
        for (Map.Entry<String, String> pkPair : targetToFinalPk.entrySet()) {
            Object value = finalIdSnapshot.get(pkPair.getValue());
            if (value == null) {
                return null;
            }
            idSnapshot.put(pkPair.getKey(), value);
        }
        return ObjectId.of(DbPathExp.DB_PREFIX + target.getName(), idSnapshot);
    }

    /**
     * Maps each primary key column of an intermediate entity to the column of the path's final entity that
     * carries the same value, walking every remaining segment rather than only the next hop: stopping early
     * would copy some other row's key, silently so whenever the tables name their primary keys alike.
     * <p>
     * A hop qualifies when it is to-one, is a dependent primary key link in either direction, and joins primary
     * key to primary key -- deliberately weaker than {@link DbRelationship#isSharedPrimaryKey()}, which also
     * requires the joins to span the full primary key of <em>both</em> entities. That strictness answers whether
     * two rows are one logical entity split across tables; here the question is only whether the intermediate's
     * key is fully determined, so key columns the far side has beyond the join do not bear on it. The weaker
     * form still excludes many-to-many junctions, since only part of a junction's composite key is joined.
     */
    private static Map<String, String> resolvePkMapping(DbEntity target, List<CayenneMapEntry> remainingPath) {
        Map<String, String> targetToCurrentPk = new HashMap<>();
        for (DbAttribute pk : target.getPrimaryKeys()) {
            targetToCurrentPk.put(pk.getName(), pk.getName());
        }
        if (targetToCurrentPk.isEmpty()) {
            return Map.of();
        }

        for (CayenneMapEntry pathComponent : remainingPath) {
            if (!(pathComponent instanceof DbRelationship relationship) || relationship.isToMany()) {
                return Map.of();
            }
            DbRelationship reverse = relationship.getReverseRelationship();
            if (!relationship.isToDependentPK() && (reverse == null || !reverse.isToDependentPK())) {
                return Map.of();
            }

            Map<String, String> nextMapping = new HashMap<>(targetToCurrentPk.size());
            for (DbJoin join : relationship.getJoins()) {
                if (join.getSource() == null || !join.getSource().isPrimaryKey()
                        || join.getTarget() == null || !join.getTarget().isPrimaryKey()) {
                    return Map.of();
                }
                for (Map.Entry<String, String> pkPair : targetToCurrentPk.entrySet()) {
                    if (pkPair.getValue().equals(join.getSourceName())) {
                        nextMapping.put(pkPair.getKey(), join.getTargetName());
                    }
                }
            }
            // every key column of the intermediate must still be accounted for; a junction table's composite
            // PK is only partly joined, so it drops out here rather than needing a separate exclusion
            if (nextMapping.size() != targetToCurrentPk.size()) {
                return Map.of();
            }
            targetToCurrentPk = nextMapping;
        }
        return targetToCurrentPk;
    }

    private boolean shouldSkipFlattenedOp(ObjectId id, ObjectId finalTargetId) {
        // as we get two sides of the relationship processed,
        // check if we got more information for a reverse operation
        return finalTargetId != null
                && factory.getFlattenedIds(id).isEmpty()
                && !factory.getFlattenedIds(finalTargetId).isEmpty();
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

            // propagated master -> child PK; only an arc creation defines the new id value, an arc deletion
            // must not stomp it with the old target's value
            if(add && id != null && attribute.isPrimaryKey()) {
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
