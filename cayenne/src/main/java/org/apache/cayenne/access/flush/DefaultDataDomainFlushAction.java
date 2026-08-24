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

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.access.ObjectDiff;
import org.apache.cayenne.access.DataContextObjectStore;
import org.apache.cayenne.access.ObjectStoreGraphDiff;
import org.apache.cayenne.access.OperationObserver;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpMerger;
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.DbRowOpVisitor;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOp;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOpFactory;
import org.apache.cayenne.access.flush.operation.InsertDbRowOp;
import org.apache.cayenne.access.flush.operation.OpIdFactory;
import org.apache.cayenne.access.flush.operation.UpdateDbRowOp;
import org.apache.cayenne.graph.ArcId;
import org.apache.cayenne.graph.CompoundDiff;
import org.apache.cayenne.graph.GraphChangeHandler;
import org.apache.cayenne.graph.GraphDiff;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link DataDomainFlushAction}.
 *
 * @since 4.2
 */
public class DefaultDataDomainFlushAction implements DataDomainFlushAction {

    protected final DataDomain dataDomain;
    protected final DbRowOpSorter dbRowOpSorter;
    protected final OperationObserver observer;
    protected final DeleteDbRowOpFactory deleteDbRowOpFactory;

    protected DefaultDataDomainFlushAction(DataDomain dataDomain, DbRowOpSorter dbRowOpSorter,
                                           DeleteDbRowOpFactory deleteDbRowOpFactory) {
        this.dataDomain = dataDomain;
        this.dbRowOpSorter = dbRowOpSorter;
        this.observer = new FlushObserver();
        this.deleteDbRowOpFactory = deleteDbRowOpFactory;
    }

    @Override
    public GraphDiff flush(DataContext context, GraphDiff changes) {
        CompoundDiff afterCommitDiff = new CompoundDiff();
        if (changes == null) {
            return afterCommitDiff;
        }
        if(!(changes instanceof ObjectStoreGraphDiff objectStoreGraphDiff)) {
            throw new CayenneRuntimeException("Instance of ObjectStoreGraphDiff expected, got %s", changes.getClass());
        }

        DataContextObjectStore objectStore = context.getObjectStore();

        Set<ArcTarget> processedArcs = new HashSet<>();
        Set<ArcTarget> declaredArcs = collectDeclaredArcs(objectStoreGraphDiff);
        DbRowOpFactory factory = new DbRowOpFactory(dataDomain.getEntityResolver(), objectStore, processedArcs,
                declaredArcs, deleteDbRowOpFactory);

        List<DbRowOp> dbRowOps = createDbRowOps(factory, objectStoreGraphDiff);
        updateObjectIds(dbRowOps);
        List<DbRowOp> deduplicatedOps = mergeSameObjectIds(dbRowOps);
        List<DbRowOp> filteredOps = filterOps(deduplicatedOps);
        List<DbRowOp> sortedOps = sort(filteredOps);
        List<? extends Query> queries = createQueries(sortedOps);
        executeQueries(queries);
        createReplacementIds(objectStore, afterCommitDiff, sortedOps);
        factory.promoteFlattenedPaths();
        // note: we are using here not filtered operations, but the original ones,
        // as we need them all for the postprocessing
        postprocess(context, objectStoreGraphDiff, afterCommitDiff, deduplicatedOps);

        return afterCommitDiff;
    }

