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

import org.apache.cayenne.Cayenne;
import org.apache.cayenne.CayenneRuntimeException;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.ObjectStore;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.access.ObjectStoreGraphDiff;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOpFactory;
import org.apache.cayenne.access.flush.operation.GraphBasedDbRowOpSorter;
import org.apache.cayenne.di.Inject;
import org.apache.cayenne.graph.CompoundDiff;
import org.apache.cayenne.query.Query;
import org.apache.cayenne.query.SelectById;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code ObjectStore.trackedFlattenedPaths} caches which vertical-inheritance child rows exist, and Cayenne
 * reads it to choose between inserting and updating one. These tests cover the ways it can come to name a row
 * that is not there, after which the child row's INSERT is downgraded to an UPDATE matching nothing: the parent
 * row is written, the child row is silently absent, and no exception is raised.
 * <p>
 * Two things keep it right. {@code DbRowOpFactory} stages the flush's markings, so a flush that fails before
 * its queries run records nothing at all; and {@code ObjectResolver.resolveAdditionalIds} forgets a path when a
 * fetch shows no child row, so re-reading the database repairs the cache. The second is the load-bearing one,
 * covering every source of staleness at one point rather than each at its own.
 * <p>
 * {@code insertFlattenedAfterChildRowRemovedOutsideTheContext} is the case that needs no failure at all and is
 * therefore the root of the problem. {@code insertFlattenedAfterFlushFailedPastPromotion} covers a flush failing
 * between {@code promoteFlattenedPaths()} and {@code postprocess()} in
 * {@link DefaultDataDomainFlushAction#flush}, where the queries have run and the paths are already in the
 * {@link ObjectStore} but the objects are not yet COMMITTED; it passes because the rollback leaves them HOLLOW
 * and the refetch corrects the cache. {@code ReplacementIdVisitor.visitInsert} throwing "PK for flattened path
 * ... is not set during insert" is the real occupant of that window, which the test injects directly rather than
 * contriving.
 * <p>
 * A failure at COMMIT - a {@code DEFERRABLE INITIALLY DEFERRED} constraint - is deliberately not covered here.
 * {@code postprocess} has by then flipped every object to COMMITTED, so nothing refetches and no repair on the
 * read path can reach it. That window corrupts object state and cached snapshots beyond the flattened paths, so
 * it wants its own fix rather than a further repair confined to this cache.
 */
public class FlattenedPathStalenessIT {

    /** Fails a flush after its queries have run and its flattened paths are in the {@link ObjectStore}. */
    private static final AtomicBoolean failAfterPromotion = new AtomicBoolean();

    /** Fails a flush before any of its queries run, and so before anything could be promoted. */
    private static final AtomicBoolean failDuringExecution = new AtomicBoolean();

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
            .withExtraModules(binder -> {
                binder.bind(DbRowOpSorter.class).to(GraphBasedDbRowOpSorter.class);
                binder.bind(DataDomainFlushActionFactory.class).to(InjectedFailureFlushActionFactory.class);
            });

    TableHelper ivAbstractTable;

    TableHelper ivConcreteTable;

    TableHelper ivOtherTable;

    TableHelper ivBaseTable;

    TableHelper ivImplTable;

    @BeforeEach
    public void setUp() {
        failAfterPromotion.set(false);
        failDuringExecution.set(false);
        ivAbstractTable = env.table("IV_ABSTRACT").setColumns("ID", "PARENT_ID", "TYPE")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.CHAR);
        ivConcreteTable = env.table("IV_CONCRETE").setColumns("ID", "NAME", "RELATED_ABSTRACT_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
        ivOtherTable = env.table("IV_OTHER").setColumns("ID", "NAME", "SUB1_SUB1_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
        ivBaseTable = env.table("IV_BASE").setColumns("ID", "NAME", "TYPE")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.CHAR);
        ivImplTable = env.table("IV_IMPL").setColumns("ID", "ATTR1", "OTHER1_ID", "OTHER3_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER);
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        failAfterPromotion.set(false);
        failDuringExecution.set(false);
        ivConcreteTable.deleteAll();
        ivAbstractTable.deleteAll();
        // IV_OTHER and IV_IMPL reference each other, so no ordering of deletes alone is valid --
        // break the cycle first, the way CircularDependencyIT does
        ivOtherTable.update().set("IMPL_ID", null, Types.INTEGER).execute();
        ivImplTable.deleteAll();
        ivOtherTable.deleteAll();
        ivBaseTable.deleteAll();
    }

    /**
     * The parent row exists with no child row, so writing the flattened attribute has to insert one. The first
     * commit gets as far as recording the child row's id and is then rolled back; the second must still insert.
     */
    @Test
    public void insertFlattenedAfterFlushFailedPastPromotion() throws SQLException {
        ivAbstractTable.insert(1, null, "S");

        ObjectContext context = env.runtime().newContext();
        IvConcrete object = SelectById.query(IvConcrete.class, 1).selectOne(context);
        assertNotNull(object);
        assertEquals(0, ivConcreteTable.getRowCount(), "precondition: the child row does not exist yet");

        object.setName("first");
        failAfterPromotion.set(true);
        assertThrows(CayenneRuntimeException.class, context::commitChanges);
        assertEquals(0, ivConcreteTable.getRowCount(), "the failed flush must leave no child row behind");

        context.rollbackChangesLocally();

        object.setName("second");
        context.commitChanges();

        assertEquals(1, ivConcreteTable.getRowCount(),
                "the retry must insert the child row rather than update one that is not there");

        ObjectContext cleanContext = env.runtime().newContext();
        IvConcrete refetched = SelectById.query(IvConcrete.class, 1).selectOne(cleanContext);
        assertNotNull(refetched);
        assertEquals("second", refetched.getName(), "the flattened attribute must be readable back");
    }

    /**
     * The counterpart that must keep working: a rollback also discards paths for child rows that really do
     * exist, and those have to come back. They do, because the rollback leaves the object HOLLOW and
     * {@code ObjectResolver.objectFromDataRow} re-runs {@code resolveAdditionalIds} on the refault. Were the
     * marking not restored, this commit would try to insert a second child row for the same primary key.
     */
    @Test
    public void updateFlattenedAfterRollbackWhenChildRowExists() throws SQLException {
        ivAbstractTable.insert(1, null, "S");
        ivConcreteTable.insert(1, "original", null);

        ObjectContext context = env.runtime().newContext();
        IvConcrete object = SelectById.query(IvConcrete.class, 1).selectOne(context);
        assertNotNull(object);

        object.setName("rolled back");
        context.rollbackChangesLocally();

        object.setName("updated");
        context.commitChanges();

        assertEquals(1, ivConcreteTable.getRowCount(), "the existing child row must be updated, not duplicated");

        ObjectContext cleanContext = env.runtime().newContext();
        IvConcrete refetched = SelectById.query(IvConcrete.class, 1).selectOne(cleanContext);
        assertNotNull(refetched);
        assertEquals("updated", refetched.getName(), "the flattened attribute must be readable back");
    }

    /**
     * The staleness source that needs no failure at all. Something outside this context removes the child row,
     * which is what a concurrent context, a raw SQL fix or another user's request all look like from here. On
     * the refetch {@code ObjectResolver.resolveAdditionalIds} finds no child row; were it only ever to add to
     * the cache, the entry would be left naming a row that is gone with nothing to correct it. That is what
     * makes this the root of the problem rather than one more way to trip over it.
     */
    @Test
    public void insertFlattenedAfterChildRowRemovedOutsideTheContext() throws SQLException {
        ivAbstractTable.insert(1, null, "S");
        ivConcreteTable.insert(1, "original", null);

        ObjectContext context = env.runtime().newContext();
        IvConcrete object = SelectById.query(IvConcrete.class, 1).selectOne(context);
        assertNotNull(object);
        assertEquals("original", object.getName(), "the child row is tracked as existing at this point");

        // behind Cayenne's back, exactly as another context or a manual fix would
        ivConcreteTable.deleteAll();

        // force the object to be read again, so the cache has every chance to correct itself
        context.invalidateObjects(object);
        assertNull(object.getName(), "the child row is gone, so the flattened attribute reads back null");

        object.setName("rewritten");
        context.commitChanges();

        assertEquals(1, ivConcreteTable.getRowCount(),
                "the child row must be inserted again rather than updated where it no longer exists");

        ObjectContext cleanContext = env.runtime().newContext();
        IvConcrete refetched = SelectById.query(IvConcrete.class, 1).selectOne(cleanContext);
        assertNotNull(refetched);
        assertEquals("rewritten", refetched.getName(), "the flattened attribute must be readable back");
    }

    /**
     * The staging half of the fix, at {@code ArcValuesCreationHandler}'s flattened-attribute writer: a
     * flattened attribute written to a fetched object whose child row does not exist reaches
     * {@code processFlattenedPath} with a null final target, finds no id for the path, and mints one for the
     * row it is about to insert.
     * <p>
     * The flush then dies before any of its queries run, so that row is never written. The retry happens in the
     * same context with <em>no rollback</em>: the object stays MODIFIED and is never refaulted, so the read
     * path never refetches it and the {@code ObjectResolver} repair cannot reach this. Whether the second
     * commit inserts the child row therefore turns on the first flush having recorded nothing, which is what
     * staging the marking on {@code DbRowOpFactory} is for.
     */
    @Test
    public void insertFlattenedAfterFlushFailedBeforeItsQueriesRan() throws SQLException {
        ivAbstractTable.insert(1, null, "S");

        ObjectContext context = env.runtime().newContext();
        IvConcrete object = SelectById.query(IvConcrete.class, 1).selectOne(context);
        assertNotNull(object);
        assertEquals(0, ivConcreteTable.getRowCount(), "precondition: the child row does not exist yet");

        object.setName("first");
        failDuringExecution.set(true);
        assertThrows(CayenneRuntimeException.class, context::commitChanges);
        assertEquals(0, ivConcreteTable.getRowCount(), "the failed flush must leave no child row behind");

        // deliberately no rollback: the object stays MODIFIED and is never refaulted
        object.setName("second");
        context.commitChanges();

        assertEquals(1, ivConcreteTable.getRowCount(),
                "the retry must insert the child row rather than update one that is not there");

        ObjectContext cleanContext = env.runtime().newContext();
        IvConcrete refetched = SelectById.query(IvConcrete.class, 1).selectOne(cleanContext);
        assertNotNull(refetched);
        assertEquals("second", refetched.getName(), "the flattened attribute must be readable back");
    }

    /**
     * The same staging half at {@code ArcValuesCreationHandler}'s other writer, the one CAY-2890 added:
     * {@code markDerivedRowOnOwner} records the {@code IV_IMPL} row a derived flip is about to insert against
     * the impl that owns it, so that later work in the same context knows the row exists.
     * <p>
     * Here the flush that would have inserted it dies before any query runs, so it does not exist. The retry
     * again takes no rollback, leaving both objects NEW and unfetched, so nothing on the read path can correct
     * a marking the first flush left behind.
     */
    @Test
    public void insertDerivedRowAfterFlushFailedBeforeItsQueriesRan() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvOther holder = newOtherWithPermanentId(context, 1);
        IvImpl impl = newImplWithPermanentId(context, 2);
        holder.setName("holder");
        impl.setName("impl");
        holder.setImpl(impl);

        failDuringExecution.set(true);
        assertThrows(CayenneRuntimeException.class, context::commitChanges);
        assertEquals(0, ivImplTable.getRowCount(), "the failed flush must leave no sub-table row behind");

        // deliberately no rollback: both objects stay NEW and are never fetched
        context.commitChanges();

        assertEquals(1, ivImplTable.getRowCount(),
                "the retry must insert the IV_IMPL row rather than update one that was never written");

        ObjectContext cleanContext = env.runtime().newContext();
        IvOther refetchedHolder = SelectById.query(IvOther.class, 1).selectOne(cleanContext);
        assertNotNull(refetchedHolder, "the holder must be readable back");
        assertNotNull(refetchedHolder.getImpl(), "and the arc it holds must resolve to the impl");
        assertEquals(2, Cayenne.intPKForObject(refetchedHolder.getImpl()),
                "which is the impl whose sub-table row the derived flip had to insert");
    }

    /**
     * Registers a new {@code IvOther} under a caller-supplied permanent id, so that assertions can name its row
     * directly instead of reading a generated key back.
     */
    private static IvOther newOtherWithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvOther");
        IvOther object = (IvOther) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvOther", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    /**
     * Registers a new vertically-inherited {@code IvImpl} under a caller-supplied permanent id, so that the
     * derived flip has a real key to derive its {@code IV_IMPL} row from.
     */
    private static IvImpl newImplWithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvImpl");
        IvImpl object = (IvImpl) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvImpl", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    public static class InjectedFailureFlushActionFactory implements DataDomainFlushActionFactory {

        @Inject
        private DbRowOpSorter operationSorter;

        @Inject
        private DeleteDbRowOpFactory deleteDbRowOpFactory;

        @Override
        public DataDomainFlushAction createFlushAction(DataDomain dataDomain) {
            return new InjectedFailureFlushAction(dataDomain, operationSorter, deleteDbRowOpFactory);
        }
    }

    /**
     * Identical to {@link DefaultDataDomainFlushAction} except that one flush can be made to fail at either of
     * the two points that matter here, on both sides of promotion.
     * <p>
     * {@code failDuringExecution} throws at the head of {@code executeQueries}, so the flush dies before any
     * SQL runs and therefore before {@code createReplacementIds} and {@code promoteFlattenedPaths}. It stands
     * for every way execution can fail - a constraint, a dead connection, a driver error - and is the injection
     * point that leaves the operations built but nothing written.
     * <p>
     * {@code failAfterPromotion} throws in {@code postprocess}, once the queries have run and the flattened
     * paths are already in the {@link ObjectStore}, but before the objects are flipped to COMMITTED.
     */
    static class InjectedFailureFlushAction extends DefaultDataDomainFlushAction {

        InjectedFailureFlushAction(DataDomain dataDomain, DbRowOpSorter dbRowOpSorter,
                                   DeleteDbRowOpFactory deleteDbRowOpFactory) {
            super(dataDomain, dbRowOpSorter, deleteDbRowOpFactory);
        }

        @Override
        protected void executeQueries(List<? extends Query> queries) {
            if (failDuringExecution.compareAndSet(true, false)) {
                throw new CayenneRuntimeException("injected failure before any of the flush's queries ran");
            }
            super.executeQueries(queries);
        }

        @Override
        protected void postprocess(DataContext context, ObjectStoreGraphDiff changes, CompoundDiff afterCommitDiff,
                                   List<DbRowOp> dbRowOps) {
            if (failAfterPromotion.compareAndSet(true, false)) {
                throw new CayenneRuntimeException("injected failure after the flattened paths were promoted");
            }
            super.postprocess(context, changes, afterCommitDiff, dbRowOps);
        }
    }
}
