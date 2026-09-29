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

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.ObjectDiff;
import org.apache.cayenne.access.flush.operation.DbRowOpType;
import org.apache.cayenne.access.flush.operation.DbRowOpVisitor;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOp;
import org.apache.cayenne.access.flush.operation.InsertDbRowOp;
import org.apache.cayenne.access.flush.operation.UpdateDbRowOp;
import org.apache.cayenne.exp.DbPathExp;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.graph.GraphChangeHandler;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.reflect.AdditionalDbEntityDescriptor;
import org.apache.cayenne.reflect.ArcProperty;
import org.apache.cayenne.reflect.ClassDescriptor;

/**
 * Visitor that runs all required actions based on operation type.
 * <p>
 * E.g. it creates values for insert and update, it fills optimistic lock qualifier for update and delete, etc.
 *
 * @since 4.2
 */
class RootRowOpProcessor implements DbRowOpVisitor<Void> {
    private final DbRowOpFactory dbRowOpFactory;
    private final GraphChangeHandler insertHandler;
    private final GraphChangeHandler updateHandler;
    private final GraphChangeHandler deleteHandler;
    private ObjectDiff diff;

    RootRowOpProcessor(DbRowOpFactory dbRowOpFactory) {
        this.dbRowOpFactory = dbRowOpFactory;
        this.insertHandler = new ValuesCreationHandler(dbRowOpFactory, DbRowOpType.INSERT);
        this.updateHandler = new ValuesCreationHandler(dbRowOpFactory, DbRowOpType.UPDATE);
        this.deleteHandler = new ArcValuesCreationHandler(dbRowOpFactory, DbRowOpType.DELETE);
    }

    void setDiff(ObjectDiff diff) {
        this.diff = diff;
    }

    @Override
    public Void visitInsert(InsertDbRowOp dbRow) {
        diff.apply(insertHandler);
        return null;
    }

    @Override
    public Void visitUpdate(UpdateDbRowOp dbRow) {
        diff.apply(updateHandler);
        if (dbRowOpFactory.getDescriptor().getEntity().getDeclaredLockType() == ObjEntity.LOCK_TYPE_OPTIMISTIC) {
            new OptimisticLockQualifierBuilder(dbRow, diff).build(dbRowOpFactory.getDescriptor());
        }
        return null;
    }

    @Override
    public Void visitDelete(DeleteDbRowOp dbRow) {
        ObjEntity entity = dbRowOpFactory.getDescriptor().getEntity();
        if (entity.isReadOnly()) {
            throw new CayenneRuntimeException("Attempt to modify object(s) mapped to a read-only entity: '%s'. " +
                    "Can't commit changes.", entity.getName());
        }
        diff.apply(deleteHandler);

        ClassDescriptor descriptor = dbRowOpFactory.getDescriptor();
        Map<CayennePath,ObjectId> flattenedPathIdMap = dbRowOpFactory.getFlattenedPathIdMap(dbRow.getChangeId());
        flattenedPathIdMap.forEach((path, id) -> {
            AdditionalDbEntityDescriptor addEntity = descriptor.getAdditionalDbEntities().get(path);
            if (!addEntity.noDelete()) {// See PersistentDescriptorFactory.indexAdditionalDbEntities
                dbRowOpFactory.getOrCreate(addEntity.getDbEntity(), id, DbRowOpType.DELETE);
            }
        });
        deleteJoinRowsWithOwner(dbRow.getChangeId(), flattenedPathIdMap.keySet());
        deleteToManyJoinRowsWithOwner(dbRow.getChangeId());

        if (entity.getDeclaredLockType() == ObjEntity.LOCK_TYPE_OPTIMISTIC) {
            new OptimisticLockQualifierBuilder(dbRow, diff).build(descriptor);
        }
        return null;
    }