    /**
     * Every arc event this flush's diffs declare through their {@code otherDiffs}, collected in one pass before
     * any row op is built.
     * <p>
     * Phantom-FK {@code db:}-prefixed arcs are <b>excluded</b> deliberately, not by oversight: this pass reads
     * {@code ObjectStoreGraphDiff.apply}, which omits the phantom-FK map, while the later walk reads
     * {@code ObjectDiff.apply}, which includes it. The set is therefore a strict subset of what the walk sees,
     * which is safe in the one direction it runs: a missing member can only make membership miss, and a miss
     * means the walk proceeds. It cannot be reached in any case, since a {@code db:} forward name is handled by
     * {@code processArcChange}'s null-relationship branch and never reaches the flattened predicate. Anyone
     * widening this query, or reusing the set for a different question, inherits the gap.
     * <p>
     * A flattened walk may skip writing its foreign key only when the arc's other endpoint will write it
     * instead, and the only sound evidence of that is that the other endpoint <em>declared</em> the arc. Route
     * presence cannot answer it: routes are stamped by the flush's own bookkeeping, by the target's own
     * attribute walk, and by walks that have already run, so it is wrong in both directions of provenance.
     * <p>
     * <b>Target ids are recorded exactly as the events raise them, with no substitution.</b>
     * {@code ArcValuesCreationHandler.processArcChange}'s substitution of a created arc's target chooses
     * <em>which</em> target the flush asks about, and reads per-diff state this pass runs before. This set is a
     * lookup table keyed by object identity, so it answers correctly for whichever target the query supplies:
     * a diff's node id and a resolved arc value denote the same object by the same {@link ObjectId}. Where the
     * two disagree in id <em>form</em> -- a temporary id against the permanent one an insert replaced it with
     * -- membership misses and the walk proceeds, the conservative direction, since skipping is what silently
     * loses a write.
     *
     * @param changes object graph diff
     * @return every arc this flush declares, in both create and delete sense
     */
    private Set<ArcTarget> collectDeclaredArcs(ObjectStoreGraphDiff changes) {
        Set<ArcTarget> declared = new HashSet<>();
        changes.apply(new GraphChangeHandler() {
            @Override
            public void arcCreated(ObjectId id, ObjectId targetId, ArcId arcId) {
                declared.add(new ArcTarget(id, targetId, arcId, false));
            }

            @Override
            public void arcDeleted(ObjectId id, ObjectId targetId, ArcId arcId) {
                declared.add(new ArcTarget(id, targetId, arcId, true));
            }
        });
        return declared;
    }

    /**
     * Create ops based on incoming graph changes
     * @param creator creator of this flush's operations
     * @param changes object graph diff
     * @return collection of {@link DbRowOp}
     */
    protected List<DbRowOp> createDbRowOps(DbRowOpCreator creator, ObjectStoreGraphDiff changes) {
        Map<ObjectId, ObjectDiff> changesByObjectId = changes.getChangesByObjectId();
        List<DbRowOp> ops = new ArrayList<>(changesByObjectId.size());

        // ops.addAll() method is slower in this case as it will allocate new array for all values
        //noinspection UseBulkOperation
        changesByObjectId.forEach((obj, diff) -> creator.createRows(diff).forEach(ops::add));

        return ops;
    }

    /**
     * Fill in replacement IDs' data for given operations
     * @param dbRowOps collection of {@link DbRowOp}
     */
    protected void updateObjectIds(Collection<DbRowOp> dbRowOps) {
        DbRowOpVisitor<Void> permIdVisitor = new PermanentObjectIdVisitor(dataDomain);
        dbRowOps.forEach(row -> row.accept(permIdVisitor));
    }

    /**
     * @param dbRowOps collection of {@link DbRowOp}
     * @return collection of ops with merged duplicates
     */
    protected List<DbRowOp> mergeSameObjectIds(List<DbRowOp> dbRowOps) {
        Map<ObjectId, DbRowOp> index = new LinkedHashMap<>(dbRowOps.size());
        // new EffectiveOpId()
        dbRowOps.forEach(row -> index.merge(OpIdFactory.idForOperation(row.getChangeId()), row, new DbRowOpMerger()));
        // reuse list
        dbRowOps.clear();
        dbRowOps.addAll(index.values());
        return dbRowOps;
    }

    protected List<DbRowOp> filterOps(List<DbRowOp> dbRowOps) {
        List<DbRowOp> opsToRemove = null;
        for (DbRowOp row : dbRowOps) {
            // clear phantom update (this can be from insert/delete of arc with transient object)
            row.accept(PhantomDbRowOpCleaner.INSTANCE);
            // check for an empty update that we may need to filter out
            if (row.accept(EmptyRowChecker.INSTANCE)) {
                if (opsToRemove == null) {
                    opsToRemove = new ArrayList<>();
                }
                opsToRemove.add(row);
            }
        }

        if(opsToRemove != null && !opsToRemove.isEmpty()) {
            // make a duplicate, to keep original operations intact
            dbRowOps = new ArrayList<>(dbRowOps);
            dbRowOps.removeAll(opsToRemove);
        }

        return dbRowOps;
    }

