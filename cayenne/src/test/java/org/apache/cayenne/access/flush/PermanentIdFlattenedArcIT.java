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
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.reflect.AdditionalDbEntityDescriptor;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvConcrete;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A vertical-inheritance child row must be inserted, not updated, when the row has never been written.
 * <p>
 * {@code ArcValuesCreationHandler.processFlattenedPath} chooses the operation type for an intermediate child row
 * in two places, and both have to ask whether the row exists. An id read from {@code ObjectStore} tracking is
 * evidence the row was seen in the database; one computed by {@code deriveIdFromFinalTarget} from the arc
 * target's primary key is evidence of nothing. Since derivation refuses only <em>temporary</em> ids, a
 * {@code NEW} object registered under a permanent, caller-supplied {@link ObjectId} derives successfully, and
 * marking its child row {@code UPDATE} would match no rows, raise nothing, and commit a parent row with no
 * child.
 * <p>
 * {@code IvConcrete.relatedAbstract} is mapped {@code concrete.relatedAbstract}, putting the foreign key on the
 * child table, so setting it walks exactly the path that goes wrong.
 */
public class PermanentIdFlattenedArcIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    TableHelper ivAbstractTable;

    TableHelper ivConcreteTable;

    TableHelper ivRootTable;

    TableHelper ivSub1Table;

    TableHelper ivSub1Sub1Table;

    TableHelper ivOtherTable;

    TableHelper ivBaseTable;

    TableHelper ivImplTable;

    @BeforeEach
    public void setUp() {
        ivAbstractTable = env.table("IV_ABSTRACT").setColumns("ID", "PARENT_ID", "TYPE")
                .setColumnTypes(Types.INTEGER, Types.INTEGER, Types.CHAR);
        ivConcreteTable = env.table("IV_CONCRETE").setColumns("ID", "NAME", "RELATED_ABSTRACT_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);
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
        ivConcreteTable.deleteAll();
        ivAbstractTable.deleteAll();
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
     * Registers a new object under a caller-supplied permanent id, as a deserializer minting ids client-side
     * does, rather than leaving it on a Cayenne temporary id until commit.
     */
    private static IvConcrete newWithPermanentId(ObjectContext context, int id) {
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvConcrete");
        IvConcrete object = (IvConcrete) descriptor.createObject();
        object.setObjectId(ObjectId.of("IvConcrete", "ID", id));
        context.registerNewObject(object);
        return object;
    }

    @Test
    public void insertsChildRowForNewArcTargetUnderPermanentId() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvConcrete holder = newWithPermanentId(context, 1);
        IvConcrete target = newWithPermanentId(context, 2);

        holder.setName("holder");
        target.setName("target");
        holder.setRelatedAbstract(target);

        context.commitChanges();

        assertEquals(2, ivConcreteTable.getRowCount(),
                "both child rows must be inserted; the arc holder's row is the one that goes missing");

        ObjectContext cleanContext = env.runtime().newContext();
        IvConcrete refetchedHolder = (IvConcrete) Cayenne
                .objectForPK(cleanContext, ObjectId.of("IvConcrete", "ID", 1));
        assertEquals("holder", refetchedHolder.getName(), "the arc holder's flattened attribute must read back");
        assertEquals(2, Cayenne.intPKForObject(refetchedHolder.getRelatedAbstract()),
                "the arc holder's child row must carry the foreign key");
    }

    /**
     * The order the two objects are registered in must not decide whether a row is written, so both orders
     * are covered.
     */
    @Test
    public void insertsChildRowRegardlessOfRegistrationOrder() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvConcrete target = newWithPermanentId(context, 2);
        IvConcrete holder = newWithPermanentId(context, 1);

        holder.setName("holder");
        target.setName("target");
        holder.setRelatedAbstract(target);

        context.commitChanges();

        assertEquals(2, ivConcreteTable.getRowCount(),
                "registering the arc target first must not change which rows are written");
        assertNotNull(concreteRow(1), "the arc holder's child row must exist");
    }

    /**
     * The arc alone must be enough to write the holder's child row. Nothing else in the holder's own diff
     * reaches the child table here, so this isolates the arc as the sole cause of the row.
     */
    @Test
    public void insertsChildRowWhenHolderHasNoFlattenedAttributeOfItsOwn() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvConcrete holder = newWithPermanentId(context, 1);
        IvConcrete target = newWithPermanentId(context, 2);

        target.setName("target");
        holder.setRelatedAbstract(target);

        context.commitChanges();

        Object[] holderRow = concreteRow(1);
        assertNotNull(holderRow, "the arc alone must still produce the holder's child row");
        assertEquals(2, ((Number) holderRow[2]).intValue(), "and that row must carry the foreign key");
    }

    /**
     * The guard against over-firing, and note which object has to be pre-committed: the damaging operation is
     * built while walking the <em>reverse</em> arc, so the derived intermediate row -- and the persistence-state
     * lookup that gates it -- belong to the arc <em>holder</em>, not to the object the arc points at. With
     * the holder already persisted and its child row present, the write must stay an update. Were the gate to
     * fire here it would insert over an existing row and fail on a duplicate key.
     */
    @Test
    public void updatesChildRowWhenHolderIsAlreadyPersisted() throws SQLException {
        ivAbstractTable.insert(1, null, "S");
        ivConcreteTable.insert(1, "existing", null);

        ObjectContext context = env.runtime().newContext();
        IvConcrete holder = (IvConcrete) Cayenne.objectForPK(context, ObjectId.of("IvConcrete", "ID", 1));
        assertNotNull(holder, "precondition: the holder is already persisted with its child row");

        IvConcrete target = newWithPermanentId(context, 2);
        target.setName("target");
        holder.setRelatedAbstract(target);

        context.commitChanges();

        assertEquals(2, ivConcreteTable.getRowCount(),
                "the holder's existing child row must be updated in place, not duplicated");
        Object[] holderRow = concreteRow(1);
        assertNotNull(holderRow, "the holder's child row must still be there");
        assertEquals("existing", holderRow[1], "the pre-existing flattened attribute must survive the update");
        assertEquals(2, ((Number) holderRow[2]).intValue(), "and the foreign key must have been written");
    }

    /**
     * Control: ordinary {@code newObject} leaves both objects on temporary ids, which derivation refuses, so
     * this path never reaches the branch the cases above exercise.
     */
    @Test
    public void writesBothChildRowsForTemporaryIds() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvConcrete holder = context.newObject(IvConcrete.class);
        IvConcrete target = context.newObject(IvConcrete.class);

        holder.setName("holder");
        target.setName("target");
        holder.setRelatedAbstract(target);

        context.commitChanges();

        assertEquals(2, ivConcreteTable.getRowCount(), "the temporary-id path must be unaffected");
    }

    /**
     * The multi-intermediate form of the same defect, reached by a plain forward arc rather than a reverse one.
     * {@code IvOther.sub1Sub1} is mapped {@code sub1Sub1.master.master}, so the walk derives an id at
     * <em>two</em> hops -- {@code IV_SUB1_SUB1} and {@code IV_SUB1} -- so both rows are at stake.
     * Neither object sets a flattened attribute, so the arc is the only thing that can produce them.
     */
    @Test
    public void insertsEveryIntermediateRowForAThreeSegmentPath() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvSub1Sub1");
        IvSub1Sub1 target = (IvSub1Sub1) descriptor.createObject();
        target.setObjectId(ObjectId.of("IvSub1Sub1", "ID", 1));
        context.registerNewObject(target);

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(target);

        context.commitChanges();

        assertEquals(1, ivRootTable.getRowCount(), "the target's root row must be written");
        assertEquals(1, ivSub1Table.getRowCount(), "the first derived intermediate row must be inserted");
        assertEquals(1, ivSub1Sub1Table.getRowCount(), "the second derived intermediate row must be inserted");
    }

    @Test
    public void secondCommitDoesNotDuplicateAChildRowWrittenByTheArcAlone() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvConcrete holder = newWithPermanentId(context, 1);
        IvConcrete target = newWithPermanentId(context, 2);

        target.setName("target");
        holder.setRelatedAbstract(target);
        context.commitChanges();

        assertNotNull(concreteRow(1), "precondition: the arc alone wrote the holder's child row");
        int afterFirstCommit = ivConcreteTable.getRowCount();

        holder.setName("named later");
        context.commitChanges();

        assertEquals(afterFirstCommit, ivConcreteTable.getRowCount(),
                "writing a flattened attribute afterwards must update that row, not insert a second one");
        Object[] holderRow = concreteRow(1);
        assertNotNull(holderRow, "the holder's child row must still be there");
        assertEquals("named later", holderRow[1], "and must carry the newly written attribute");
    }

    /**
     * The shape the defect actually took in the field, and the only case here whose first path segment is
     * <em>to-many</em>.
     * <p>
     * Nothing sets a flattened arc explicitly: the test sets the plain to-one {@code IvImpl.other3}
     * ({@code impl.other3}), and Cayenne maintains its mapped inverse {@code IvOther.implsWithInverse}
     * ({@code implsWithInverse.base}) on the target. Walking that inverse is what derives an id --
     * {@code implsWithInverse} is to-many, and the one remaining hop {@code base} joins {@code IV_IMPL.ID} to
     * {@code IV_BASE.ID} with a {@code toDependentPK} reverse, so it is a full shared-PK link to the master.
     * Derivation therefore succeeds, and the sub-table row must still be marked {@code INSERT}.
     * <p>
     * The {@code IvOther} target is already persisted while the vertically-inherited {@code IvImpl} is
     * {@code NEW}, which is what makes the registered-and-{@code NEW} gate the deciding condition.
     */
    @Test
    public void insertsChildRowWhenTheInverseOfAToOneWalksAToManyPath() throws SQLException {
        ivOtherTable.insert(1, "other", null);

        ObjectContext context = env.runtime().newContext();
        IvOther other = (IvOther) Cayenne.objectForPK(context, ObjectId.of("IvOther", "ID", 1));
        assertNotNull(other, "precondition: the arc target is already persisted");

        IvImpl impl = newImplWithPermanentId(context, 2);
        impl.setName("impl");
        impl.setAttr1("attr");
        impl.setOther3(other);

        context.commitChanges();

        assertEquals(1, ivBaseTable.getRowCount(), "the root row must be written");
        Object[] implRow = implRow(2);
        assertNotNull(implRow, "the sub-table row must be inserted rather than updated to nothing");
        assertEquals("attr", implRow[1], "and must carry the flattened attribute");
        assertEquals(1, ((Number) implRow[3]).intValue(), "and the foreign key the arc set");
    }

    /**
     * Control isolating the inverse as the cause: {@code IvImpl.other1} joins the same two entities over
     * {@code OTHER1_ID}, for which no reverse relationship is mapped. With no inverse there is no to-many walk,
     * derivation cannot succeed, and the row is written correctly for that reason alone -- so this case is not
     * claimed as defect coverage. It is here to show that what distinguishes the case above is the presence of
     * the mapped inverse, not the fact of setting a to-one.
     * <p>
     * The mapping is what separates the two cases, and it reaches the decision indirectly. The deferral asks
     * whether the arc's reverse was declared by this flush, and an unmapped inverse has no way to declare one:
     * there is no reverse {@code ArcProperty} to raise the event from. So this control rests on no conjunct that
     * reads the mapping -- the absence it isolates denies the deferral its evidence rather than failing a test
     * the predicate performs.
     */
    @Test
    public void toOneWithoutAMappedInverseWritesItsChildRowEitherWay() throws SQLException {
        ivOtherTable.insert(1, "other", null);

        ObjectContext context = env.runtime().newContext();
        IvOther other = (IvOther) Cayenne.objectForPK(context, ObjectId.of("IvOther", "ID", 1));

        IvImpl impl = newImplWithPermanentId(context, 2);
        impl.setName("impl");
        impl.setAttr1("attr");
        impl.setOther1(other);

        context.commitChanges();

        Object[] implRow = implRow(2);
        assertNotNull(implRow, "the sub-table row must be present");
        assertEquals(1, ((Number) implRow[2]).intValue(), "carrying the OTHER1_ID foreign key");
    }

    /**
     * Reassignment to a target that is itself {@code NEW} under a permanent id in the <em>second</em> commit. The
     * new target's root row is inserted but both intermediate rows take a zero-row {@code UPDATE}, so the foreign
     * key is written to an id with no sub-table rows behind it.
     */
    @Test
    public void reassigningToANewPermanentIdTargetWritesTheTargetsRows() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 firstTarget = newSub1Sub1WithPermanentId(context, 1);

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(firstTarget);
        context.commitChanges();

        // the second target is NEW under a permanent id in the SECOND commit, which is the whole point here
        IvSub1Sub1 secondTarget = newSub1Sub1WithPermanentId(context, 2);
        other.setSub1Sub1(secondTarget);
        context.commitChanges();

        assertNotNull(rowWithId(ivSub1Table, 2), "the new target's IV_SUB1 row must be inserted");
        assertNotNull(rowWithId(ivSub1Sub1Table, 2), "and its IV_SUB1_SUB1 row too");
        assertEquals(Cayenne.intPKForObject(secondTarget),
                ((Number) rowWithId(ivOtherTable, Cayenne.intPKForObject(other))[2]).intValue(),
                "and the foreign key must point at a target whose rows all exist");

        assertNotNull(rowWithId(ivSub1Table, 1), "the old target's rows must survive the reassignment");
        assertNotNull(rowWithId(ivSub1Sub1Table, 1), "both of them");
        assertEquals(2, ivSub1Table.getRowCount(), "two targets, two rows -- not one row re-keyed");
        assertEquals(2, ivSub1Sub1Table.getRowCount(), "and the same one level down");
    }

    /**
     * The same reassignment with an ordinary generated id: the second target comes from {@code newObject}, so it
     * is {@code NEW} on a Cayenne temporary id rather than a caller-supplied permanent one. The generator picks
     * its key, so every assertion reads that key back rather than naming a literal.
     * <p>
     * This is the shape an application reaches, and it is also what separates redirecting the deletion walk from
     * merely repairing the operation type it pins: on a temporary id the misdirected deletion cannot derive an
     * id at all, so it mints the phantom intermediate itself and pins {@code UPDATE} there, and no {@code INSERT}
     * verdict is produced anywhere for a type repair to promote or to gate.
     */
    @Test
    public void reassigningToATemporaryIdTargetWritesTheTargetsRows() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvSub1Sub1 firstTarget = newSub1Sub1WithPermanentId(context, 1);

        IvOther other = context.newObject(IvOther.class);
        other.setName("other");
        other.setSub1Sub1(firstTarget);
        context.commitChanges();

        IvSub1Sub1 secondTarget = context.newObject(IvSub1Sub1.class);
        other.setSub1Sub1(secondTarget);
        context.commitChanges();

        int secondTargetId = Cayenne.intPKForObject(secondTarget);
        assertNotNull(rowWithId(ivSub1Table, secondTargetId), "the new target's IV_SUB1 row must be inserted");
        assertNotNull(rowWithId(ivSub1Sub1Table, secondTargetId), "and its IV_SUB1_SUB1 row too");
        assertEquals(secondTargetId, ((Number) rowWithId(ivOtherTable, Cayenne.intPKForObject(other))[2]).intValue(),
                "and the foreign key must point at a target whose rows all exist");
    }

    /**
     * Condition 4's anchor, against the real descriptors the enabled cases above walk: every route the flip
     * reaches for {@code IvSub1Sub1} resolves in its own {@code getAdditionalDbEntities()} to the matching
     * sub-table, and a route it does not declare resolves to nothing. The map is cumulative for deeper levels,
     * which is why the second key is {@code sub1.sub1Sub1} rather than {@code sub1Sub1}.
     */
    @Test
    public void targetDescriptorDeclaresEveryRouteTheFlipReaches() {
        ObjectContext context = env.runtime().newContext();
        ClassDescriptor descriptor = context.getEntityResolver().getClassDescriptor("IvSub1Sub1");

        Map<CayennePath, AdditionalDbEntityDescriptor> declared = descriptor.getAdditionalDbEntities();

        assertNotNull(declared.get(CayennePath.of("sub1")), "the first hop's route must resolve");
        assertEquals("IV_SUB1", declared.get(CayennePath.of("sub1")).getDbEntity().getName());
        assertNotNull(declared.get(CayennePath.of("sub1.sub1Sub1")), "the cumulative deeper route must resolve");
        assertEquals("IV_SUB1_SUB1", declared.get(CayennePath.of("sub1.sub1Sub1")).getDbEntity().getName());
        assertNull(declared.get(CayennePath.of("notAMappedRoute")),
                "a route the descriptor does not declare must resolve to nothing, which is what makes the "
                        + "refusal a refusal rather than a null dereference");
    }

    /**
     * The mark {@code markDerivedRowOnOwner} leaves is what lets a later commit in the same context write a
     * flattened attribute to the sub-table row an earlier commit's derived flip inserted. The impl that owns
     * that row was created in this context rather than fetched, so {@code ObjectResolver.resolveAdditionalIds}
     * never recorded the route; without the mark the later commit has no evidence the row exists, attempts an
     * {@code INSERT} over it, and the database rejects the duplicate primary key.
     * <p>
     * The holder is committed on its own first, so that the flush carrying the arc <em>updates</em> its
     * {@code IV_OTHER} row rather than inserting it. {@code IV_OTHER} and {@code IV_IMPL} reference each other,
     * so two INSERTs would sit in one strongly connected component whose internal order only
     * {@code DefaultDbRowOpSorter}'s row-dependency tie-break settles -- a different fix, and not the one this
     * case is about. {@code DbRowComparator} sorts on operation type before anything else and
     * {@code DbRowOpType} orders {@code INSERT} before {@code UPDATE}, so an update is ordered after every
     * insert whatever the entity graph looks like. Persisting the holder first therefore leaves this case
     * depending on the sorter for nothing, and it fails only when the mark is missing.
     */
    @Test
    public void laterCommitWritesAFlattenedAttributeToTheRowTheDerivedFlipCreated() throws SQLException {
        ObjectContext context = env.runtime().newContext();

        IvOther holder = newOtherWithPermanentId(context, 1);
        holder.setName("holder");
        context.commitChanges();
        assertEquals(0, ivImplTable.getRowCount(),
                "precondition: the holder is persisted with no impl behind it yet");

        IvImpl impl = newImplWithPermanentId(context, 2);
        impl.setName("impl");
        holder.setImpl(impl);
        context.commitChanges();

        Object[] afterFlip = implRow(2);
        assertNotNull(afterFlip, "precondition: the derived flip inserted the impl's sub-table row");
        assertNull(afterFlip[1], "precondition: no flattened attribute has been set yet, so ATTR1 starts null");

        impl.setAttr1("written-in-a-later-commit");
        context.commitChanges();

        assertEquals(1, ivImplTable.getRowCount(),
                "the impl's sub-table row must be written once -- an INSERT attempted over the existing row "
                        + "fails on a duplicate key, which is the failure this mark exists to prevent");
        Object[] afterLaterCommit = implRow(2);
        assertNotNull(afterLaterCommit, "the impl's sub-table row must still be there");
        assertEquals("written-in-a-later-commit", afterLaterCommit[1],
                "and must carry the flattened attribute the later commit wrote");

        ObjectContext cleanContext = env.runtime().newContext();
        IvImpl refetched = (IvImpl) Cayenne.objectForPK(cleanContext,
                ObjectId.of("IvImpl", "ID", 2));
        assertNotNull(refetched, "the impl must be readable back in a clean context");
        assertEquals("written-in-a-later-commit", refetched.getAttr1(),
                "carrying the same flattened attribute when read back through the mapping");
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
     * Registers a new vertically-inherited {@code IvImpl} under a caller-supplied permanent id, the sub-table
     * counterpart of {@link #newWithPermanentId} for the {@code IvBase} hierarchy.
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

    private Object[] concreteRow(int id) throws SQLException {
        return rowWithId(ivConcreteTable, id);
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
