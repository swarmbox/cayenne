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

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.access.DataContextObjectStore;
import org.apache.cayenne.access.ObjectDiff;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpType;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOpFactory;
import org.apache.cayenne.access.flush.operation.InsertDbRowOp;
import org.apache.cayenne.access.flush.operation.UpdateDbRowOp;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.reflect.ClassDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Factory that produces a collection of {@link DbRowOp} from given {@link ObjectDiff}.
 *
 * @since 4.2
 */
class DbRowOpFactory implements DbRowOpCreator {

    private final EntityResolver resolver;
    private final DataContextObjectStore store;
    private final Set<ArcTarget> processedArcs;
    private final Map<ObjectId, DbRowOp> dbRows;
    private final Map<ObjectId, Map<CayennePath, ObjectId>> flattenedPaths;
    private final RootRowOpProcessor rootRowOpProcessor;
    private final DeleteDbRowOpFactory deleteDbRowOpFactory;

    private ClassDescriptor descriptor;
    private Persistent object;
    private ObjectDiff diff;

    DbRowOpFactory(EntityResolver resolver,
                   DataContextObjectStore store,
                   Set<ArcTarget> processedArcs,
                   DeleteDbRowOpFactory deleteDbRowOpFactory) {

        this.resolver = resolver;
        this.store = store;
        this.dbRows = new HashMap<>(4);
        this.flattenedPaths = new HashMap<>(4);
        this.processedArcs = processedArcs;
        this.rootRowOpProcessor = new RootRowOpProcessor(this);
        this.deleteDbRowOpFactory = deleteDbRowOpFactory;
    }

    private void updateDiff(ObjectDiff diff) {
        ObjectId id = diff.getNodeId();
        this.diff = diff;
        this.descriptor = resolver.getClassDescriptor(id.getEntityName());
        this.object = store.getObject(id);
        this.dbRows.clear();
    }

    @Override
    public Collection<? extends DbRowOp> createRows(ObjectDiff diff) {
        updateDiff(diff);
        DbEntity rootEntity = descriptor.getEntity().getDbEntity();
        DbRowOp row = getOrCreate(rootEntity, object.getObjectId(), DbRowOpType.forObject(object));
        rootRowOpProcessor.setDiff(diff);
        row.accept(rootRowOpProcessor);
        return dbRows.values();
    }

    @SuppressWarnings("unchecked")
    <E extends DbRowOp> E get(ObjectId id) {
        return Objects.requireNonNull((E) dbRows.get(id));
    }

    @SuppressWarnings("unchecked")
    <E extends DbRowOp> E getOrCreate(DbEntity entity, ObjectId id, DbRowOpType type) {
        return (E) dbRows.computeIfAbsent(id, nextId -> createRow(entity, id, type));
    }

    private DbRowOp createRow(DbEntity entity, ObjectId id, DbRowOpType type) {
        // skip phantom nodes, this could be a created and immediately deleted relationship
        if (store.getObject(id) == null && !id.getEntityName().startsWith("db:")) {
            return null;
        }
        return switch (type) {
            case INSERT -> new InsertDbRowOp(object, entity, id);
            case UPDATE -> new UpdateDbRowOp(object, entity, id);
            case DELETE -> deleteDbRowOpFactory.createOp(object, entity, id);
        };
    }

    ClassDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * The resolver, for looking up a descriptor other than the diff's own. {@link #getDescriptor()} is the
     * <em>diff's</em> descriptor, which is the wrong one when a question is about an arc's target.
     */
    EntityResolver getResolver() {
        return resolver;
    }

    Persistent getObject() {
        return object;
    }

    DataContextObjectStore getStore() {
        return store;
    }

    /**
     * Returns the id of the row at the given flattened path, preferring one discovered by the flush in
     * progress over one the {@link ObjectStore} already tracks.
     */
    ObjectId getFlattenedId(ObjectId id, CayennePath path) {
        ObjectId flushLocal = flattenedPaths.getOrDefault(id, Collections.emptyMap()).get(path);
        return flushLocal != null ? flushLocal : store.getFlattenedId(id, path);
    }

    /**
     * Records the id of a flattened row that this flush intends to create. The id is kept local to the flush
     * so that later segments and attributes of the same flush see a consistent value, and is handed to the
     * {@link ObjectStore} only once the queries have executed - see {@link #promoteFlattenedPaths()}. Marking
     * the store directly here would leave behind an id for a row that a failed flush never inserted, and the
     * next flush would then take it for an existing row and issue an update matching nothing.
     * <p>
     * {@code GraphBasedDbRowOpSorter} is a deliberate exception: it reads {@code ObjectStore} directly while
     * sorting, i.e. before promotion, so it sees only durable markings. It is a public DI service and cannot be
     * handed the factory; the rows it consults snapshots for are covered by read-time markings from
     * {@code ObjectResolver.resolveAdditionalIds}.
     */
    void markFlattenedPath(ObjectId id, CayennePath path, ObjectId flattenedId) {
        flattenedPaths.computeIfAbsent(id, i -> new HashMap<>(4)).put(path, flattenedId);
    }

    Collection<ObjectId> getFlattenedIds(ObjectId id) {
        return getFlattenedPathIdMap(id).values();
    }

    Map<CayennePath, ObjectId> getFlattenedPathIdMap(ObjectId id) {
        Map<CayennePath, ObjectId> tracked = store.getFlattenedPathIdMap(id);
        Map<CayennePath, ObjectId> flushLocal = flattenedPaths.getOrDefault(id, Collections.emptyMap());

        if (flushLocal.isEmpty()) {
            return tracked;
        }
        if (tracked.isEmpty()) {
            // not the live staging map: callers iterate this while creating further ops
            return Collections.unmodifiableMap(new HashMap<>(flushLocal));
        }

        Map<CayennePath, ObjectId> merged = new HashMap<>(tracked);
        merged.putAll(flushLocal);
        return merged;
    }

    /**
     * Hands every flattened path discovered by this flush to the {@link ObjectStore}. Called once the flush has
     * executed its queries, so that a flush failing before that point leaves nothing behind.
     * <p>
     * Paths are keyed on the owner's permanent id, the id its snapshot is taken under. The staged id is promoted
     * as is, since its {@code getIdSnapshot()} already resolves to the row's real key.
     * <p>
     * Note this is keyed on the queries having run, not on the transaction having committed: a failure raised
     * at COMMIT - a {@code DEFERRABLE INITIALLY DEFERRED} constraint, say - still promotes. That window is not
     * specific to flattened paths, since {@code postprocess} likewise flips objects to COMMITTED before the
     * transaction ends.
     */
    void promoteFlattenedPaths() {
        flattenedPaths.forEach((id, paths) -> paths.forEach((path, flattenedId)
                -> store.markFlattenedPath(permanent(id), path, flattenedId)));
        flattenedPaths.clear();
    }

    private static ObjectId permanent(ObjectId id) {
        if(id.isTemporary() && id.isReplacementIdAttached()) {
            return id.createReplacementId();
        }
        return id;
    }

    ObjectDiff getDiff() {
        return diff;
    }

    Set<ArcTarget> getProcessedArcs() {
        return processedArcs;
    }
}