    /**
     * Sort all operations
     * @param dbRowOps collection of {@link DbRowOp}
     * @return sorted collection of operations
     * @see DbRowOpSorter interface and it's default implementation
     */
    protected List<DbRowOp> sort(List<DbRowOp> dbRowOps) {
        return dbRowOpSorter.sort(dbRowOps);
    }

    /**
     *
     * @param dbRowOps collection of {@link DbRowOp}
     * @return collection of {@link Query} to perform
     */
    protected List<? extends Query> createQueries(List<DbRowOp> dbRowOps) {
        QueryCreatorVisitor queryCreator = new QueryCreatorVisitor(dbRowOps.size());
        dbRowOps.forEach(row -> row.accept(queryCreator));
        return queryCreator.getQueryList();
    }

    /**
     * Execute queries, grouping them by nodes
     * @param queries to execute
     */
    protected void executeQueries(List<? extends Query> queries) {
        EntityResolver entityResolver = dataDomain.getEntityResolver();
        queries.stream()
                .collect(Collectors.groupingBy(query
                        -> dataDomain.lookupDataNode(query.getMetaData(entityResolver).getDataMap())))
                .forEach((node, nodeQueries)
                        -> node.performQueries(nodeQueries, observer));
    }

    /**
     * Set final {@link ObjectId} for persistent objects
     *
     * @param store object store
     * @param afterCommitDiff result graph diff
     * @param dbRowOps collection of {@link DbRowOp}
     */
    protected void createReplacementIds(DataContextObjectStore store, CompoundDiff afterCommitDiff, List<DbRowOp> dbRowOps) {
        ReplacementIdVisitor visitor = new ReplacementIdVisitor(dataDomain.getEntityResolver(), afterCommitDiff);
        dbRowOps.forEach(row -> row.accept(visitor));
    }

    /**
     * Notify {@link DataContextObjectStore} and it's data row cache about actual changes we performed.
     *
     * @param context originating context
     * @param changes incoming diff
     * @param afterCommitDiff resulting diff
     * @param dbRowOps collection of {@link DbRowOp}
     */
    protected void postprocess(DataContext context, ObjectStoreGraphDiff changes, CompoundDiff afterCommitDiff, List<DbRowOp> dbRowOps) {
        DataContextObjectStore objectStore = context.getObjectStore();

        PostprocessVisitor postprocessor = new PostprocessVisitor(context);
        dbRowOps.forEach(row -> row.accept(postprocessor));

        DataDomainIndirectDiffBuilder indirectDiffBuilder = new DataDomainIndirectDiffBuilder(context.getEntityResolver());
        indirectDiffBuilder.processChanges(changes);

        objectStore.getDataRowCache()
                .processSnapshotChanges(
                        objectStore,
                        postprocessor.getUpdatedSnapshots(),
                        postprocessor.getDeletedIds(),
                        Collections.emptyList(),
                        indirectDiffBuilder.getIndirectModifications()
                );
        objectStore.postprocessAfterCommit(afterCommitDiff);
    }

    protected static class PhantomDbRowOpCleaner implements DbRowOpVisitor<Void> {

        protected static final DbRowOpVisitor<Void> INSTANCE = new PhantomDbRowOpCleaner();

        @Override
        public Void visitUpdate(UpdateDbRowOp dbRow) {
            if(dbRow.getChangeId().isTemporary() && !dbRow.getChangeId().isReplacementIdAttached()) {
                dbRow.getValues().clear();
            }
            return null;
        }
    }

    protected static class EmptyRowChecker implements DbRowOpVisitor<Boolean> {

        protected static final EmptyRowChecker INSTANCE = new EmptyRowChecker();

        @Override
        public Boolean visitDelete(DeleteDbRowOp dbRow) {
            return false;
        }

        @Override
        public Boolean visitInsert(InsertDbRowOp dbRow) {
            return false;
        }

        @Override
        public Boolean visitUpdate(UpdateDbRowOp dbRow) {
            return dbRow.getValues().isEmpty();
        }
    }
}
