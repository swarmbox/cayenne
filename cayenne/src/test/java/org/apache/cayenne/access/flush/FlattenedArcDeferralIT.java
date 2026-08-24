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
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.DataContext;
import org.apache.cayenne.access.DataContextObjectStore;
import org.apache.cayenne.exp.DbPathExp;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.ObjRelationship;
import org.apache.cayenne.reflect.ArcProperty;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.testdo.inheritance_vertical.IvSub1Sub1;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When one side of a flattened arc may stand down in favour of another walk, and when it may not.
 * <p>
 * {@code shouldSkipFlattenedOp} lets an arc skip its flattened write on the understanding that a second walk is
 * coming to write the same foreign key. What it reads as evidence of that second walk has moved: from whether
 * the mapping declares an inverse, to whether the arc's reverse was declared by the flush in hand. Both
 * readings are wrong in the same direction when the routes standing against the target are ones an earlier
 * walk left behind rather than ones a walk still to come will write, and the flattened foreign key is then
 * silently never written at all.
 * <p>
 * The cases divide by what the target's flattened routes are made of, because that is the variable the
 * predicate cannot see: routes an earlier flush's derived flip stamped onto the target, routes the target's own
 * attribute walk recorded for itself, and flattened attributes the target sets in the flush in hand each reach
 * the predicate differently. {@link #twoSidedArcBetweenTwoNewObjectsWritesItsForeignKey} is the antisymmetry
 * guard pointing the other way -- two {@code NEW} objects joined by a two-sided arc, where both ends deferring
 * to each other would drop the write just as silently.
 */
public class FlattenedArcDeferralIT {

    /** Position of {@code OTHER3_ID} in the {@code IV_IMPL} column list declared in {@link #setUp()}. */
    private static final int OTHER3_ID_COLUMN = 3;

    /** Entity name the derived flip mints an intermediate {@code IV_IMPL} id under. */
    private static final String DB_IMPL_ENTITY = DbPathExp.DB_PREFIX + "IV_IMPL";

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    TableHelper ivRootTable;

    TableHelper ivSub1Table;

    TableHelper ivSub1Sub1Table;

    TableHelper ivOtherTable;

    TableHelper ivBaseTable;

    TableHelper ivImplTable;

    @BeforeEach
    public void setUp() {
        ivRootTable = env.table("IV_ROOT").setColumns("ID", "DISCRIMINATOR", "NAME")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.VARCHAR);
        ivSub1Table = env.table("IV_SUB1").setColumns("ID", "SUB1_NAME", "SUB1_PRICE", "SUB2_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.DOUBLE, Types.INTEGER);
        ivSub1Sub1Table = env.table("IV_SUB1_SUB1").setColumns("ID", "SUB1_SUB1_NAME", "SUB1_SUB1_PRICE")
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
        // IV_OTHER and IV_IMPL reference each other, so no ordering of deletes alone is valid --
        // break the cycle first, the way CircularDependencyIT does
        ivOtherTable.update().set("IMPL_ID", null, Types.INTEGER).execute();
        ivImplTable.deleteAll();
        ivOtherTable.deleteAll();
        ivBaseTable.deleteAll();
        ivSub1Sub1Table.deleteAll();
        ivSub1Table.deleteAll();
        ivRootTable.deleteAll();
    }

    /**
     * An arc created and immediately cancelled in one flush must not leave the foreign key behind.
     * <p>
     * The target is {@code NEW} in the same flush and sets <strong>no</strong> attributes of its own, so the only
     * routes standing against it are the ones the holder's own walk marked on its behalf. Read as evidence that
     * the target's own walk was coming, they suppressed the cancelling walk and the foreign key set by the
     * creating walk survived. Membership is what refuses that inference: {@code IvOther.sub1Sub1} declares no
     * inverse, so no reverse event can be raised and there is no second walk to defer to.
     * <p>
     * {@code ObjectDiff}'s diff-level cancellation pre-empts this scenario, dropping the pair before the flush
     * sees either event, so the deferral is not what this case measures -- the two-commit cases above do that.
     * It stands as the guard on the outcome should the cancellation ever stop seeing the pair.
     * <p>
     * Asserts only the foreign key, deliberately <strong>not</strong> row counts: whether a cancelled arc should
     * leave the key-only intermediate rows at all is a separate question, which this case does not settle.
     */
    @Test
    public void cancelledArcLeavesNoForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 target = newSub1Sub1WithPermanentId(context, 1);

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(target);
        other.setSub1Sub1(null);

        context.commitChanges();

        Object[] otherRow = rowWithId(ivOtherTable, Cayenne.intPKForObject(other));
        assertNotNull(otherRow, "precondition: the holder's row was written");
        assertNull(otherRow[2], "a cancelled arc must not leave its foreign key behind");
    }

    /**
     * Clearing the arc in a <em>second</em> commit must null the foreign key. Commit 1's routes have reached the
     * {@code DataContextObjectStore} by the time commit 2's predicate reads them, so this case exercises the promoted map
     * rather than the flush-local one.
     * <p>
     * The target was persisted in commit 1 by <em>this</em> arc's own derived flip and sets no attributes of its
     * own, so every route standing against it was recorded on its behalf.
     */
    @Test
    public void clearingTheArcInASecondCommitNullsTheForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 target = newSub1Sub1WithPermanentId(context, 1);

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(target);
        context.commitChanges();

        int otherId = Cayenne.intPKForObject(other);
        assertEquals(1, ((Number) rowWithId(ivOtherTable, otherId)[2]).intValue(),
                "precondition: commit 1 wrote the foreign key");

        other.setSub1Sub1(null);
        context.commitChanges();

        assertNull(rowWithId(ivOtherTable, otherId)[2], "clearing the arc must null the foreign key");
    }

    /**
     * Reassigning to an already-persisted target must move the foreign key. Marking the derived row on its
     * owner leaves a route standing against the target, and a deferral reading route presence would take its
     * own bookkeeping for evidence that the target's walk is coming.
     * <p>
     * The target was persisted in commit 1 by a <em>second</em> {@code IvOther}'s derived flip and sets no
     * attributes of its own. What keeps this reassignment walking is membership: {@code IvOther.sub1Sub1}
     * declares no inverse, so no reverse event can be raised and no other walk could write this key.
     */
    @Test
    public void reassigningToATargetPersistedByAnotherArcsDerivedFlipMovesTheForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 firstTarget = newSub1Sub1WithPermanentId(context, 1);
        IvSub1Sub1 secondTarget = newSub1Sub1WithPermanentId(context, 2);

        IvOther holderA = context.newObject(IvOther.class);
        holderA.setName("holder-a");
        holderA.setSub1Sub1(firstTarget);

        IvOther holderB = context.newObject(IvOther.class);
        holderB.setName("holder-b");
        holderB.setSub1Sub1(secondTarget);

        context.commitChanges();

        int holderAId = Cayenne.intPKForObject(holderA);
        assertEquals(Cayenne.intPKForObject(firstTarget), ((Number) rowWithId(ivOtherTable, holderAId)[2]).intValue(),
                "precondition: commit 1 pointed holder A at the first target");

        holderA.setSub1Sub1(secondTarget);
        context.commitChanges();

        assertEquals(Cayenne.intPKForObject(secondTarget), ((Number) rowWithId(ivOtherTable, holderAId)[2]).intValue(),
                "reassigning must move the foreign key to the new target");
    }

    /**
     * Reassignment to a target that sets flattened attributes <em>of its own</em>. That is the whole difference
     * from {@link #reassigningToATargetPersistedByAnotherArcsDerivedFlipMovesTheForeignKey}: here the target's
     * routes come from its own attribute walk rather than from another object marking a row on its behalf.
     * <p>
     * The deferral therefore reaches the membership query, which asks what both cases here turn on: whether any
     * other walk could write this key instead. {@code IvOther.sub1Sub1} declares no inverse, so no reverse event
     * can be raised, this walk is the only one there will ever be, and it must not stand down.
     */
    @Test
    public void reassigningToATargetWithItsOwnFlattenedAttributesMovesTheForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        assertNull(context.getEntityResolver().getObjEntity("IvOther").getRelationship("sub1Sub1")
                        .getReverseRelationship(),
                "precondition: IvOther.sub1Sub1 has no mapped inverse, so no walk but this one can write the key");
        IvSub1Sub1 firstTarget = newSub1Sub1WithPermanentId(context, 1);

        IvSub1Sub1 secondTarget = newSub1Sub1WithPermanentId(context, 2);
        secondTarget.setSub1Name("second-sub1");
        secondTarget.setSub1Sub1Name("second-sub1-sub1");

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(firstTarget);
        context.commitChanges();

        int otherId = Cayenne.intPKForObject(other);
        assertEquals(Cayenne.intPKForObject(firstTarget),
                ((Number) rowWithId(ivOtherTable, otherId)[2]).intValue(),
                "precondition: commit 1 wrote the first target's id");

        other.setSub1Sub1(secondTarget);
        context.commitChanges();

        assertEquals(Cayenne.intPKForObject(secondTarget), ((Number) rowWithId(ivOtherTable, otherId)[2]).intValue(),
                "reassigning must move the foreign key even when the target has flattened attributes of its own");
    }

    /**
     * The same conjunct with no reassignment involved: a single commit that points a new {@code IvOther} at a
     * {@code NEW} permanent-id target which sets flattened attributes of its own. The target's own walk
     * supplies the target conjunct, the source has no routes, and only membership stops the arc being handed
     * to a walk that never comes.
     * <p>
     * The row counts are as load-bearing as the foreign key. Un-suppressing this walk makes it and the target's
     * own flattened-attribute walk both reach {@code IV_SUB1_SUB1} for one logical row from different diffs; the
     * two operations must converge into one rather than fabricate a second intermediate row, so a fix that
     * writes the key by inventing rows fails here even though the key itself would read back correctly.
     */
    @Test
    public void firstCommitWritesTheForeignKeyToATargetWithItsOwnFlattenedAttributes() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        assertNull(context.getEntityResolver().getObjEntity("IvOther").getRelationship("sub1Sub1")
                        .getReverseRelationship(),
                "precondition: IvOther.sub1Sub1 has no mapped inverse, so no walk but this one can write the key");
        IvSub1Sub1 target = newSub1Sub1WithPermanentId(context, 1);
        target.setSub1Name("sub1");
        target.setSub1Sub1Name("sub1-sub1");

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(target);

        context.commitChanges();

        assertEquals(1, ((Number) rowWithId(ivOtherTable, Cayenne.intPKForObject(other))[2]).intValue(),
                "a first commit must write the foreign key to a target that has flattened attributes of its own");

        assertEquals(1, ivRootTable.getRowCount(), "the target's IV_ROOT row must be present exactly once");
        assertEquals(1, ivSub1Table.getRowCount(),
                "its IV_SUB1 row exactly once, and no fabricated second row");
        assertEquals(1, ivSub1Sub1Table.getRowCount(),
                "its IV_SUB1_SUB1 row exactly once, and no fabricated second row");
        assertEquals("sub1", rowWithId(ivSub1Table, 1)[1],
                "and IV_SUB1.SUB1_NAME must still hold what the target set");
        assertEquals("sub1-sub1", rowWithId(ivSub1Sub1Table, 1)[1],
                "and IV_SUB1_SUB1.SUB1_SUB1_NAME must still hold what the target set");
    }

    /**
     * An arc target whose flattened routes are <em>exclusively</em> ones an earlier flush's own derived flip
     * stamped onto it, followed in a later commit by a one-sided arc pointing at it.
     * <p>
     * Commit 1 sets {@code IvOther.impl} ({@code impl.base}). That walk derives the {@code IV_IMPL} row of a
     * {@code NEW} {@code IvImpl} registered under a permanent id and records it against the impl through
     * {@code markDerivedRowOnOwner}. The impl sets no flattened attribute of its own, so once the marks are
     * promoted to the {@link DataContextObjectStore} every route it holds is one this flush invented on its behalf -- and
     * {@code OTHER3_ID}, which no arc has touched, is still null.
     * <p>
     * Commit 2 then records {@code implsWithInverse} from a second holder with {@code setReverse} false, a
     * one-sided arc through public API. Both route conjuncts hold -- the holder carries no routes, the impl
     * carries routes -- and {@code implsWithInverse} declares {@code IvImpl.other3} as its inverse, so a
     * predicate resting on route presence and the mapping stands down in favour of a walk that the absent
     * reverse guarantees will never run. Membership sees that the impl's diff never declared the arc.
     */
    @Test
    public void oneSidedArcOntoATargetHoldingOnlyDerivedMarksWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvOther markingHolder = newOtherWithPermanentId(context, 1);
        markingHolder.setName("marking-holder");
        IvImpl impl = newImplWithPermanentId(context, 3);
        impl.setName("impl");
        markingHolder.setImpl(impl);

        context.commitChanges();

        Object[] afterFirstCommit = implRow(3);
        assertNotNull(afterFirstCommit, "precondition: the derived flip inserted the impl's sub-table row");
        assertNull(afterFirstCommit[OTHER3_ID_COLUMN],
                "precondition: commit 1 sets no arc reaching OTHER3_ID, so it starts unwritten");
        assertEquals(Map.of(CayennePath.of("impl"), ObjectId.of(DB_IMPL_ENTITY, "ID", 3)),
                ((DataContext) context).getObjectStore().getFlattenedPathIdMap(impl.getObjectId()),
                "precondition: the impl's only route is the permanent id the derived flip stamped on it -- a "
                        + "route it had recorded for itself would carry the temporary id it would have invented");

        IvOther oneSidedHolder = newOtherWithPermanentId(context, 2);
        oneSidedHolder.setName("one-sided-holder");
        oneSidedHolder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, false);

        context.commitChanges();

        Object[] afterSecondCommit = implRow(3);
        assertNotNull(afterSecondCommit, "the impl's sub-table row must still be there");
        assertNotNull(afterSecondCommit[OTHER3_ID_COLUMN],
                "the one-sided arc must write the flattened foreign key rather than defer to a walk that the "
                        + "absent reverse guarantees will never run");
        assertEquals(Cayenne.intPKForObject(oneSidedHolder),
                ((Number) afterSecondCommit[OTHER3_ID_COLUMN]).intValue(),
                "and must write the holder the arc named");
    }

    /**
     * The other half of the same conjunct: an arc target whose flattened routes are exclusively ones it recorded
     * for <em>itself</em>, rather than ones a derived flip stamped on it.
     * <p>
     * Commit 1 records no arc at all. Setting the flattened attribute {@code attr1} ({@code impl.ATTR1}) walks
     * the one-segment path {@code impl} with a null final target, so no id can be derived and none is tracked:
     * the walk invents a temporary id for the sub-table row and marks it against the walk source, which is the
     * impl itself. {@code OTHER3_ID}, which no arc has touched, is null.
     * <p>
     * Commit 2 is byte-for-byte the same one-sided arc as
     * {@link #oneSidedArcOntoATargetHoldingOnlyDerivedMarksWritesItsForeignKey}. Provenance is the only variable
     * between the two cases, which is what makes the pair decide whether the deferral's reach is the fork-only
     * derived subclass or both halves of the union map.
     */
    @Test
    public void oneSidedArcOntoATargetHoldingOnlyItsOwnMarksWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvImpl impl = newImplWithPermanentId(context, 1);
        impl.setName("impl");
        impl.setAttr1("attr");

        context.commitChanges();

        Object[] afterFirstCommit = implRow(1);
        assertNotNull(afterFirstCommit, "precondition: the impl's own attribute walk inserted its sub-table row");
        assertNull(afterFirstCommit[OTHER3_ID_COLUMN],
                "precondition: commit 1 records no arc at all, so OTHER3_ID starts unwritten");

        Map<CayennePath, ObjectId> routes = ((DataContext) context).getObjectStore()
                .getFlattenedPathIdMap(impl.getObjectId());
        assertEquals(Set.of(CayennePath.of("impl")), routes.keySet(),
                "precondition: the impl holds exactly one flattened route");
        ObjectId route = routes.get(CayennePath.of("impl"));
        assertEquals(List.of(DB_IMPL_ENTITY, true, Map.of("ID", 1)),
                List.of(route.getEntityName(), route.isTemporary(), route.getIdSnapshot()),
                "precondition: that route is the temporary id the impl's own walk invented for itself, reading "
                        + "back as the row key through the replacement the insert attached -- a derived flip "
                        + "stamps a permanent id, so this pins every route here as own rather than derived");

        IvOther oneSidedHolder = newOtherWithPermanentId(context, 2);
        oneSidedHolder.setName("one-sided-holder");
        oneSidedHolder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, false);

        context.commitChanges();

        Object[] afterSecondCommit = implRow(1);
        assertNotNull(afterSecondCommit, "the impl's sub-table row must still be there");
        assertNotNull(afterSecondCommit[OTHER3_ID_COLUMN],
                "the one-sided arc must write the flattened foreign key rather than defer to a walk that the "
                        + "absent reverse guarantees will never run");
        assertEquals(Cayenne.intPKForObject(oneSidedHolder),
                ((Number) afterSecondCommit[OTHER3_ID_COLUMN]).intValue(),
                "and must write the holder the arc named");
    }

    /**
     * The own-marks half again, with the caller-supplied permanent id taken away. Commit 1 derives nothing here
     * -- it records no arc at all -- so nothing in that flush needs a non-temporary target, and an ordinary
     * {@code newObject} on a database-generated key should mark the same route at the same place. Whether it
     * does decides how narrow this half's preconditions are: a target minted under a caller-supplied permanent
     * id is an unusual shape, while a vertically inherited object with a flattened attribute is an ordinary one.
     * <p>
     * A generated key puts two temporary ids in play rather than one: the impl's own id, temporary until commit 1
     * replaces it, and the id the attribute walk invents for the sub-table row, which stays temporary. They are
     * separate instances with separate lifecycles, and it is the second whose temporariness pins the route as own
     * rather than derived, since a derived flip stamps a permanent id there.
     */
    @Test
    public void oneSidedArcOntoAGeneratedKeyTargetHoldingOnlyItsOwnMarksWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvImpl impl = context.newObject(IvImpl.class);
        impl.setName("impl");
        impl.setAttr1("attr");
        assertTrue(impl.getObjectId().isTemporary(),
                "precondition: the impl is on an ordinary temporary id, not a caller-supplied permanent one");

        context.commitChanges();

        int implId = Cayenne.intPKForObject(impl);
        Object[] afterFirstCommit = implRow(implId);
        assertNotNull(afterFirstCommit, "precondition: the impl's own attribute walk inserted its sub-table row");
        assertNull(afterFirstCommit[OTHER3_ID_COLUMN],
                "precondition: commit 1 records no arc at all, so OTHER3_ID starts unwritten");

        Map<CayennePath, ObjectId> routes = ((DataContext) context).getObjectStore()
                .getFlattenedPathIdMap(impl.getObjectId());
        assertEquals(Set.of(CayennePath.of("impl")), routes.keySet(),
                "precondition: the impl holds exactly one flattened route, tracked under the permanent id the "
                        + "insert remapped it to");
        ObjectId route = routes.get(CayennePath.of("impl"));
        assertEquals(List.of(DB_IMPL_ENTITY, false, true, Set.of("ID")),
                List.of(route.getEntityName(), impl.getObjectId().isTemporary(), route.isTemporary(),
                        route.getIdSnapshot().keySet()),
                "precondition: the impl's own id was replaced by the insert while the id its walk invented for "
                        + "the sub-table row stays temporary -- and it is that one whose temporariness pins "
                        + "every route here as own rather than derived, a derived flip stamping a permanent id");
        assertEquals(implId, ((Number) route.getIdSnapshot().get("ID")).intValue(),
                "that temporary route id nonetheless reads back as the generated row key, through the "
                        + "replacement the insert attached, which is why snapshot equality alone cannot "
                        + "discriminate the two provenances");

        IvOther oneSidedHolder = newOtherWithPermanentId(context, 2);
        oneSidedHolder.setName("one-sided-holder");
        oneSidedHolder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, false);

        context.commitChanges();

        Object[] afterSecondCommit = implRow(implId);
        assertNotNull(afterSecondCommit, "the impl's sub-table row must still be there");
        assertNotNull(afterSecondCommit[OTHER3_ID_COLUMN],
                "the one-sided arc must write the flattened foreign key rather than defer to a walk that the "
                        + "absent reverse guarantees will never run");
        assertEquals(Cayenne.intPKForObject(oneSidedHolder),
                ((Number) afterSecondCommit[OTHER3_ID_COLUMN]).intValue(),
                "and must write the holder the arc named");
    }

    /**
     * The same-flush shape: the routed target and the one-sided arc land in <em>one</em> commit rather than
     * across two, with the target registered first so that its own attribute walk has recorded its route by the
     * time the holder's arc is processed.
     * <p>
     * {@code DbRowOpFactory} keeps its flush-local markings for the whole flush -- {@code updateDiff} clears the
     * row map and not the flattened paths -- and {@code DataContextObjectStore.changes} is insertion-ordered, so the impl's
     * {@code attr1} walk marks route {@code impl} before the holder's diff is reached. The route conjuncts then
     * hold exactly as they do in the two-commit cases above, and for the same insufficient reason: the route is
     * evidence of a walk that has already run, not of one still to come.
     */
    @Test
    public void sameFlushOneSidedArcWithTheTargetRegisteredFirstWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvImpl impl = newImplWithPermanentId(context, 1);
        impl.setName("impl");
        impl.setAttr1("attr");

        IvOther oneSidedHolder = newOtherWithPermanentId(context, 2);
        oneSidedHolder.setName("one-sided-holder");
        oneSidedHolder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, false);

        context.commitChanges();

        assertSoleImplRowCarriesTheSameFlushHolder(Cayenne.intPKForObject(impl), Cayenne.intPKForObject(oneSidedHolder));
    }

    /**
     * The target-first same-flush shape with the caller-supplied permanent id taken away, which is the ordinary
     * mapping rather than an unusual one. It also moves the second axis: a {@code NEW} target on a temporary id
     * is one {@code deriveIdFromFinalTarget} refuses outright, so the walk the deferral stands down in favour of
     * cannot reach the row by derivation even when it does run.
     */
    @Test
    public void sameFlushOneSidedArcOntoAGeneratedKeyTargetRegisteredFirstWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvImpl impl = context.newObject(IvImpl.class);
        impl.setName("impl");
        impl.setAttr1("attr");
        assertTrue(impl.getObjectId().isTemporary(),
                "precondition: the impl is on an ordinary temporary id, not a caller-supplied permanent one");

        IvOther oneSidedHolder = newOtherWithPermanentId(context, 2);
        oneSidedHolder.setName("one-sided-holder");
        oneSidedHolder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, false);

        context.commitChanges();

        assertSoleImplRowCarriesTheSameFlushHolder(Cayenne.intPKForObject(impl), Cayenne.intPKForObject(oneSidedHolder));
    }

    /**
     * What the same-flush cases must end at: one sub-table row for the target, carrying the holder the one-sided
     * arc named. Row count is asserted alongside the value because the two ways this can go wrong are opposites
     * -- a deferred write leaves the column null, while a walk that reaches the row under an id the target's own
     * diff does not share writes it twice.
     */
    private void assertSoleImplRowCarriesTheSameFlushHolder(int implId, int holderId) throws SQLException {
        assertEquals(1, ivImplTable.getRowCount(),
                "the target's sub-table row must be written once, under an id its own diff shares");
        Object[] implRow = implRow(implId);
        assertNotNull(implRow, "the target's sub-table row must exist");
        assertNotNull(implRow[OTHER3_ID_COLUMN],
                "the one-sided arc must write the flattened foreign key rather than defer to a walk that the "
                        + "absent reverse guarantees will never run");
        assertEquals(holderId, ((Number) implRow[OTHER3_ID_COLUMN]).intValue(),
                "and must write the holder the arc named");
    }

    /**
     * The antisymmetry guard: two {@code NEW} objects joined by a <em>two-sided</em> arc, which is the case the
     * deferral's route conjuncts exist to protect and the one a membership-only predicate would break.
     * <p>
     * Every other case in this class asks whether the flush skips when it should walk. This one asks the
     * opposite: it pins a working shape so that tightening the predicate cannot quietly cost it.
     * <p>
     * What the two ends rely on is an <em>asymmetry</em> that only appears once the walk is under way, not a
     * standing condition that both are routeless throughout. Nothing is marked before the flush begins, so the
     * first diff processed is genuinely routeless, walks, and marks a route; by the time the second is reached
     * the first is no longer routeless and the second arc skips -- correctly, deferring to a walk that has
     * already written the key.
     * <p>
     * So the second endpoint does defer, and should. What must never happen is that <em>both</em> do. A
     * predicate that asked only "was the reverse arc declared?" would answer yes on both sides, skip the first
     * arc as well, and the write would vanish with no error. What rules that out is the pair of route conjuncts:
     * the skip requires the source routeless and the target routed, which cannot hold for the first arc
     * evaluated in a flush where nothing is marked yet.
     * <p>
     * The reverse is maintained here, so both diffs carry the arc; that lone boolean is the only difference
     * from {@link #sameFlushOneSidedArcWithTheTargetRegisteredFirstWritesItsForeignKey}'s producer.
     */
    @Test
    public void twoSidedArcBetweenTwoNewObjectsWritesItsForeignKey() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvImpl impl = newImplWithPermanentId(context, 1);
        impl.setName("impl");

        IvOther holder = newOtherWithPermanentId(context, 2);
        holder.setName("two-sided-holder");
        holder.addToManyTarget(IvOther.IMPLS_WITH_INVERSE.getName(), impl, true);

        assertEquals(holder, impl.getOther3(),
                "precondition: the reverse is maintained, so the impl's own diff carries this arc too and both "
                        + "ends are in a position to defer to the other");
        DataContextObjectStore objectStore = ((DataContext) context).getObjectStore();
        assertEquals(List.of(Map.of(), Map.of()),
                List.of(objectStore.getFlattenedPathIdMap(impl.getObjectId()),
                        objectStore.getFlattenedPathIdMap(holder.getObjectId())),
                "precondition: both endpoints are NEW in this flush and neither sets a flattened attribute, so "
                        + "no route survives from an earlier flush to break the symmetry from outside");

        context.commitChanges();

        assertEquals(1, ivImplTable.getRowCount(),
                "the target's sub-table row must be written once, under an id its own diff shares");
        Object[] implRow = implRow(1);
        assertNotNull(implRow, "the target's sub-table row must exist");
        assertNotNull(implRow[OTHER3_ID_COLUMN],
                "a two-sided arc must write the flattened foreign key from one side or the other -- both ends "
                        + "standing down for the other is the mutual deferral this case exists to catch");
        assertEquals(Cayenne.intPKForObject(holder), ((Number) implRow[OTHER3_ID_COLUMN]).intValue(),
                "and must write the holder the arc named");
    }

    /**
     * The mapping facts the deferral depends on, against the real resolver.
     * <p>
     * The deferral does not read the mapping. It asks whether the arc's reverse was <em>declared</em> by this
     * flush, which is the sufficient question a mapping-only test could only approximate. But a mapped inverse
     * is the precondition that makes a declared reverse possible at all: a reverse event can only be raised
     * through the reverse {@code ArcProperty}, and that property cannot exist without one. These facts are
     * therefore what the deferral's evidence is made of, and are worth pinning as such.
     * <p>
     * {@code ArcId} and {@code BaseArcProperty.setReverse} both go through {@code getComplimentaryReverseArc()},
     * so asserting that it agrees with {@code getReverseRelationship()} turns a Cayenne change that decoupled
     * them into a red test rather than a silent regression -- load-bearing in a second way too, since
     * membership matches on {@code ArcId.getReverseId()}, whose forward and reverse names an unmapped inverse's
     * synthetic {@code db:}-prefixed form could never satisfy. Asserting {@code sub1Sub1} has no inverse turns a
     * mapping edit that added one into a red test rather than the silent retirement of the cases above that
     * depend on it.
     */
    @Test
    public void mappedInverseIsDeclaredWhereTheDeferralExpectsIt() {
        ObjectContext context = env.runtime().newContext();

        ObjRelationship sub1Sub1 = context.getEntityResolver().getObjEntity("IvOther").getRelationship("sub1Sub1");
        assertNull(sub1Sub1.getReverseRelationship(),
                "IvOther.sub1Sub1 must have no mapped inverse, or the two enabled cases above stop testing the defect");

        ObjRelationship implsWithInverse = context.getEntityResolver()
                .getObjEntity("IvOther").getRelationship("implsWithInverse");
        ObjRelationship other3 = context.getEntityResolver().getObjEntity("IvImpl").getRelationship("other3");
        assertEquals(List.of(other3, implsWithInverse),
                List.of(implsWithInverse.getReverseRelationship(), other3.getReverseRelationship()),
                "IvOther.implsWithInverse and IvImpl.other3 must each resolve to the other");

        assertEquals(
                List.of(reverseRelationshipIsMapped(implsWithInverse), reverseRelationshipIsMapped(other3),
                        reverseRelationshipIsMapped(sub1Sub1)),
                List.of(reverseArcIsMaintained(context, "IvOther", "implsWithInverse"),
                        reverseArcIsMaintained(context, "IvImpl", "other3"),
                        reverseArcIsMaintained(context, "IvOther", "sub1Sub1")),
                "getComplimentaryReverseArc must agree with getReverseRelationship, since reverse-arc maintenance "
                        + "is what decides whether a reverse arc event can be raised for the deferral to find");
    }

    /** Whether the mapping declares an inverse of this relationship, which is what a reverse event needs to exist. */
    private static boolean reverseRelationshipIsMapped(ObjRelationship relationship) {
        return relationship.getReverseRelationship() != null;
    }

    /** Whether the runtime maintains the arc on the target's side, and so whether a reverse event can be raised. */
    private static boolean reverseArcIsMaintained(ObjectContext context, String entityName, String arcName) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor(entityName);
        return ((ArcProperty) descriptor.getProperty(arcName)).getComplimentaryReverseArc() != null;
    }

    /**
     * Registers a new vertically-inherited {@code IvSub1Sub1} under a caller-supplied permanent id, setting no
     * attributes -- so that nothing but a derived flip can record its flattened routes.
     */
    private static IvSub1Sub1 newSub1Sub1WithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvSub1Sub1");
        IvSub1Sub1 object = (IvSub1Sub1) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvSub1Sub1", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    /**
     * Registers a new {@code IvOther} under a caller-supplied permanent id, so that assertions can name its row
     * directly instead of reading a generated key back. The order operations reach the sort in is decided by
     * registration order, not by the id.
     */
    private static IvOther newOtherWithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvOther");
        IvOther object = (IvOther) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvOther", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    /**
     * Registers a new vertically-inherited {@code IvImpl} under a caller-supplied permanent id, so that its
     * rows in the {@code IvBase} hierarchy can be named directly instead of reading a generated key back.
     */
    private static IvImpl newImplWithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvImpl");
        IvImpl object = (IvImpl) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvImpl", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    private Object[] implRow(int id) throws SQLException {
        return rowWithId(ivImplTable, id);
    }

    /** Returns the row whose first selected column -- the primary key in every table used here -- equals id. */
    private static Object[] rowWithId(TableHelper table, int id) throws SQLException {
        for(Object[] row : table.selectAll()) {
            if(((Number) row[0]).intValue() == id) {
                return row;
            }
        }
        return null;
    }

}
