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
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.GraphBasedDbRowOpSorter;
import org.apache.cayenne.query.SelectById;
import org.apache.cayenne.test.jdbc.TableHelper;
import org.apache.cayenne.testdo.inheritance_vertical.IvImpl;
import org.apache.cayenne.testdo.inheritance_vertical.IvOther;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CAY-2890. A flattened relationship whose foreign key sits on a vertical-inheritance child table is recorded
 * as an arc on both of its sides, and the two sides must classify the existing child row identically. A to-many
 * hop of {@code ArcValuesCreationHandler.processFlattenedPath} that does not consult the tracked flattened id
 * treats the row as a junction row to insert, while the to-one side emits an UPDATE, and {@code DbRowOpMerger}
 * keeps whichever operation it saw first -- so the outcome turns on the order the object diffs happen to be
 * visited in.
 * <p>
 * A non-final segment's id is therefore derived from the arc's final target whenever the remaining path is a
 * chain of shared primary key joins, and that derivation takes precedence over any tracked id. Both sides of
 * the arc then name the same row, so the merge is a value merge rather than a contest over operation type and
 * the result does not depend on visit order. These tests run on the default flush action; no diff ordering is
 * imposed, because none is needed.
 * <p>
 * {@code updateFlattenedRelationshipWithInverseUnderUnsortedDiffOrder} is a control rather than defect
 * coverage, because {@code shouldSkipFlattenedOp} suppresses the to-many side whenever the arc target already
 * has tracked flattened ids and the source does not. The case that carries the defect is
 * {@code updateBelongsToWithFkOnChildTableUnderUnsortedDiffOrder}, and the longer three-segment path is
 * covered by {@code VerticalInheritanceIT.updateOneToOneChildToChildValueToValue}, whose middle hop holds the
 * foreign key and whose intermediate is tracked from a stale fetch-time value.
 */
public class FlattenedToManyOrderDependenceIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.INHERITANCE_VERTICAL_PROJECT)
            .withExtraModules(binder -> binder.bind(DbRowOpSorter.class).to(GraphBasedDbRowOpSorter.class));

    @Test
    public void updateFlattenedRelationshipWithInverseUnderUnsortedDiffOrder() throws SQLException {
        TableHelper ivOtherTable = env.table("IV_OTHER").setColumns("ID", "NAME")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR);
        TableHelper ivBaseTable = env.table("IV_BASE").setColumns("ID", "NAME", "TYPE")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.CHAR);
        TableHelper ivImplTable = env.table("IV_IMPL").setColumns("ID", "ATTR1", "OTHER3_ID")
                .setColumnTypes(Types.INTEGER, Types.VARCHAR, Types.INTEGER);

        ivOtherTable.insert(1, "other1");
        ivOtherTable.insert(2, "other2");
        ivBaseTable.insert(1, "Impl 1", "I");
        ivImplTable.insert(1, "attr1", 1);

        IvImpl impl = SelectById.query(IvImpl.class, 1).selectOne(env.context());
        IvOther other = SelectById.query(IvOther.class, 2).selectOne(env.context());
        assertNotNull(impl);
        assertNotNull(other);

        impl.setOther3(other);
        env.context().commitChanges();

        assertEquals(1, ivImplTable.getRowCount(), "the existing child row must be updated, not duplicated");
        Object[] row = ivImplTable.selectAll().get(0);
        assertEquals(2, row[2], "OTHER3_ID written to the existing child row");
        assertEquals("attr1", row[1], "existing child column preserved");
    }

    /**
     * The scenario the diff sorting actually carries. Reassigning a belongs-to whose foreign key lives on the
     * vertical-inheritance child table must not depend on the order the diffs are visited in: classifying the
     * existing child row from the to-many side alone yields {@code INSERT INTO IV_IMPL}, so correctness would
     * rest on the bucket ordering rather than on the classification being right. Mirrors
     * {@code VerticalInheritanceIT.updateBelongsToWithFkOnChildTableImplTwoToImplThree}.
     */
    @Test
    public void updateBelongsToWithFkOnChildTableUnderUnsortedDiffOrder() throws SQLException {
        TableHelper ivBaseTable = env.table("IV_BASE", "ID", "NAME", "TYPE");
        TableHelper ivImplTable = env.table("IV_IMPL", "ID", "ATTR1", "BASE_ID");

        ivBaseTable.insert(2, "Base 2", "I");
        ivImplTable.insert(2, "Impl 2", null);
        ivBaseTable.insert(3, "Base 3", "I");
        ivImplTable.insert(3, "Impl 3", null);
        ivBaseTable.insert(1, "Base 1", "I");
        ivImplTable.insert(1, "Impl 1", 2);

        IvImpl impl1 = SelectById.queryId(IvImpl.class, 1).selectOne(env.context());
        IvImpl impl3 = SelectById.queryId(IvImpl.class, 3).selectOne(env.context());
        assertNotNull(impl1);
        assertNotNull(impl3);

        impl1.setRelatedImpl(impl3);
        env.context().commitChanges();

        assertEquals(3, ivImplTable.getRowCount(), "no child row may be inserted or removed");

        ObjectContext cleanContext = env.runtime().newContext();
        IvImpl impl1Fetched = SelectById.queryId(IvImpl.class, 1).selectOne(cleanContext);
        IvImpl impl2Fetched = SelectById.queryId(IvImpl.class, 2).selectOne(cleanContext);
        IvImpl impl3Fetched = SelectById.queryId(IvImpl.class, 3).selectOne(cleanContext);
        assertEquals(impl3Fetched, impl1Fetched.getRelatedImpl());
        assertEquals("Impl 1", impl1Fetched.getAttr1());
        assertEquals("Impl 2", impl2Fetched.getAttr1());
        assertEquals("Impl 3", impl3Fetched.getAttr1());
    }
}
