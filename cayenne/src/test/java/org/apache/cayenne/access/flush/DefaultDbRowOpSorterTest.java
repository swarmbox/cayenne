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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.cayenne.ObjectId;
import org.apache.cayenne.PersistenceState;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.access.DataDomain;
import org.apache.cayenne.access.flush.operation.DefaultDbRowOpSorter;
import org.apache.cayenne.access.flush.operation.BaseDbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOp;
import org.apache.cayenne.access.flush.operation.DbRowOpSorter;
import org.apache.cayenne.access.flush.operation.DeleteDbRowOp;
import org.apache.cayenne.access.flush.operation.InsertDbRowOp;
import org.apache.cayenne.access.flush.operation.UpdateDbRowOp;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.EntitySorter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DefaultDbRowOpSorterTest {

    private EntitySorter entitySorter;
    private EntityResolver entityResolver;
    private DataMap dataMap;
    private DbRowOpSorter sorter;

    @BeforeEach
    public void createSorter() {
        entitySorter = mock(EntitySorter.class);
        entityResolver = mock(EntityResolver.class);
        dataMap = new DataMap("sorter-test-map");

        when(entitySorter.getDbEntityComparator())
                .thenReturn(Comparator.comparing(DbEntity::getName));
        when(entitySorter.isReflexive(argThat(ent -> ent.getName().equals("reflexive"))))
                .thenReturn(true);

        DataDomain dataDomain = mock(DataDomain.class);
        when(dataDomain.getEntitySorter()).thenReturn(entitySorter);
        when(dataDomain.getEntityResolver()).thenReturn(entityResolver);

        sorter = new DefaultDbRowOpSorter(() -> dataDomain);
    }

    @Test
    public void sortEmptyList() {
        List<DbRowOp> rows = new ArrayList<>();
        List<DbRowOp> sorted = sorter.sort(rows);
        assertTrue(sorted.isEmpty());
    }

    @Test
    public void sortByOpType() {
        ObjectId id1 = ObjectId.of("test", "id", 1);
        ObjectId id2 = ObjectId.of("test", "id", 2);
        ObjectId id3 = ObjectId.of("test", "id", 3);
        ObjectId id4 = ObjectId.of("test", "id", 4);
        ObjectId id5 = ObjectId.of("test", "id", 5);
        ObjectId id6 = ObjectId.of("test", "id", 6);

        DbEntity test = mockEntity("test");
        DbRowOp op1 = new InsertDbRowOp(mockObject(id1), test, id1);
        DbRowOp op2 = new UpdateDbRowOp(mockObject(id2), test, id2);
        DbRowOp op3 = new DeleteDbRowOp(mockObject(id3), test, id3);
        DbRowOp op4 = new InsertDbRowOp(mockObject(id4), test, id4);
        DbRowOp op5 = new UpdateDbRowOp(mockObject(id5), test, id5);
        DbRowOp op6 = new DeleteDbRowOp(mockObject(id6), test, id6);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4, op5, op6);
        List<DbRowOp> expected = Arrays.asList(op1, op4, op2, op5, op3, op6);

        List<DbRowOp> sorted = sorter.sort(rows);
        assertEquals(expected, sorted);
    }

    @Test
    public void sortByOpEntity() {
        ObjectId id1 = ObjectId.of("test4", "id", 1);
        ObjectId id2 = ObjectId.of("test2", "id", 2);
        ObjectId id3 = ObjectId.of("test3", "id", 3);
        ObjectId id4 = ObjectId.of("test1", "id", 4);

        DbRowOp op1 = new InsertDbRowOp(mockObject(id1), mockEntity("test4"), id1);
        DbRowOp op2 = new InsertDbRowOp(mockObject(id2), mockEntity("test2"), id2);
        DbRowOp op3 = new InsertDbRowOp(mockObject(id3), mockEntity("test3"), id3);
        DbRowOp op4 = new InsertDbRowOp(mockObject(id4), mockEntity("test1"), id4);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4);
        List<DbRowOp> expected = Arrays.asList(op4, op2, op3, op1);

        List<DbRowOp> sorted = sorter.sort(rows);
        assertEquals(expected, sorted);
    }

    @Test
    public void sortById() {
        ObjectId id1 = ObjectId.of("test", "id", 1);
        ObjectId id2 = ObjectId.of("test", "id", 2);
        ObjectId id3 = ObjectId.of("test", "id", 2);
        ObjectId id4 = ObjectId.of("test", "id", 3);

        DbEntity test = mockEntity("test");
        InsertDbRowOp op1 = new InsertDbRowOp(mockObject(id1), test, id1);
        InsertDbRowOp op2 = new InsertDbRowOp(mockObject(id2), test, id2);
        DeleteDbRowOp op3 = new DeleteDbRowOp(mockObject(id3), test, id3);
        DeleteDbRowOp op4 = new DeleteDbRowOp(mockObject(id4), test, id4);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4);
        List<DbRowOp> expected = Arrays.asList(op1, op2, op3, op4);

        List<DbRowOp> sorted = sorter.sort(rows);
        assertEquals(expected, sorted);
    }

    @Test
    public void sortByIdDifferentEntities() {
        ObjectId id1  = ObjectId.of("test2", "id", 1);
        ObjectId id2  = ObjectId.of("test",  "id", 2);
        ObjectId id3  = ObjectId.of("test",  "id", 2);
        ObjectId id4  = ObjectId.of("test",  "id", 3);
        ObjectId id5  = ObjectId.of("test2", "id", 4);
        ObjectId id6  = ObjectId.of("test",  "id", 5);
        ObjectId id7  = ObjectId.of("test",  "id", 8);
        ObjectId id8  = ObjectId.of("test2", "id", 7);
        ObjectId id9  = ObjectId.of("test2", "id", 4);
        ObjectId id10 = ObjectId.of("test",  "id", 8);

        DbEntity test = mockEntity("test");
        DbEntity test2 = mockEntity("test2");
        BaseDbRowOp[] op = new BaseDbRowOp[10];
        op[0] = new InsertDbRowOp(mockObject(id1),  test2, id1);
        op[1] = new InsertDbRowOp(mockObject(id2),  test,  id2);
        op[2] = new DeleteDbRowOp(mockObject(id3),  test,  id3);
        op[3] = new UpdateDbRowOp(mockObject(id4),  test,  id4);
        op[4] = new InsertDbRowOp(mockObject(id5),  test2, id5);
        op[5] = new DeleteDbRowOp(mockObject(id6),  test,  id6);
        op[6] = new InsertDbRowOp(mockObject(id7),  test,  id7);
        op[7] = new UpdateDbRowOp(mockObject(id8),  test2, id8);
        op[8] = new DeleteDbRowOp(mockObject(id9),  test2, id9);
        op[9] = new DeleteDbRowOp(mockObject(id10), test,  id10);

        List<DbRowOp> expected = Arrays.asList(op[1], op[6], op[0], op[4], op[3], op[7], op[8], op[2], op[5], op[9]);
        List<DbRowOp> sorted = sorter.sort(Arrays.asList(op));

        assertEquals(expected, sorted);
    }

    @Test
    public void sortReflexive() {
        ObjectId id1 = ObjectId.of("reflexive", "id", 1);
        ObjectId id2 = ObjectId.of("reflexive", "id", 2);
        ObjectId id3 = ObjectId.of("reflexive", "id", 3);
        ObjectId id4 = ObjectId.of("reflexive", "id", 4);

        DbEntity reflexive = mockEntity("reflexive");
        DbRowOp op1 = new InsertDbRowOp(mockObject(id1), reflexive, id1);
        DbRowOp op2 = new InsertDbRowOp(mockObject(id2), reflexive, id2);
        DbRowOp op3 = new InsertDbRowOp(mockObject(id3), reflexive, id3);
        DbRowOp op4 = new InsertDbRowOp(mockObject(id4), reflexive, id4);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4);
        List<DbRowOp> expected = Arrays.asList(op1, op2, op3, op4);

        List<DbRowOp> sorted = sorter.sort(rows);
        assertEquals(expected, sorted); // no actual sorting is done
        verify(entitySorter, times(3)).getDbEntityComparator();
        verify(entitySorter) // should call entity sorter
                .sortObjectsForEntity(isNull(), any(List.class), eq(false));
        verify(entitySorter) // should call entity sorter
                .isReflexive(any(DbEntity.class));
        verifyNoMoreInteractions(entitySorter);
    }

    @Test
    public void sortReflexiveUpdates() {
        ObjectId id1 = ObjectId.of("reflexive", "id", 1);
        ObjectId id2 = ObjectId.of("reflexive", "id", 2);
        ObjectId id3 = ObjectId.of("reflexive", "id", 3);
        ObjectId id4 = ObjectId.of("reflexive", "id", 4);

        DbEntity reflexive = mockEntity("reflexive");
        DbRowOp op1 = new UpdateDbRowOp(mockObject(id1), reflexive, id1);
        DbRowOp op2 = new UpdateDbRowOp(mockObject(id2), reflexive, id2);
        DbRowOp op3 = new UpdateDbRowOp(mockObject(id3), reflexive, id3);
        DbRowOp op4 = new UpdateDbRowOp(mockObject(id4), reflexive, id4);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4);
        List<DbRowOp> expected = Arrays.asList(op1, op2, op3, op4);

        List<DbRowOp> sorted = sorter.sort(rows);
        assertEquals(expected, sorted); // no actual sorting is done
        verify(entitySorter, times(3)).getDbEntityComparator();
        verifyNoMoreInteractions(entitySorter); // shouldn't call entity sorter
    }

    @Test
    public void tiedRunIsOrderedByRowDependencyWhenTheDependentComesFirst() {
        // named so that the required output order -- referenced row first -- is the reverse of entity name order
        DbEntity dependent = mockEntity("a_dependent");
        DbEntity referenced = mockEntity("z_referenced");
        DbAttribute fk = wireDependency(dependent, "ref_id", referenced);
        tieEntityComparator();

        ObjectId dependentId = ObjectId.of("Dependent", "id", 1);
        ObjectId referencedId = ObjectId.of("Referenced", "id", 100);
        InsertDbRowOp dependentOp = new InsertDbRowOp(mockObject(dependentId), dependent, dependentId);
        InsertDbRowOp referencedOp = new InsertDbRowOp(mockObject(referencedId), referenced, referencedId);
        dependentOp.getValues().addValue(fk, 100, true);

        List<DbRowOp> sorted = sorter.sort(new ArrayList<>(List.of(dependentOp, referencedOp)));
        assertEquals(List.of(referencedOp, dependentOp), sorted);
    }

    @Test
    public void tiedRunKeepsACorrectOrderWhenTheDependentComesLast() {
        DbEntity dependent = mockEntity("a_dependent");
        DbEntity referenced = mockEntity("z_referenced");
        DbAttribute fk = wireDependency(dependent, "ref_id", referenced);
        tieEntityComparator();

        ObjectId dependentId = ObjectId.of("Dependent", "id", 1);
        ObjectId referencedId = ObjectId.of("Referenced", "id", 100);
        InsertDbRowOp dependentOp = new InsertDbRowOp(mockObject(dependentId), dependent, dependentId);
        InsertDbRowOp referencedOp = new InsertDbRowOp(mockObject(referencedId), referenced, referencedId);
        dependentOp.getValues().addValue(fk, 100, true);

        List<DbRowOp> sorted = sorter.sort(new ArrayList<>(List.of(referencedOp, dependentOp)));
        assertEquals(List.of(referencedOp, dependentOp), sorted);
    }

    @Test
    public void anOperationDoesNotCrossAComparatorBoundaryToSatisfyADependency() {
        DbEntity dependent = mockEntity("a_dependent");
        DbEntity referenced = mockEntity("z_referenced");
        DbAttribute fk = wireDependency(dependent, "ref_id", referenced);

        ObjectId dependentId = ObjectId.of("Dependent", "id", 1);
        ObjectId referencedId = ObjectId.of("Referenced", "id", 100);
        InsertDbRowOp dependentOp = new InsertDbRowOp(mockObject(dependentId), dependent, dependentId);
        UpdateDbRowOp referencedOp = new UpdateDbRowOp(mockObject(referencedId), referenced, referencedId);
        dependentOp.getValues().addValue(fk, 100, true);

        // the dependency edge points at the update, but operation type ordering is not negotiable
        List<DbRowOp> sorted = sorter.sort(new ArrayList<>(List.of(referencedOp, dependentOp)));
        assertEquals(List.of(dependentOp, referencedOp), sorted);
    }

    @Test
    public void aCycleInATiedRunLeavesTheInputOrderAndDoesNotThrow() {
        // the required output is the input order, which is the reverse of both name order and ascending id order
        DbEntity first = mockEntity("z_cycle_first");
        DbEntity second = mockEntity("a_cycle_second");
        DbAttribute firstFk = wireDependency(first, "second_id", second);
        DbAttribute secondFk = wireDependency(second, "first_id", first);
        tieEntityComparator();

        ObjectId firstId = ObjectId.of("First", "id", 200);
        ObjectId secondId = ObjectId.of("Second", "id", 100);
        InsertDbRowOp firstOp = new InsertDbRowOp(mockObject(firstId), first, firstId);
        InsertDbRowOp secondOp = new InsertDbRowOp(mockObject(secondId), second, secondId);
        firstOp.getValues().addValue(firstFk, 100, true);
        secondOp.getValues().addValue(secondFk, 200, true);

        List<DbRowOp> sorted = sorter.sort(new ArrayList<>(List.of(firstOp, secondOp)));
        assertEquals(List.of(firstOp, secondOp), sorted);
    }

    @Test
    public void singleEntityListDoesNotConsultTheEntityComparatorBeyondTheMainSort() {
        ObjectId id1 = ObjectId.of("single", "id", 1);
        ObjectId id2 = ObjectId.of("single", "id", 2);
        ObjectId id3 = ObjectId.of("single", "id", 3);
        ObjectId id4 = ObjectId.of("single", "id", 4);

        DbEntity single = mockEntity("single");
        DbRowOp op1 = new InsertDbRowOp(mockObject(id1), single, id1);
        DbRowOp op2 = new InsertDbRowOp(mockObject(id2), single, id2);
        DbRowOp op3 = new InsertDbRowOp(mockObject(id3), single, id3);
        DbRowOp op4 = new InsertDbRowOp(mockObject(id4), single, id4);

        List<DbRowOp> rows = Arrays.asList(op1, op2, op3, op4);
        List<DbRowOp> sorted = sorter.sort(rows);

        assertEquals(Arrays.asList(op1, op2, op3, op4), sorted);
        // three comparisons is what List.sort alone makes for a four-element input
        verify(entitySorter, times(3)).getDbEntityComparator();
        verify(entitySorter).isReflexive(any(DbEntity.class));
        verifyNoMoreInteractions(entitySorter);
    }

    /**
     * Makes the entity comparator tie every pair, so that the operations land in a single maximal run the
     * comparator cannot separate -- the shape a strongly connected component produces in production.
     */
    private void tieEntityComparator() {
        when(entitySorter.getDbEntityComparator()).thenReturn((left, right) -> 0);
    }

    /**
     * Wires one direction of a foreign key: gives {@code dependent} an FK attribute and a to-one relationship into
     * the primary key of {@code referenced}. Both entities are added to a real DataMap, without which
     * {@code DbRelationship.isToPK()} throws while resolving the join target and the dependency is silently lost.
     */
    private DbAttribute wireDependency(DbEntity dependent, String fkName, DbEntity referenced) {
        dataMap.addDbEntity(dependent);
        dataMap.addDbEntity(referenced);

        DbAttribute fk = new DbAttribute(fkName);
        dependent.addAttribute(fk);

        DbRelationship relationship = new DbRelationship(fkName + "_to_" + referenced.getName());
        relationship.setTargetEntityName(referenced.getName());
        dependent.addRelationship(relationship);
        relationship.addJoin(new DbJoin(relationship, fkName, "id"));

        when(entityResolver.getDbEntities()).thenReturn(List.of(referenced, dependent));
        return fk;
    }

    private Persistent mockObject(ObjectId id) {
        Persistent persistent = mock(Persistent.class);
        when(persistent.getObjectId()).thenReturn(id);
        when(persistent.getPersistenceState()).thenReturn(PersistenceState.MODIFIED);
        return persistent;
    }

    private DbEntity mockEntity(String name) {
        DbAttribute attribute1 = new DbAttribute("id");
        attribute1.setPrimaryKey(true);
        DbAttribute attribute2 = new DbAttribute("attr");
        DbEntity testEntity = new DbEntity(name);
        testEntity.addAttribute(attribute1);
        testEntity.addAttribute(attribute2);
        return testEntity;
    }
}