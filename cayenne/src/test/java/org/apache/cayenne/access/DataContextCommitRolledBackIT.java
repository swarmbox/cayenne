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

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.CayenneRuntime;
import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.CoreModule;
import org.apache.cayenne.DataRow;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.event.EventManager;
import org.apache.cayenne.log.NoopSQLLogger;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.test.parallel.ParallelTestContainer;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.tx.BaseTransaction;
import org.apache.cayenne.tx.CayenneTransaction;
import org.apache.cayenne.tx.Transaction;
import org.apache.cayenne.tx.TransactionDescriptor;
import org.apache.cayenne.tx.TransactionListener;
import org.apache.cayenne.tx.TransactionManager;
import org.apache.cayenne.tx.TransactionPropagation;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transaction rolled back after a commit was flushed in it leaves every object the commit wrote as
 * {@link ObjectContext#rollbackChangesLocally()} would, and the shared cache and peer contexts forget its rows.
 */
public class DataContextCommitRolledBackIT {

    private static final String INJECTED_FAILURE = "injected failure after the flush";

    // make the shared cache refuse to forget rows, and UnregisterRefusingArtist refuse to be unregistered
    private static final AtomicBoolean failCacheInvalidation = new AtomicBoolean();
    private static final AtomicBoolean failUnregister = new AtomicBoolean();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT)
            .withExtraModules(binder -> {
                CoreModule.extend(binder).syncContexts();
                binder.bind(DataRowStoreFactory.class).to(InvalidationRefusingCacheFactory.class);
            });

    private DataContext context;

    private CayenneRuntime runtime;

    private TableHelper tArtist;

    private TableHelper tPainting;

    private ConnectionRecorder recorder;

    @BeforeEach
    public void setUp() {
        context = env.context();
        runtime = env.runtime();
        tArtist = env.table("ARTIST").setColumns("ARTIST_ID", "ARTIST_NAME");
        tPainting = env.table("PAINTING")
                .setColumns("PAINTING_ID", "PAINTING_TITLE", "PAINTING_DESCRIPTION")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.VARCHAR);
        recorder = new ConnectionRecorder();
        failCacheInvalidation.set(false);
        failUnregister.set(false);
    }

    @AfterEach
    public void tearDown() {
        failCacheInvalidation.set(false);
        failUnregister.set(false);
        recorder.rollBackIfLeaked();
    }

    @Test
    public void update() throws Exception {
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a");
        context.commitChanges();
        ObjectId id = artist.getObjectId();

        DataContext peer = (DataContext) runtime.newContext();
        Artist peerArtist = peer.localObject(artist);
        assertEquals("a", peerArtist.getArtistName());

        failAfter(() -> {
            artist.setArtistName("b");
            context.commitChanges();
        });

        assertEquals("a", artistNameInDb());
        assertNull(snapshotName(id));
        assertEquals(PersistenceState.HOLLOW, artist.getPersistenceState());
        assertEquals("a", artist.getArtistName());

        // resolved through the shared cache
        DataContext fresh = (DataContext) runtime.newContext();
        assertEquals("a", ((Artist) fresh.objectForPK(id)).getArtistName());

        // the peer merged "b" from the commit's snapshot event
        new ParallelTestContainer() {
            @Override
            protected void assertResult() {
                assertEquals(PersistenceState.HOLLOW, peerArtist.getPersistenceState());
            }
        }.runTest(3000);
        assertEquals("a", peerArtist.getArtistName());
    }

    @Test
    public void insert() throws Exception {
        Artist artist = context.newObject(Artist.class);
        ObjectId[] committedId = new ObjectId[1];
        int[] stateInTx = new int[1];

        failAfter(() -> {
            artist.setArtistName("a");
            context.commitChanges();
            committedId[0] = artist.getObjectId();
            stateInTx[0] = artist.getPersistenceState();
        });

        // CAY-766: inside the transaction the object was COMMITTED under its permanent id
        ObjectId id = committedId[0];
        assertEquals(PersistenceState.COMMITTED, stateInTx[0]);
        assertFalse(id.isTemporary());

        assertEquals(0, tArtist.getRowCount());
        assertNull(snapshotName(id));
        assertEquals(PersistenceState.TRANSIENT, artist.getPersistenceState());
        assertNull(artist.getObjectContext());
        assertNull(context.getObjectStore().getObject(id));
        assertFalse(context.hasChanges());

        DataContext fresh = (DataContext) runtime.newContext();
        assertNull(fresh.objectForPK(id));
    }

    @Test
    public void insert_ToManyOfCommittedParent() throws Exception {
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a");
        context.commitChanges();
        assertEquals(0, artist.getPaintingArray().size());

        Painting painting = context.newObject(Painting.class);
        failAfter(() -> {
            painting.setPaintingTitle("p");
            painting.setToArtist(artist);
            context.commitChanges();
        });

        assertEquals(PersistenceState.TRANSIENT, painting.getPersistenceState());
        assertEquals(0, artist.getPaintingArray().size());

        DataContext fresh = (DataContext) runtime.newContext();
        assertEquals(0, fresh.localObject(artist).getPaintingArray().size());
    }

    @Test
    public void delete() throws Exception {
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a");
        context.commitChanges();
        ObjectId id = artist.getObjectId();

        failAfter(() -> {
            context.deleteObject(artist);
            context.commitChanges();
        });

        assertEquals(1, tArtist.getRowCount());
        assertEquals(PersistenceState.HOLLOW, artist.getPersistenceState());
        assertSame(context, artist.getObjectContext());
        assertSame(artist, context.getObjectStore().getObject(id));
        assertEquals("a", artist.getArtistName());
    }

    // a retry after a rolled back commit writes every changed field, including one equal to the rolled back value
    @Test
    public void retryWritesEveryField() throws Exception {
        tPainting.insert(1, "t1", "d1");
        Painting painting = Cayenne.objectForPK(context, Painting.class, 1);
        assertEquals("d1", painting.getPaintingDescription());

        failAfter(() -> {
            painting.setPaintingTitle("rejected");
            painting.setPaintingDescription("d2");
            context.commitChanges();
        });

        painting.setPaintingTitle("t2");
        painting.setPaintingDescription("d2");
        context.commitChanges();

        assertEquals("t2", tPainting.getString("PAINTING_TITLE"));
        assertEquals("d2", tPainting.getString("PAINTING_DESCRIPTION"));
    }

    // several commits of two contexts in one transaction, some objects inserted then updated, inserted then
    // deleted, or updated then deleted
    @Test
    public void severalCommitsInOneTransaction() throws Exception {
        Artist updated = context.newObject(Artist.class);
        updated.setArtistName("u1");
        context.commitChanges();
        ObjectId updatedId = updated.getObjectId();

        Artist inserted = context.newObject(Artist.class);
        Artist insertedThenDeleted = context.newObject(Artist.class);
        DataContext other = (DataContext) runtime.newContext();
        Artist otherInserted = other.newObject(Artist.class);

        failAfter(() -> {
            inserted.setArtistName("i1");
            insertedThenDeleted.setArtistName("d1");
            updated.setArtistName("u2");
            context.commitChanges();

            inserted.setArtistName("i2");
            context.deleteObject(insertedThenDeleted);
            context.deleteObject(updated);
            context.commitChanges();

            otherInserted.setArtistName("o1");
            other.commitChanges();
        });

        assertEquals(1, tArtist.getRowCount());
        assertEquals(PersistenceState.TRANSIENT, inserted.getPersistenceState());
        assertNull(inserted.getObjectContext());
        assertEquals(PersistenceState.TRANSIENT, insertedThenDeleted.getPersistenceState());
        assertNull(insertedThenDeleted.getObjectContext());
        assertEquals(PersistenceState.TRANSIENT, otherInserted.getPersistenceState());

        assertEquals(PersistenceState.HOLLOW, updated.getPersistenceState());
        assertSame(updated, context.getObjectStore().getObject(updatedId));
        assertEquals("u1", updated.getArtistName());
        assertFalse(context.hasChanges());
        assertFalse(other.hasChanges());
    }

    // only the rolled back inner REQUIRES_NEW transaction is undone. It runs first, as on a connection of its own
    // it would wait on the locks of the outer one
    @Test
    public void requiresNewRolledBack_OuterCommitted() throws Exception {
        TransactionManager manager = runtime.getInjector().getInstance(TransactionManager.class);
        TransactionDescriptor requiresNew = TransactionDescriptor.builder()
                .propagation(TransactionPropagation.REQUIRES_NEW)
                .isolation(Connection.TRANSACTION_READ_COMMITTED)
                .build();

        Artist outer = context.newObject(Artist.class);
        DataContext innerContext = (DataContext) runtime.newContext();
        Artist inner = innerContext.newObject(Artist.class);

        // asserted after the outer transaction, as an assertion error inside it would leave it open
        CayenneRuntimeException[] innerFailure = new CayenneRuntimeException[1];
        runtime.performInTransaction(() -> {
            try {
                manager.performInTransaction(() -> {
                    inner.setArtistName("inner");
                    innerContext.commitChanges();
                    throw new CayenneRuntimeException(INJECTED_FAILURE);
                }, requiresNew);
            } catch (CayenneRuntimeException e) {
                innerFailure[0] = e;
            }

            outer.setArtistName("outer");
            context.commitChanges();
            return null;
        });

        assertInjectedFailure(() -> {
            if (innerFailure[0] != null) {
                throw innerFailure[0];
            }
        });
        assertEquals(1, tArtist.getRowCount());
        assertEquals("outer", artistNameInDb());
        assertEquals(PersistenceState.COMMITTED, outer.getPersistenceState());
        assertEquals(PersistenceState.TRANSIENT, inner.getPersistenceState());
    }

    @Test
    public void committedTransactionPublishes() throws Exception {
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a");
        context.commitChanges();
        ObjectId id = artist.getObjectId();

        DataContext peer = (DataContext) runtime.newContext();
        Artist peerArtist = peer.localObject(artist);
        assertEquals("a", peerArtist.getArtistName());

        runtime.performInTransaction(() -> {
            artist.setArtistName("b");
            context.commitChanges();
            return null;
        });

        assertEquals("b", artistNameInDb());
        assertEquals(PersistenceState.COMMITTED, artist.getPersistenceState());
        assertEquals("b", snapshotName(id));

        new ParallelTestContainer() {
            @Override
            protected void assertResult() {
                assertEquals(PersistenceState.COMMITTED, peerArtist.getPersistenceState());
                assertEquals("b", peerArtist.readPropertyDirectly(Artist.ARTIST_NAME.getName()));
            }
        }.runTest(3000);
    }

    // A failure while undoing a rolled back commit, in the context or in the shared cache, must not keep the other
    // part from being undone, or the transaction's connection from being rolled back.

    @Test
    public void undoFailure_Context() throws SQLException {
        UnregisterRefusingArtist artist = new UnregisterRefusingArtist();
        artist.setObjectId(ObjectId.of("Artist"));
        context.registerNewObject(artist);
        artist.setArtistName("a");
        ObjectId[] committedId = new ObjectId[1];
        failUnregister.set(true);

        assertInjectedFailure(() -> runtime.getInjector().getInstance(TransactionManager.class)
                .performInTransaction(() -> {
                    context.commitChanges();
                    committedId[0] = artist.getObjectId();
                    throw new CayenneRuntimeException(INJECTED_FAILURE);
                }, recorder));

        recorder.assertRolledBack();
        assertNull(snapshotName(committedId[0]));
        assertEquals(0, tArtist.getRowCount());
    }

    @Test
    public void undoFailure_Cache() throws SQLException {
        Artist artist = context.newObject(Artist.class);
        artist.setArtistName("a");
        context.commitChanges();
        failCacheInvalidation.set(true);

        assertInjectedFailure(() -> runtime.getInjector().getInstance(TransactionManager.class)
                .performInTransaction(() -> {
                    artist.setArtistName("b");
                    context.commitChanges();
                    throw new CayenneRuntimeException(INJECTED_FAILURE);
                }, recorder));

        recorder.assertRolledBack();
        assertEquals(PersistenceState.HOLLOW, artist.getPersistenceState());
        assertEquals("a", artistNameInDb());
    }

    // the commits of several contexts in one transaction share one listener per shared cache
    @Test
    public void oneRollbackListenerPerTransactionAndCache() throws Exception {
        DataContext other = (DataContext) runtime.newContext();
        ListenerCountingTransaction transaction = new ListenerCountingTransaction();
        BaseTransaction.bindThreadTransaction(transaction);
        try {
            context.newObject(Artist.class).setArtistName("a1");
            context.commitChanges();
            context.newObject(Artist.class).setArtistName("a2");
            context.commitChanges();
            other.newObject(Artist.class).setArtistName("o1");
            other.commitChanges();

            assertEquals(1, transaction.rollbackListenerCount());
        } finally {
            BaseTransaction.bindThreadTransaction(null);
            transaction.rollback();
        }

        assertEquals(0, tArtist.getRowCount());
        assertFalse(context.hasChanges());
        assertFalse(other.hasChanges());
    }

    // past the retained ids it can hold, a transaction's rollback undoes its commits by entity: the committed
    // objects of the entities written turn HOLLOW, and objects with pending changes keep them
    @Test
    public void manyCommittedRowsAreUndoneByEntity() throws Exception {
        Artist updated = context.newObject(Artist.class);
        updated.setArtistName("u1");
        Artist pending = context.newObject(Artist.class);
        pending.setArtistName("p1");
        context.commitChanges();
        ObjectId updatedId = updated.getObjectId();

        // an insert retains its object and its id
        int insertsPastTheLimit = CommitRollbackListener.MAX_RETAINED_IDS / 2 + 1;
        List<Artist> inserted = new ArrayList<>(insertsPastTheLimit);
        DataRowStore cache = context.getObjectStore().getDataRowCache();

        failAfter(() -> {
            updated.setArtistName("u2");
            context.commitChanges();

            for (int i = 0; i < insertsPastTheLimit; i++) {
                Artist artist = context.newObject(Artist.class);
                artist.setArtistName("i" + i);
                inserted.add(artist);
            }
            context.commitChanges();

            CommitRollbackListener listener = cache.rollbackListener(BaseTransaction.getThreadTransaction());
            assertTrue(listener.isCoarse());
            assertEquals(0, listener.getRetainedIds());

            pending.setArtistName("p2");
        });

        assertEquals(2, tArtist.getRowCount());
        assertNull(cache.getCachedSnapshot(updatedId));
        assertEquals(PersistenceState.HOLLOW, updated.getPersistenceState());
        assertEquals("u1", updated.getArtistName());
        assertEquals(PersistenceState.HOLLOW, inserted.getFirst().getPersistenceState());
        assertEquals(PersistenceState.MODIFIED, pending.getPersistenceState());
        assertEquals("p2", pending.getArtistName());
    }

    // fails the transaction after the work, as a constraint deferred to COMMIT would
    private void failAfter(Runnable work) {
        assertInjectedFailure(() -> runtime.performInTransaction(() -> {
            work.run();
            throw new CayenneRuntimeException(INJECTED_FAILURE);
        }));
    }

    // the work fails with the injected failure, not with one of its own
    private static void assertInjectedFailure(Executable work) {
        CayenneRuntimeException e = assertThrows(CayenneRuntimeException.class, work);
        assertTrue(e.getMessage().contains(INJECTED_FAILURE), () -> "unexpected failure: " + e);
    }

    // ARTIST_NAME is a CHAR column, padded on some databases
    private String artistNameInDb() throws SQLException {
        return tArtist.getString("ARTIST_NAME").trim();
    }

    private String snapshotName(ObjectId id) {
        DataRow row = context.getObjectStore().getDataRowCache().getCachedSnapshot(id);
        return row != null ? ((String) row.get("ARTIST_NAME")).trim() : null;
    }

    // throws when unregistered, which is the first thing the undo of its insert does
    public static class UnregisterRefusingArtist extends Artist {

        @Override
        public void setObjectContext(ObjectContext objectContext) {
            if (objectContext == null && failUnregister.get()) {
                throw new IllegalStateException("refusing to be unregistered");
            }
            super.setObjectContext(objectContext);
        }
    }

    public static class InvalidationRefusingCacheFactory implements DataRowStoreFactory {

        private final EventManager eventManager;

        public InvalidationRefusingCacheFactory(@Inject EventManager eventManager) {
            this.eventManager = eventManager;
        }

        @Override
        public DataRowStore createDataRowStore(String name) {
            return new InvalidationRefusingCache(name, eventManager);
        }
    }

    public static class InvalidationRefusingCache extends DataRowStore {

        public InvalidationRefusingCache(String name, EventManager eventManager) {
            super(name, DefaultDataRowStoreFactory.SNAPSHOT_CACHE_SIZE_DEFAULT, eventManager);
        }

        @Override
        public void processSnapshotChanges(Object postedBy, Map<ObjectId, DataRow> updatedSnapshots,
                                           Collection<ObjectId> deletedSnapshotIds,
                                           Collection<ObjectId> invalidatedSnapshotIds,
                                           Collection<ObjectId> indirectlyModifiedIds) {
            if (!invalidatedSnapshotIds.isEmpty() && failCacheInvalidation.get()) {
                throw new IllegalStateException("refusing to forget rows");
            }
            super.processSnapshotChanges(postedBy, updatedSnapshots, deletedSnapshotIds, invalidatedSnapshotIds,
                    indirectlyModifiedIds);
        }
    }

    static class ListenerCountingTransaction extends CayenneTransaction {

        ListenerCountingTransaction() {
            super(NoopSQLLogger.getInstance());
        }

        long rollbackListenerCount() {
            if (listeners == null) {
                return 0;
            }
            return listeners.stream().filter(CommitRollbackListener.class::isInstance).count();
        }
    }

    // decorates the transaction's connection to see whether it is rolled back
    static class ConnectionRecorder implements TransactionListener {

        private Connection connection;
        private boolean rolledBack;

        @Override
        public Connection decorateConnection(Transaction tx, Connection connection) {
            this.connection = connection;
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("rollback")) {
                            rolledBack = true;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        void assertRolledBack() {
            assertTrue(rolledBack, "the transaction's connection must be rolled back");
        }

        // a failing run may release the connection without a rollback, still holding its locks
        void rollBackIfLeaked() {
            if (connection != null && !rolledBack) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }

        @Override
        public void willCommit(Transaction tx) {
        }

        @Override
        public void willRollback(Transaction tx) {
        }

        @Override
        public void willAddConnection(Transaction tx, String connectionName, Connection connection) {
        }
    }
}
