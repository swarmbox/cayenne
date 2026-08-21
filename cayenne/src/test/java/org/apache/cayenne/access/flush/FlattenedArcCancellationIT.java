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
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A flattened to-one arc set and cleared inside one flush must leave exactly what never setting it would leave.
 * <p>
 * The creating walk and the cancelling walk both reach the intermediate rows of {@code IvOther.sub1Sub1}
 * ({@code sub1Sub1.master.master}). Pairing the two events off at the diff level, before the flush sees either,
 * is what stops the creating walk's foreign key and its key-only intermediate rows surviving a cancellation the
 * database is never told about.
 * <p>
 * Each case compares a whole {@code FlushFootprint} -- row counts across the target's three-table hierarchy,
 * the holder's foreign key, the statement count and the flattened-path tracking sizes -- against a control
 * commit, rather than asserting field by field, so that a fix repairing one observable while breaking another
 * cannot pass and so that a failure prints both sides in full. The guards pin what the pairing must not eat: an
 * odd number of transitions to the same target, a reassignment to a second target, and a cancelled pair on
 * endpoints that are both already persisted.
 */
public class FlattenedArcCancellationIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    TableHelper ivRootTable;

    TableHelper ivSub1Table;

    TableHelper ivSub1Sub1Table;

    TableHelper ivOtherTable;

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
    }

    @AfterEach
    public void cleanUp() throws SQLException {
        // IV_OTHER points at IV_SUB1_SUB1, so the hierarchy unwinds after it
        ivOtherTable.deleteAll();
        ivSub1Sub1Table.deleteAll();
        ivSub1Table.deleteAll();
        ivRootTable.deleteAll();
    }

    /**
     * An arc created and removed inside one flush must leave exactly what never creating it would leave.
     * Written against {@code IvOther.sub1Sub1} rather than {@code IvConcrete.relatedAbstract} for two
     * independent reasons, either of which alone would make the case pass on the other mapping: on
     * the reverse arc {@code ObjectDiff.addDiff} cancels the flattened to-many create/delete pair before the
     * flush sees it, and on the forward arc {@code relatedAbstract} joins the non-PK
     * {@code RELATED_ABSTRACT_ID} to {@code ID}, so {@code resolvePkMapping} returns empty and derivation never
     * happens.
     * <p>
     * The whole footprint is compared as one record rather than field by field, so that a fix repairing one
     * observable and breaking another cannot pass, and so that a failure prints both sides in full instead of
     * the first differing array index.
     */
    @Test
    public void arcCreatedAndRemovedInOneFlushLeavesWhatNoArcWouldLeave() throws SQLException {
        FlushFootprint withCancelledArc = commitNewSub1Sub1(true, false);
        deleteRowsBetweenFootprintRuns();
        FlushFootprint control = commitNewSub1Sub1();

        assertEquals(control, withCancelledArc,
                "setting and clearing an arc in one flush must leave the same rows, the same foreign key, the "
                        + "same number of statements and the same flattened-path tracking as never setting it");
    }

    /**
     * Guard: reassigning the arc from one {@code NEW} permanent-id target to another within one flush must still
     * write the <em>second</em> target's rows and point the foreign key at it. Pinned because the tempting fix
     * -- withdrawing the derived operations on the cancelling walk -- strikes the surviving target instead, the
     * two walks having already been rewritten to the same id by the arc-snapshot substitution.
     */
    @Test
    public void reassigningInOneFlushWritesTheSecondTargetsRows() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 firstTarget = newSub1Sub1WithPermanentId(context, 1);
        IvSub1Sub1 secondTarget = newSub1Sub1WithPermanentId(context, 2);

        IvOther holder = newOtherWithPermanentId(context, 3);
        holder.setName("holder");
        holder.setSub1Sub1(firstTarget);
        holder.setSub1Sub1(secondTarget);

        context.commitChanges();

        assertEquals(Cayenne.intPKForObject(secondTarget),
                ((Number) rowWithId(ivOtherTable, Cayenne.intPKForObject(holder))[2]).intValue(),
                "the foreign key must point at the target the arc holds at commit");
        assertNotNull(rowWithId(ivSub1Table, 2), "the surviving target's IV_SUB1 row is needed");
        assertNotNull(rowWithId(ivSub1Sub1Table, 2),
                "and so is its IV_SUB1_SUB1 row, which the foreign key references");
        assertNull(rowWithId(ivSub1Table, 1), "the abandoned target needs no IV_SUB1 row");
        assertNull(rowWithId(ivSub1Sub1Table, 1), "nor an IV_SUB1_SUB1 row");
    }

    /**
     * Guard: an odd number of transitions to one target must survive cancellation. Set, clear, then re-set the
     * same {@code NEW} permanent-id target in one flush and the arc is live at commit, so the footprint must
     * match a plain single set.
     * <p>
     * This is the probe that separates a correct pairing from a lookalike that never removes the paired entry
     * from its map: such a map is append-only, the cancelling delete stays under the key, and the third event
     * pairs against that residue and is dropped -- silently losing an arc that is live.
     */
    @Test
    public void setClearAndResetTheSameTargetInOneFlushKeepsTheArc() throws SQLException {
        FlushFootprint threeTransitions = commitNewSub1Sub1(true, false, true);
        deleteRowsBetweenFootprintRuns();
        FlushFootprint singleSet = commitNewSub1Sub1(true);

        assertEquals(singleSet, threeTransitions,
                "an arc set, cleared and set again in one flush is live at commit, so it must leave exactly "
                        + "what setting it once leaves");
    }

    /**
     * Guard: clearing and then restoring the same arc on endpoints that are <em>both</em> already persisted must
     * emit no statements at all. The cancelled pair is the context's only change, so the whole-context diff is
     * noop and the commit takes the phantom path.
     * <p>
     * The order must not be flipped. Clear-then-restore puts the delete first, which is the only sequence that
     * distinguishes a symmetric cancellation from one handling delete-after-create alone -- the database outcome
     * is identical either way, so the statement count is the only observable.
     * <p>
     * The cancellation is not what makes this case pass, and it says so deliberately. On persisted endpoints
     * {@code ObjectDiff.isNoop}'s {@code visitToOne} value comparison already takes the phantom path for a
     * clear-and-restore pair. The guard is here for the statement count should
     * {@code isNoop} ever stop seeing through the pair; the cancellation's own teeth on persisted endpoints are
     * {@link #cancelledArcOnAPersistedHolderLeavesWhatNoArcWouldLeave}.
     */
    @Test
    public void clearingAndRestoringTheArcOnPersistedEndpointsEmitsNoStatements() throws SQLException {
        seedPersistedSub1Sub1(1);
        ivOtherTable.insert(3, "holder", 1);

        ObjectContext context = env.runtime().newContext();
        IvOther holder = Cayenne.objectForPK(context, IvOther.class, 3);
        IvSub1Sub1 target = holder.getSub1Sub1();
        assertNotNull(target, "precondition: the seeded arc resolves to a persisted target");

        holder.setSub1Sub1(null);
        holder.setSub1Sub1(target);

        int statements = env.runWithQueryCounter(context::commitChanges);

        assertEquals(0, statements, "a cancelled pair on persisted endpoints must emit no statements");
        assertEquals(1, ((Number) rowWithId(ivOtherTable, 3)[2]).intValue(),
                "and must leave the foreign key holding its original value");
    }

    /**
     * Guard: the same cancelled arc reached through an already-persisted holder. The derived branch of
     * {@code processFlattenedPath} picks {@code INSERT} from the target's own storage alone, independently of the
     * holder's operation type, so an {@code UPDATE}-typed holder does not suppress the residue -- this pins that
     * it is gone there too.
     */
    @Test
    public void cancelledArcOnAPersistedHolderLeavesWhatNoArcWouldLeave() throws SQLException {
        FlushFootprint withCancelledArc = commitNewSub1Sub1UnderPersistedHolder(true);
        deleteRowsBetweenFootprintRuns();
        FlushFootprint control = commitNewSub1Sub1UnderPersistedHolder(false);

        assertEquals(control, withCancelledArc,
                "a cancelled arc on a persisted holder must also leave what never setting it would leave");
    }

    /**
     * The whole observable footprint of one commit: the row counts of the target's three-table hierarchy, the
     * holder's foreign key, the number of statements the flush emitted, and the size of the
     * {@code DataContextObjectStore}'s flattened-path tracking for each endpoint.
     */
    private record FlushFootprint(int ivRoot, int ivSub1, int ivSub1Sub1, Integer foreignKey, int statements,
                                  int holderTrackedPaths, int targetTrackedPaths) {
    }

    /**
     * Commits a {@code NEW} permanent-id {@code IvSub1Sub1} target together with a permanent-id {@code IvOther}
     * holder in one flush, applying the given arc transitions in order -- {@code true} sets the arc to the
     * target, {@code false} clears it -- and returns the resulting footprint. Both endpoints carry
     * caller-supplied permanent ids so that no run generates a primary key, without which the cached key blocks
     * of {@code JdbcPkGenerator} would make the statement counts incomparable.
     */
    private FlushFootprint commitNewSub1Sub1(boolean... arcTransitions) throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 target = newSub1Sub1WithPermanentId(context, 1);

        IvOther holder = newOtherWithPermanentId(context, 3);
        holder.setName("holder");
        applyArcTransitions(holder, target, arcTransitions);

        int statements = env.runWithQueryCounter(context::commitChanges);

        return footprintOf(context, statements, holder, target);
    }

    /**
     * The counterpart of {@link #commitNewSub1Sub1} for an {@code IvOther} that is already persisted with a null
     * foreign key, so that the holder reaches the flush as an {@code UPDATE} rather than an {@code INSERT}.
     */
    private FlushFootprint commitNewSub1Sub1UnderPersistedHolder(boolean setAndClearArc) throws SQLException {
        ivOtherTable.insert(3, "holder", null);

        ObjectContext context = env.runtime().newContext();
        IvOther holder = Cayenne.objectForPK(context, IvOther.class, 3);
        IvSub1Sub1 target = newSub1Sub1WithPermanentId(context, 1);
        if(setAndClearArc) {
            applyArcTransitions(holder, target, true, false);
        }

        int statements = env.runWithQueryCounter(context::commitChanges);

        return footprintOf(context, statements, holder, target);
    }

    private static void applyArcTransitions(IvOther holder, IvSub1Sub1 target, boolean... arcTransitions) {
        for(boolean setArc : arcTransitions) {
            if(setArc) {
                holder.setSub1Sub1(target);
            } else {
                holder.setSub1Sub1(null);
            }
        }
    }

    private FlushFootprint footprintOf(ObjectContext context, int statements, IvOther holder, IvSub1Sub1 target)
            throws SQLException {
        Object[] holderRow = rowWithId(ivOtherTable, Cayenne.intPKForObject(holder));
        assertNotNull(holderRow, "precondition: the holder's row was written");

        DataContextObjectStore objectStore = ((DataContext) context).getObjectStore();
        return new FlushFootprint(
                ivRootTable.getRowCount(),
                ivSub1Table.getRowCount(),
                ivSub1Sub1Table.getRowCount(),
                foreignKeyOf(holderRow),
                statements,
                objectStore.getFlattenedPathIdMap(holder.getObjectId()).size(),
                objectStore.getFlattenedPathIdMap(target.getObjectId()).size());
    }

    /** The holder's {@code SUB1_SUB1_ID}, normalized to {@code Integer} so footprints compare across drivers. */
    private static Integer foreignKeyOf(Object[] holderRow) {
        if(holderRow[2] == null) {
            return null;
        }
        return ((Number) holderRow[2]).intValue();
    }

    /** Seeds a genuinely persisted {@code IvSub1Sub1}, one row in each table of its hierarchy. */
    private void seedPersistedSub1Sub1(int id) throws SQLException {
        ivRootTable.insert(id, "IvSub1Sub1", "target");
        ivSub1Table.insert(id, null, null, null);
        ivSub1Sub1Table.insert(id, null, null);
    }

    /** Unwinds the rows one footprint run leaves so that the next run measures from the same empty state. */
    private void deleteRowsBetweenFootprintRuns() throws SQLException {
        ivOtherTable.deleteAll();
        ivSub1Sub1Table.deleteAll();
        ivSub1Table.deleteAll();
        ivRootTable.deleteAll();
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