    /**
     * Deletes the join rows of the owner's to-ones through a join table. Such a row refers to the owner by a
     * plain foreign key, so it cannot outlive it, whatever the delete rule and whatever this flush did to the
     * arc: {@link ArcValuesCreationHandler} writes no arc change for the join row of a deleted owner.
     * <p>
     * The loop above has already deleted the rows whose key the flush tracks, by that key. The others, whose
     * key a fetch never reads, are deleted here by their foreign key to the owner. Either way the path stops
     * naming the row.
     */
    private void deleteJoinRowsWithOwner(ObjectId ownerId, Set<CayennePath> trackedPaths) {
        ClassDescriptor descriptor = dbRowOpFactory.getDescriptor();
        // a join row that two of the owner's to-ones pass through is still deleted once
        Set<CayennePath> deletedPaths = new HashSet<>(trackedPaths);

        for (ObjRelationship relationship : descriptor.getEntity().getRelationships()) {
            List<DbRelationship> dbPath = relationship.getDbRelationships();
            if (ArcValuesCreationHandler.isJoinRowDeletedWithOwner(descriptor, dbPath)) {
                CayennePath path = ArcValuesCreationHandler.joinRowPath(dbPath);
                boolean alreadyDeleted = deletedPaths.contains(path);
                if (!alreadyDeleted) {
                    deleteByForeignKeyToOwner(dbPath.getFirst(), ownerId);
                    deletedPaths.add(path);
                }
                dbRowOpFactory.forgetFlattenedPath(ownerId, path);
            }
        }
    }

    /**
     * Deletes the join rows of the owner's to-manys through a join table, by their foreign key to the
     * owner, once per join table. Like a to-one's join row, such a row cannot outlive the owner. The delete
     * rules remove it only as an arc the context knows of, and with no rule, or a CASCADE whose targets
     * leave their arc, nothing does. Deleting by the foreign key needs no fetch of the rows.
     * <p>
     * A to-many the context has read and holds empty is left alone: the removal of each arc it held, by a
     * NULLIFY rule or by the application, already deletes that arc's row.
     */
    private void deleteToManyJoinRowsWithOwner(ObjectId ownerId) {
        // two to-manys that enter the same join table by the same foreign key delete its rows once
        Set<DbRelationship> hopsToJoinRows = new LinkedHashSet<>();
        for (ObjRelationship relationship : dbRowOpFactory.getDescriptor().getEntity().getRelationships()) {
            List<DbRelationship> dbPath = relationship.getDbRelationships();
            if (ArcValuesCreationHandler.isJoinTableToMany(dbPath) && mayStillHaveJoinRows(relationship)) {
                hopsToJoinRows.add(dbPath.getFirst());
            }
        }
        for (DbRelationship toJoinRows : hopsToJoinRows) {
            deleteByForeignKeyToOwner(toJoinRows, ownerId);
        }
    }

    /**
     * Whether the owner may still have rows in the to-many's join table, which is so unless the context has
     * read the to-many and holds it empty.
     */
    private boolean mayStillHaveJoinRows(ObjRelationship toMany) {
        ArcProperty property = (ArcProperty) dbRowOpFactory.getDescriptor().getProperty(toMany.getName());
        Object targets = property.readPropertyDirectly(dbRowOpFactory.getObject());
        boolean readAndEmpty = !property.isFaultValue(targets) && isEmptyCollection(targets);
        return !readAndEmpty;
    }

    private static boolean isEmptyCollection(Object targets) {
        return switch (targets) {
            case Collection<?> collection -> collection.isEmpty();
            case Map<?, ?> map -> map.isEmpty();
            case null, default -> false;
        };
    }

    private void deleteByForeignKeyToOwner(DbRelationship toJoinRow, ObjectId ownerId) {
        DbEntity joinTable = toJoinRow.getTargetEntity();
        // a temporary id that never gets a key: the foreign-key qualifier, not the id, finds the row
        ObjectId keylessId = ObjectId.of(DbPathExp.DB_PREFIX + joinTable.getName());
        DeleteDbRowOp delete = dbRowOpFactory.getOrCreate(joinTable, keylessId, DbRowOpType.DELETE);
        ArcValuesCreationHandler.qualifyByForeignKeyToSource(delete, toJoinRow, ownerId);
    }
}
