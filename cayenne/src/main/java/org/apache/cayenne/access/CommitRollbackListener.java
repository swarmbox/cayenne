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

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.tx.Transaction;
import org.apache.cayenne.tx.TransactionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Undoes the publication of the commits of one transaction to one shared snapshot cache when the transaction is
 * rolled back: each originating context is left as a local rollback would leave it, and the shared cache forgets the
 * commits' rows, posting the usual event so that peer contexts forget them too. See
 * {@link DataContextObjectStore#undoCommitOnRollback}. The {@link DataRowStore} keeps one such listener per
 * transaction, and every commit of the transaction to that cache is added to it.
 * <p>
 * The objects are held weakly. A long transaction may commit many contexts one after another and rely on the
 * objects of the earlier ones being collected, and an object nothing else references needs no undoing. The ids are
 * held strongly, as the shared cache has to forget them whether or not the objects survive.
 * <p>
 * So that a transaction committing many rows doesn't hold an id and an object reference for each of them until it
 * ends, the listener holds them only up to {@link #MAX_RETAINED_IDS}. A commit that would take it past that turns the
 * listener coarse: it then holds only the names of the entities the commits wrote and the contexts that made them.
 * Its rollback turns the COMMITTED objects of those entities HOLLOW in those contexts, while their NEW and MODIFIED
 * objects keep their pending changes, and the shared cache forgets every snapshot of those entities without posting
 * an event, as there are no ids to post. An object the commits inserted then fails when it is read rather than being
 * taken for a row that exists, and a peer context keeps the values it merged until it refreshes them.
 * <p>
 * The undoing must never keep the transaction from rolling back, so it doesn't throw: a failure is logged at ERROR
 * instead. {@code BaseTransaction.rollback()} calls the listeners before it rolls back the connections, and a
 * listener that throws makes it skip that rollback and release the connections with their work still open. A pool
 * that doesn't roll back a connection when it is returned, as Cayenne's own {@code UnmanagedPoolingDataSource}
 * doesn't, then hands it out again with that work pending, to be committed by whoever next turns autocommit on.
 * Errors are let through, as they are not the undo's to handle.
 *
 * @since 5.0
 */
class CommitRollbackListener implements TransactionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommitRollbackListener.class);

    /**
     * The number of ids and object references a listener holds at most before it turns coarse.
     */
    static final int MAX_RETAINED_IDS = 10_000;

    // the listener holds no reference to its transaction, which the cache keeps it under as a weak key
    private final DataRowStore dataRowCache;

    // the commits, in the order they were made, while the listener is exact
    private List<CommitUndo> commits;
    private int retainedIds;

    // once the listener is coarse, the entities the commits wrote and the stores of the contexts that made them
    private Set<String> writtenEntities;
    private Set<DataContextObjectStore> committingStores;

    CommitRollbackListener(DataRowStore dataRowCache) {
        this.dataRowCache = dataRowCache;
        this.commits = new ArrayList<>();
    }

    /**
     * Adds a commit to undo should the transaction roll back.
     *
     * @param committed the changes being committed, with their objects still in their uncommitted states
     */
    synchronized void addCommit(DataContextObjectStore store, Collection<ObjectDiff> committed,
                                Collection<ObjectId> writtenIds, Collection<ObjectId> indirectlyModifiedIds) {
        int commitIds = committed.size() + writtenIds.size() + indirectlyModifiedIds.size();
        if (!isCoarse() && retainedIds + commitIds > MAX_RETAINED_IDS) {
            turnCoarse();
        }

        if (isCoarse()) {
            addCoarseCommit(store, writtenIds, indirectlyModifiedIds);
        } else {
            commits.add(new CommitUndo(store, committed, writtenIds, indirectlyModifiedIds));
            retainedIds += commitIds;
        }
    }

    /**
     * Returns the number of ids and object references the listener holds.
     */
    synchronized int getRetainedIds() {
        return retainedIds;
    }

    synchronized boolean isCoarse() {
        return commits == null;
    }

    private void turnCoarse() {
        writtenEntities = new HashSet<>();
        committingStores = Collections.newSetFromMap(new WeakHashMap<>());
        for (CommitUndo commit : commits) {
            DataContextObjectStore store = commit.store.get();
            addCoarseCommit(store, commit.writtenIds, commit.indirectlyModifiedIds);
        }
        commits = null;
        retainedIds = 0;
    }

    private void addCoarseCommit(DataContextObjectStore store, Collection<ObjectId> writtenIds,
                                 Collection<ObjectId> indirectlyModifiedIds) {
        if (store != null) {
            committingStores.add(store);
        }
        addEntityNames(writtenEntities, writtenIds);
        addEntityNames(writtenEntities, indirectlyModifiedIds);
    }

    private static void addEntityNames(Set<String> entityNames, Collection<ObjectId> ids) {
        for (ObjectId id : ids) {
            entityNames.add(id.getEntityName());
        }
    }

    /**
     * Undoes the commits, in the order they were made, in their contexts and in the shared cache. Never throws a
     * RuntimeException: a failure of any step is logged, and the other steps are still attempted. See the class
     * documentation for why.
     */
    @Override
    public synchronized void willRollback(Transaction tx) {
        dataRowCache.rollbackListenerDone(tx);
        if (isCoarse()) {
            undoCoarse();
        } else {
            for (CommitUndo commit : commits) {
                commit.undo(dataRowCache);
            }
        }
    }

    private void undoCoarse() {
        for (DataContextObjectStore store : new ArrayList<>(committingStores)) {
            try {
                store.committedObjectsInvalidated(writtenEntities);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to undo rolled back commits in their context ({}): the committed objects of "
                        + "{} may still appear committed until they are refreshed", store, writtenEntities, e);
            }
        }

        try {
            dataRowCache.forgetSnapshots(writtenEntities);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to drop rolled back commits' rows from the shared snapshot cache ({}): the rows of "
                    + "{} may still be served from it until they are refreshed", dataRowCache, writtenEntities, e);
        }
    }

    @Override
    public void willCommit(Transaction tx) {
        // the commits stay published
        dataRowCache.rollbackListenerDone(tx);
    }

    @Override
    public void willAddConnection(Transaction tx, String connectionName, Connection connection) {
        // no connection-level state
    }

    /**
     * The undoing of one commit.
     */
    private static class CommitUndo {

        private final Reference<DataContextObjectStore> store;
        private final List<Reference<Persistent>> inserted;
        private final List<Reference<Persistent>> updated;
        private final List<Reference<Persistent>> deleted;
        private final Collection<ObjectId> writtenIds;
        private final Collection<ObjectId> indirectlyModifiedIds;

        CommitUndo(DataContextObjectStore store, Collection<ObjectDiff> committed, Collection<ObjectId> writtenIds,
                   Collection<ObjectId> indirectlyModifiedIds) {
            this.store = new WeakReference<>(store);
            this.inserted = new ArrayList<>();
            this.updated = new ArrayList<>();
            this.deleted = new ArrayList<>();
            this.writtenIds = writtenIds;
            this.indirectlyModifiedIds = indirectlyModifiedIds;

            for (ObjectDiff diff : committed) {
                Persistent object = (Persistent) diff.getObject();
                Reference<Persistent> reference = new WeakReference<>(object);
                switch (object.getPersistenceState()) {
                    case PersistenceState.NEW -> inserted.add(reference);
                    case PersistenceState.DELETED -> deleted.add(reference);
                    // MODIFIED, or a change found to be a phantom one: either way the commit made it COMMITTED
                    default -> updated.add(reference);
                }
            }
        }

        /**
         * Undoes the commit in its context, then in the shared cache.
         */
        void undo(DataRowStore dataRowCache) {
            DataContextObjectStore objectStore = store.get();
            if (objectStore != null) {
                undoInContext(objectStore);
            }
            undoInCache(dataRowCache, objectStore);
        }

        private void undoInContext(DataContextObjectStore objectStore) {
            List<Persistent> insertedObjects = reachable(inserted);
            List<Persistent> updatedObjects = reachable(updated);
            List<Persistent> deletedObjects = reachable(deleted);
            try {
                objectStore.commitRolledBack(insertedObjects, updatedObjects, deletedObjects);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to undo a rolled back commit in its context ({}): {} inserted, {} updated and "
                                + "{} deleted objects may still appear committed until they are refreshed",
                        objectStore, insertedObjects.size(), updatedObjects.size(), deletedObjects.size(), e);
            }
        }

        /**
         * @param objectStore the originating store, or null if it has been collected
         */
        private void undoInCache(DataRowStore dataRowCache, DataContextObjectStore objectStore) {
            boolean cacheTouched = !writtenIds.isEmpty() || !indirectlyModifiedIds.isEmpty();
            if (!cacheTouched) {
                return;
            }

            // posted by the originating store, which has just been brought up to date and so skips the event
            Object postedBy = objectStore != null ? objectStore : this;
            try {
                dataRowCache.processSnapshotChanges(
                        postedBy,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        writtenIds,
                        indirectlyModifiedIds);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to drop a rolled back commit's rows from the shared snapshot cache ({}): {} "
                                + "written and {} indirectly modified ids may still be served from it until they "
                                + "are refreshed",
                        postedBy, writtenIds.size(), indirectlyModifiedIds.size(), e);
            }
        }

        private static List<Persistent> reachable(List<Reference<Persistent>> references) {
            List<Persistent> objects = new ArrayList<>(references.size());
            for (Reference<Persistent> reference : references) {
                Persistent object = reference.get();
                if (object != null) {
                    objects.add(object);
                }
            }
            return objects;
        }
    }
}
