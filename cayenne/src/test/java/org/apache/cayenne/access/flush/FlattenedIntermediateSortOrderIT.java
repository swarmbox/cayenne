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

import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.test.jdbc.TableHelper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * An intermediate row must be written before the row whose foreign key references it, whichever way that
 * dependency happens to point.
 * <p>
 * {@code IV_OTHER} and {@code IV_IMPL} reference each other across the flattened arc, so they land in one
 * strongly connected component and the sorter leaves them in a single comparator-tied run. The order within
 * that run came from {@code DbEntity} name order, which is unrelated to the dependency between the rows, so
 * whether the commit succeeded was decided by a name comparison. Ordering the tied run by row dependency is
 * what these cases pin.
 * <p>
 * The commit itself is the primary assertion in each: a wrong order aborts the transaction on the foreign key
 * constraint before any of the row assertions is reached. The three cases cover a derived intermediate, a
 * flattened intermediate minted for a {@code NEW} target where no id can be derived, and the opposite
 * dependency direction -- which needs the reverse of what the other two need, and the reverse of name order.
 */
public class FlattenedIntermediateSortOrderIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT);

    TableHelper ivOtherTable;

    TableHelper ivBaseTable;

    TableHelper ivImplTable;

    @BeforeEach
    public void setUp() {
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
    }

    /**
     * The two-segment {@code impl.base} shape, where the operation <em>types</em> are all correct and only the
     * order is wrong: {@code IV_OTHER} is inserted before the derived {@code IV_IMPL} row its {@code IMPL_ID}
     * references.
     */
    @Test
    public void derivedIntermediateInsertIsSortedBeforeTheRowThatReferencesIt() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        // ids are pinned so the assertions below can name each row directly rather than reading a generated
        // key back; the order the tied run arrives in comes from registration order, not from the id
        IvOther other = newOtherWithPermanentId(context, 1);
        other.setName("other");

        IvImpl impl = newImplWithPermanentId(context, 2);
        impl.setName("impl");
        impl.setAttr1("attr");

        other.setImpl(impl);

        context.commitChanges();

        // the commit itself is the primary assertion: IV_OTHER is currently inserted first and the foreign key
        // constraint aborts the transaction before IV_IMPL is ever reached
        assertEquals(1, ivBaseTable.getRowCount(), "the root row must be written");
        assertNotNull(implRow(2), "the derived IV_IMPL row must be inserted before the row referencing it");
    }

    /**
     * The same ordering defect with no derivation involved: the arc target is an ordinary {@code newObject}, so
     * the intermediate row comes from the null-branch under a fresh temporary {@code db:} id rather than from
     * {@code deriveIdFromFinalTarget}. Both instances share one fix because both put {@code IV_OTHER} and
     * {@code IV_IMPL} in the same strongly connected component, and so in the same comparator-tied run.
     */
    @Test
    public void flattenedIntermediateForANewObjectTargetIsSortedBeforeTheRowThatReferencesIt() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvOther other = newOtherWithPermanentId(context, 1);
        other.setName("other");

        IvImpl impl = context.newObject(IvImpl.class);
        impl.setName("implNew");
        impl.setAttr1("aNew");

        other.setImpl(impl);

        context.commitChanges();

        assertEquals(1, ivBaseTable.getRowCount(), "the root row must be written");
        assertEquals(1, ivImplTable.getRowCount(),
                "the intermediate IV_IMPL row must be inserted before the row referencing it");
    }

    /**
     * The opposite dependency direction within the same tied run: {@code IvImpl.other3} puts the foreign key on
     * {@code IV_IMPL}, so {@code IV_OTHER} must be written <em>first</em> -- the reverse of what the two cases
     * above need, and the reverse of {@code DbEntity} name order. Both rows are new here, unlike the two
     * {@code setOther1}/{@code setOther3} guard tests, which pre-insert their holder and so produce no
     * {@code IV_OTHER} operation and no tied run at all.
     * <p>
     * The {@code IvImpl} is registered <strong>before</strong> the {@code IvOther} deliberately. The flush walks
     * object diffs in registration order, so registering the row that holds the foreign key first puts the
     * operations into the tied run in exactly the wrong order and the sort has to move the referenced row ahead
     * of it. Registering them the other way round would hand the sort an already-correct order and the case
     * would pass without it.
     */
    @Test
    public void theReferencedHolderIsWrittenBeforeTheRowWhoseForeignKeyPointsAtIt() throws SQLException {
        ObjectContext context = env.runtime().newContext();
        IvImpl impl = newImplWithPermanentId(context, 2);
        impl.setName("impl");
        impl.setAttr1("attr");

        IvOther other = newOtherWithPermanentId(context, 1);
        other.setName("other");

        impl.setOther3(other);

        context.commitChanges();

        assertNotNull(rowWithId(ivOtherTable, 1), "the referenced IV_OTHER row must be present");
        Object[] implRow = implRow(2);
        assertNotNull(implRow, "and the IV_IMPL row carrying the foreign key must be present");
        assertEquals(1, ((Number) implRow[3]).intValue(), "with OTHER3_ID pointing at it");
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
