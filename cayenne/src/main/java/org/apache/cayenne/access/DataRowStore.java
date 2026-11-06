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

import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.access.event.SnapshotEvent;
import org.apache.cayenne.event.EventBridge;
import org.apache.cayenne.event.EventManager;
import org.apache.cayenne.event.EventSubject;
import org.apache.cayenne.tx.Transaction;
import org.apache.cayenne.util.ClockMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentMap;

/**
 * A fixed size cache of DataRows keyed by ObjectId.
 *
 * @since 1.1
 */
public class DataRowStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataRowStore.class);

    /**
     * @deprecated unused
     */
    @Deprecated(since = "5.0", forRemoval = true)
    public static final long SNAPSHOT_EXPIRATION_DEFAULT = 2 * 60 * 60;

    /**
     * @deprecated use {@link DefaultDataRowStoreFactory#SNAPSHOT_CACHE_SIZE_DEFAULT}
     */
    @Deprecated(since = "5.0", forRemoval = true)
    public static final int SNAPSHOT_CACHE_SIZE_DEFAULT = DefaultDataRowStoreFactory.SNAPSHOT_CACHE_SIZE_DEFAULT;

    protected String name;
    private final int maxSize;
    protected final ConcurrentMap<ObjectId, DataRow> snapshots;

    protected EventManager eventManager;
    protected EventBridge remoteNotificationsHandler;

    // the values the updates of the commit being processed on this thread wrote, by id, while the commit's call of the
    // five-argument processSnapshotChanges is in progress
    private final ThreadLocal<Map<ObjectId, Map<String, Object>>> commitWrittenValues = new ThreadLocal<>();

    // IMPORTANT: EventSubject must be an ivar to avoid its deallocation
    // too early, and thus disabling events.
    protected EventSubject eventSubject;

    // per transaction, the listener that undoes the commits published to this cache should it roll back. Keyed weakly,
    // so that a transaction that never ends doesn't stay here, while the listener removes itself when it does end
    private final Map<Transaction, CommitRollbackListener> rollbackListeners =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * @param name         DataRowStore name. Used to identify this DataRowStore in events, etc. Can't be null.
     * @param maxSize      max number of cached snapshots. Past this size the cache evicts the snapshots least
     *                     recently read.
     * @param eventManager EventManager that should be used for posting and receiving events.
     * @since 5.0
     */
    public DataRowStore(String name, int maxSize, EventManager eventManager) {
        if (name == null) {
            throw new IllegalArgumentException("DataRowStore name can't be null.");
        }

        this.name = name;
        this.eventSubject = createSubject();
        this.eventManager = eventManager;
        this.maxSize = maxSize;

        this.snapshots = new ClockMap<>(maxSize);
    }

    private EventSubject createSubject() {
        return EventSubject.getSubject(this.getClass(), name);
    }

    protected void setEventBridge(EventBridge eventBridge) {
        remoteNotificationsHandler = eventBridge;
    }

    protected EventBridge getEventBridge() {
        return remoteNotificationsHandler;
    }

    /**
     * Updates cached snapshots for the list of objects.
     *
     * @since 1.2
     */
    void snapshotsUpdatedForObjects(List<Persistent> objects, List<? extends DataRow> snapshots, boolean refresh) {
        snapshotsUpdatedForObjects(objects, snapshots, object -> refresh);
    }

    /**
     * Updates cached snapshots for the list of objects, replacing an object's cached snapshot with its new one only
     * if the object is refreshed. A snapshot missing from the cache is added either way.
     *
     * @param refreshed whether an object has been refreshed from its new snapshot
     * @since 5.0
     */
    void snapshotsUpdatedForObjects(List<Persistent> objects, List<? extends DataRow> snapshots,
                                    Predicate<Persistent> refreshed) {

        int size = objects.size();

        // sanity check
        if (size != snapshots.size()) {
            throw new IllegalArgumentException(
                    "Counts of objects and corresponding snapshots do not match. "
                            + "Objects count: "
                            + objects.size()
                            + ", snapshots count: "
                            + snapshots.size());
        }

        Map<ObjectId, DataRow> modified = null;
        Object eventPostedBy = null;

        for (int i = 0; i < size; i++) {
            Persistent object = objects.get(i);

            // skip null objects... possible in some query results, e.g. with an outer join in a SQLTemplate
            if (object == null) {
                continue;
            }

            // skip HOLLOW objects as they likely were created from partial snapshots
            if (object.getPersistenceState() == PersistenceState.HOLLOW) {
                continue;
            }

            ObjectId oid = object.getObjectId();

            // add snapshots if refresh is forced, or if a snapshot is
            // missing

            DataRow cachedSnapshot = this.snapshots.get(oid);
            if (cachedSnapshot == null || refreshed.test(object)) {

                DataRow newSnapshot = snapshots.get(i);

                if (cachedSnapshot != null) {
                    // the cached snapshot came from a later statement, so it is the one to keep. The same version
                    // means the same statement, so the same data: it is already in the cache
                    if (cachedSnapshot.getVersion() >= newSnapshot.getVersion()) {
                        continue;
                    }

                    // the same data from a later statement: nothing to store and nothing to announce. The cached
                    // version thus tracks the last statement that changed the data, not the last one that read it,
                    // which keeps a cache hit free of a map write and an LRU reorder
                    if (cachedSnapshot.equals(newSnapshot)) {
                        continue;
                    }
                }

                if (modified == null) {
                    modified = new HashMap<>();
                    eventPostedBy = object.getObjectContext().getObjectStore();
                }

                modified.put(oid, newSnapshot);
            }
        }

        if (modified != null) {
            processSnapshotChanges(
                    eventPostedBy,
                    modified,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }
    }

    /**
     * Returns current cache size.
     */
    public int size() {
        return snapshots.size();
    }

    /**
     * Returns maximum allowed cache size.
     */
    public int maximumSize() {
        return maxSize;
    }

    /**
     * Shuts down any remote notification connections, and clears internal cache.
     */
    public void shutdown() {
        stopListeners();
        clear();
    }

    /**
     * Returns the name of this DataRowStore. Name allows to create EventSubjects for
     * event notifications addressed to or sent from this DataRowStore.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this DataRowStore. Name allows to create EventSubjects for event
     * notifications addressed to or sent from this DataRowStore.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns an EventManager associated with this DataRowStore.
     *
     * @since 1.2
     */
    public EventManager getEventManager() {
        return eventManager;
    }

    /**
     * Sets an EventManager associated with this DataRowStore.
     *
     * @since 1.2
     */
    public void setEventManager(EventManager eventManager) {
        if (eventManager != this.eventManager) {
            stopListeners();
            this.eventManager = eventManager;
            startListeners();
        }
    }

    /**
     * Returns cached snapshot or null if no snapshot is currently cached for the given
     * ObjectId.
     */
    public DataRow getCachedSnapshot(ObjectId oid) {
        return snapshots.get(oid);
    }

    /**
     * Returns EventSubject used by this SnapshotCache to notify of snapshot changes.
     */
    public EventSubject getSnapshotEventSubject() {
        return eventSubject;
    }

    /**
     * Expires and removes all stored snapshots without sending any notification events.
     */
    public void clear() {
        snapshots.clear();
    }

    /**
     * Evicts a snapshot from cache without generating any SnapshotEvents.
     */
    public void forgetSnapshot(ObjectId id) {
        snapshots.remove(id);
    }

    /**
     * Evicts the snapshots of the given entities from the cache without generating any SnapshotEvents.
     *
     * @since 5.0
     */
    void forgetSnapshots(Set<String> entityNames) {
        for (ObjectId id : snapshots.keySet()) {
            if (entityNames.contains(id.getEntityName())) {
                snapshots.remove(id);
            }
        }
    }

    /**
     * Returns the listener that undoes the commits published to this cache in the transaction should it roll back,
     * adding a new one to the transaction for its first commit.
     *
     * @since 5.0
     */
    CommitRollbackListener rollbackListener(Transaction transaction) {
        return rollbackListeners.computeIfAbsent(transaction, tx -> {
            CommitRollbackListener listener = new CommitRollbackListener(this);
            tx.addListener(listener);
            return listener;
        });
    }

    /**
     * Forgets the rollback listener of a transaction that has ended.
     *
     * @since 5.0
     */
    void rollbackListenerDone(Transaction transaction) {
        rollbackListeners.remove(transaction);
    }

    /**
     * Handles remote events received via EventBridge. Performs needed snapshot updates,
     * and then resends the event to local listeners.
     */
    public void processRemoteEvent(SnapshotEvent event) {
        if (event.getSource() != remoteNotificationsHandler) {
            return;
        }

        LOGGER.debug("remote event: {}", event);

        Collection<ObjectId> deletedSnapshotIds = event.getDeletedIds();
        Collection<ObjectId> invalidatedSnapshotIds = event.getInvalidatedIds();
        Map<ObjectId, Map<String, Object>> diffs = event.getModifiedDiffs();
        Collection<ObjectId> indirectlyModifiedIds = event.getIndirectlyModifiedIds();

        if (deletedSnapshotIds.isEmpty()
                && invalidatedSnapshotIds.isEmpty()
                && diffs.isEmpty()
                && indirectlyModifiedIds.isEmpty()) {
            LOGGER.warn("processRemoteEvent.. bogus call... no changes.");
            return;
        }

        processDeletedIDs(deletedSnapshotIds);
        processInvalidatedIDs(invalidatedSnapshotIds);
        long version = processUpdateDiffs(diffs);
        sendUpdateNotification(
                event.getPostedBy(),
                version,
                diffs,
                deletedSnapshotIds,
                invalidatedSnapshotIds,
                indirectlyModifiedIds);
    }

    /**
     * Processes changes made to snapshots. Modifies internal cache state, and then sends
     * the event to all listeners. Source of these changes is usually an ObjectStore.
     * <p>
     * A commit reaches this method through
     * {@link #processSnapshotChanges(Object, Map, Collection, Collection, Collection, Map)}, which hands it the values
     * the commit's updates wrote in per-thread state for the duration of the call. This method takes them before
     * anything else, so any other call, including one made while the commit's event is delivered, runs without them.
     * A subclass overriding this method therefore sees every commit, and its call of the superclass method processes
     * the commit with its written values.
     */
    public void processSnapshotChanges(
            Object postedBy,
            Map<ObjectId, DataRow> updatedSnapshots,
            Collection<ObjectId> deletedSnapshotIds,
            Collection<ObjectId> invalidatedSnapshotIds,
            Collection<ObjectId> indirectlyModifiedIds) {

        Map<ObjectId, Map<String, Object>> writtenValues = takeCommitWrittenValues();
        applySnapshotChanges(
                postedBy,
                updatedSnapshots,
                deletedSnapshotIds,
                invalidatedSnapshotIds,
                indirectlyModifiedIds,
                writtenValues);
    }

    /**
     * Processes changes made to snapshots by a commit. Modifies internal cache state, and then sends the event to all
     * listeners. The written values are the columns each update of the commit wrote, by id. A snapshot that replaces
     * a cached one is announced with its differences from that one; one that had no cached predecessor is announced
     * with its written values, so that the peers holding the object hear of the update even if its row was not
     * cached. Inserts have no written values, and are not announced.
     * <p>
     * The changes are processed by a call of
     * {@link #processSnapshotChanges(Object, Map, Collection, Collection, Collection)}, which receives the written
     * values through per-thread state set for the duration of that call only.
     *
     * @since 5.0
     */
    public void processSnapshotChanges(
            Object postedBy,
            Map<ObjectId, DataRow> updatedSnapshots,
            Collection<ObjectId> deletedSnapshotIds,
            Collection<ObjectId> invalidatedSnapshotIds,
            Collection<ObjectId> indirectlyModifiedIds,
            Map<ObjectId, Map<String, Object>> writtenValues) {

        commitWrittenValues.set(writtenValues);
        try {
            processSnapshotChanges(
                    postedBy,
                    updatedSnapshots,
                    deletedSnapshotIds,
                    invalidatedSnapshotIds,
                    indirectlyModifiedIds);
        } finally {
            commitWrittenValues.remove();
        }
    }

    private Map<ObjectId, Map<String, Object>> takeCommitWrittenValues() {
        Map<ObjectId, Map<String, Object>> writtenValues = commitWrittenValues.get();
        if (writtenValues == null) {
            return Collections.emptyMap();
        }

        commitWrittenValues.remove();
        return writtenValues;
    }

    private void applySnapshotChanges(
            Object postedBy,
            Map<ObjectId, DataRow> updatedSnapshots,
            Collection<ObjectId> deletedSnapshotIds,
            Collection<ObjectId> invalidatedSnapshotIds,
            Collection<ObjectId> indirectlyModifiedIds,
            Map<ObjectId, Map<String, Object>> writtenValues) {

        // update the internal cache, prepare snapshot event

        if (deletedSnapshotIds.isEmpty()
                && invalidatedSnapshotIds.isEmpty()
                && updatedSnapshots.isEmpty()
                && (indirectlyModifiedIds == null || indirectlyModifiedIds.isEmpty())) {
            LOGGER.warn("postSnapshotsChangeEvent.. bogus call... no changes.");
            return;
        }

        processDeletedIDs(deletedSnapshotIds);
        processInvalidatedIDs(invalidatedSnapshotIds);
        Map<ObjectId, Map<String, Object>> diffs = processUpdatedSnapshots(updatedSnapshots, writtenValues);
        sendUpdateNotification(
                postedBy,
                version(updatedSnapshots),
                diffs,
                deletedSnapshotIds,
                invalidatedSnapshotIds,
                indirectlyModifiedIds);
    }

    private void processDeletedIDs(Collection<ObjectId> deletedSnapshotIDs) {
        // DELETED: evict deleted snapshots
        if (!deletedSnapshotIDs.isEmpty()) {
            for (ObjectId deletedSnapshotID : deletedSnapshotIDs) {
                snapshots.remove(deletedSnapshotID);
            }
        }
    }

    private void processInvalidatedIDs(Collection<ObjectId> invalidatedSnapshotIds) {
        // INVALIDATED: forget snapshot, treat as expired from cache
        if (!invalidatedSnapshotIds.isEmpty()) {
            for (ObjectId invalidatedSnapshotId : invalidatedSnapshotIds) {
                snapshots.remove(invalidatedSnapshotId);
            }
        }
    }

    /**
     * Returns the version of the snapshots, which are of one operation, or {@link SnapshotVersion#NONE} if there are
     * none.
     */
    private static long version(Map<ObjectId, DataRow> snapshots) {
        long version = SnapshotVersion.NONE;
        for (DataRow snapshot : snapshots.values()) {
            version = Math.max(version, snapshot.getVersion());
        }
        return version;
    }

    private Map<ObjectId, Map<String, Object>> processUpdatedSnapshots(
            Map<ObjectId, DataRow> updatedSnapshots,
            Map<ObjectId, Map<String, Object>> writtenValues) {
        Map<ObjectId, Map<String, Object>> diffs = null;

        // MODIFIED: replace/add snapshots, generate diffs for event
        if (!updatedSnapshots.isEmpty()) {
            for (Map.Entry<ObjectId, DataRow> entry : updatedSnapshots.entrySet()) {
                ObjectId key = entry.getKey();
                DataRow newSnapshot = entry.getValue();

                // A snapshot of a later operation replaces the cached one. A snapshot of an earlier operation arriving
                // late is dropped, as the cache already holds a newer state: another context may have read or
                // committed the row since. The versions are compared atomically with the replacement, as snapshots of
                // the same row may be updated concurrently.
                DataRow[] replaced = new DataRow[1];
                DataRow stored = snapshots.merge(key, newSnapshot, (cached, candidate) -> {
                    if (cached.getVersion() >= candidate.getVersion()) {
                        return cached;
                    }
                    replaced[0] = cached;
                    return candidate;
                });

                if (stored != newSnapshot) {
                    LOGGER.debug("snapshot is older than the cached one, ignoring it. Cached: {}, New: {}",
                            stored, newSnapshot);
                    continue;
                }

                // a replaced snapshot is announced with what changed in it. Without one, the row was not cached, e.g.
                // evicted, but peers may still hold the object: an update is announced with the values it wrote
                Map<String, Object> diff = replaced[0] != null
                        ? diff(replaced[0], newSnapshot)
                        : writtenValues.get(key);

                if (diff != null) {
                    if (diffs == null) {
                        diffs = new HashMap<>();
                    }

                    diffs.put(key, diff);
                }
            }
        }

        return diffs;
    }

    /**
     * Returns the keys of {@code oldSnapshot} whose values differ in {@code newSnapshot}, with the values of
     * {@code newSnapshot}, or null if there are no differences. Key sets are assumed compatible, i.e. both rows are
     * snapshots of the same entity.
     */
    private static Map<String, Object> diff(DataRow oldSnapshot, DataRow newSnapshot) {
        // forEach, unlike the entry set, allocates no entries
        Diff diff = new Diff(newSnapshot);
        oldSnapshot.forEach(diff);
        return diff.changes;
    }

    private static final class Diff implements BiConsumer<String, Object> {

        private final DataRow newSnapshot;
        private HashMap<String, Object> changes;

        Diff(DataRow newSnapshot) {
            this.newSnapshot = newSnapshot;
        }

        @Override
        public void accept(String name, Object oldValue) {
            Object newValue = newSnapshot.get(name);
            if (!Objects.deepEquals(oldValue, newValue)) {
                if (changes == null) {
                    changes = HashMap.newHashMap(newSnapshot.size());
                }
                changes.put(name, newValue);
            }
        }
    }

    /**
     * Applies the diffs of a remote event to the cached snapshots and returns the version the merged snapshots got,
     * or {@link SnapshotVersion#NONE} if there were no diffs.
     */
    private long processUpdateDiffs(Map<ObjectId, Map<String, Object>> diffs) {
        if (diffs.isEmpty()) {
            return SnapshotVersion.NONE;
        }

        // the diffs come from another JVM, so their version is not comparable with the local ones: the merged
        // snapshots take a version of this JVM
        long version = SnapshotVersion.next();
        for (Map.Entry<ObjectId, Map<String, Object>> entry : diffs.entrySet()) {
            ObjectId key = entry.getKey();
            DataRow oldSnapshot = snapshots.remove(key);

            if (oldSnapshot == null) {
                continue;
            }

            snapshots.put(key, oldSnapshot.merged(entry.getValue(), version));
        }
        return version;
    }

    private void sendUpdateNotification(
            Object postedBy,
            long version,
            Map<ObjectId, Map<String, Object>> diffs,
            Collection<ObjectId> deletedSnapshotIDs,
            Collection<ObjectId> invalidatedSnapshotIDs,
            Collection<ObjectId> indirectlyModifiedIds) {

        // do not send bogus events... e.g. inserted objects are not counted
        if ((diffs != null && !diffs.isEmpty())
                || (deletedSnapshotIDs != null && !deletedSnapshotIDs.isEmpty())
                || (invalidatedSnapshotIDs != null && !invalidatedSnapshotIDs.isEmpty())
                || (indirectlyModifiedIds != null && !indirectlyModifiedIds.isEmpty())) {

            SnapshotEvent event = new SnapshotEvent(
                    this,
                    postedBy,
                    version,
                    diffs,
                    deletedSnapshotIDs,
                    invalidatedSnapshotIDs,
                    indirectlyModifiedIds);

            LOGGER.debug("postSnapshotsChangeEvent: {}", event);

            // synchronously notify listeners; leaving it up to the listeners to
            // register as "non-blocking" if needed.
            eventManager.postEvent(event, getSnapshotEventSubject());
        }
    }

    void stopListeners() {
        if (eventManager != null) {
            eventManager.removeListener(this);
        }

        if (remoteNotificationsHandler != null) {
            try {
                remoteNotificationsHandler.shutdown();
            } catch (Exception ex) {
                LOGGER.info("Exception shutting down EventBridge.", ex);
            }
            remoteNotificationsHandler = null;
        }
    }

    void startListeners() {
        if (eventManager != null) {
            if (remoteNotificationsHandler != null) {
                try {
                    // listen to EventBridge ... must add itself as non-blocking listener
                    // otherwise a deadlock can occur as "processRemoteEvent" will attempt
                    // to
                    // obtain a lock on this object when the dispatch queue is locked...
                    // And
                    // another commit thread may have this object locked and attempt to
                    // lock
                    // dispatch queue

                    eventManager.addNonBlockingListener(
                            this,
                            SnapshotEvent.class,
                            DataRowStore::processRemoteEvent,
                            getSnapshotEventSubject(),
                            remoteNotificationsHandler);

                    // start EventBridge - it will listen to all event sources for this
                    // subject
                    remoteNotificationsHandler.startup(
                            eventManager,
                            EventBridge.RECEIVE_LOCAL_EXTERNAL);
                } catch (Exception ex) {
                    throw new CayenneRuntimeException(
                            "Error initializing DataRowStore.",
                            ex);
                }
            }
        }
    }
}
