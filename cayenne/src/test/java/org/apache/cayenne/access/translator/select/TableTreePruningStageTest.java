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

package org.apache.cayenne.access.translator.select;

import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.JoinType;
import org.apache.cayenne.query.FluentSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TableTreePruningStageTest {

    private DbEntity order;
    private DbEntity customer;
    private DbEntity orderNote;

    /**
     * ORDER_TABLE references CUSTOMER by its primary key and also, by a to-one over non-key columns, by its CODE.
     * ORDER_NOTE shares the primary key of ORDER_TABLE as its dependent.
     */
    @BeforeEach
    public void createEntities() {
        DataMap map = new DataMap("pruning");

        order = new DbEntity("ORDER_TABLE");
        order.addAttribute(attribute("ID", true));
        order.addAttribute(attribute("CUSTOMER_ID", false));
        order.addAttribute(attribute("CUSTOMER_CODE", false));
        map.addDbEntity(order);

        customer = new DbEntity("CUSTOMER");
        customer.addAttribute(attribute("ID", true));
        customer.addAttribute(attribute("CODE", false));
        map.addDbEntity(customer);

        orderNote = new DbEntity("ORDER_NOTE");
        orderNote.addAttribute(attribute("ORDER_ID", true));
        map.addDbEntity(orderNote);

        order.addRelationship(relationship("customer", order, customer, "CUSTOMER_ID", "ID"));
        order.addRelationship(relationship("customerByCode", order, customer, "CUSTOMER_CODE", "CODE"));
        DbRelationship note = relationship("note", order, orderNote, "ID", "ORDER_ID");
        note.setToDependentPK(true);
        order.addRelationship(note);
        orderNote.addRelationship(relationship("order", orderNote, order, "ORDER_ID", "ID"));
    }

    @Test
    public void unusedOuterJoinOnTheFullPrimaryKeyIsPruned() {
        assertEquals(1, nodeCountAfterPruning(order, "customer", JoinType.LEFT_OUTER));
    }

    /**
     * A to-one over non-key columns may match several rows, which the join would multiply.
     */
    @Test
    public void unusedOuterJoinOnNonKeyColumnsIsKept() {
        assertEquals(2, nodeCountAfterPruning(order, "customerByCode", JoinType.LEFT_OUTER));
    }

    /**
     * An inner join to the master row drops a dependent row whose master row is missing.
     */
    @Test
    public void unusedInnerJoinToTheMasterIsKept() {
        assertEquals(2, nodeCountAfterPruning(orderNote, "order", JoinType.INNER));
    }

    private static int nodeCountAfterPruning(DbEntity root, String relationshipName, JoinType joinType) {
        FluentSelect<?, ?> query = new MockFluentSelectBuilder()
                .withMetaData(new MockQueryMetadataBuilder().withDbEntity(root).build())
                .build();
        SelectTranslatorContext context = new MockSelectTranslatorContext(query);

        context.getTableTree().addJoinTable(CayennePath.of(relationshipName),
                root.getRelationship(relationshipName), joinType);
        new TableTreePruningStage().perform(context);
        return context.getTableTree().getNodeCount();
    }

    private static DbRelationship relationship(String name, DbEntity source, DbEntity target,
                                               String sourceColumn, String targetColumn) {
        DbRelationship relationship = new DbRelationship(name);
        relationship.setSourceEntity(source);
        relationship.setTargetEntityName(target);
        relationship.addJoin(new DbJoin(relationship, sourceColumn, targetColumn));
        return relationship;
    }

    private static DbAttribute attribute(String name, boolean primaryKey) {
        DbAttribute attribute = new DbAttribute(name, Types.INTEGER, null);
        attribute.setPrimaryKey(primaryKey);
        return attribute;
    }
}
