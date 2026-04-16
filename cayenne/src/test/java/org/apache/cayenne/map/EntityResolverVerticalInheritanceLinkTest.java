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

package org.apache.cayenne.map;

import org.apache.cayenne.util.RelationshipPredicates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EntityResolverVerticalInheritanceLinkTest {

    private DataMap map;
    private EntityResolver resolver;
    private DbEntity sub;
    private DbRelationship subToBase;
    private DbRelationship noteToBase;

    /**
     * BASE has two dependents sharing its primary key: SUB, the sub-table of the Sub entity of the Base hierarchy,
     * and NOTE, which no inheritance hierarchy maps.
     */
    @BeforeEach
    public void createMapping() {
        map = new DataMap("inheritance");
        DbEntity base = dbEntity("BASE");
        sub = dbEntity("SUB");
        sub.addAttribute(new DbAttribute("NAME", Types.VARCHAR, sub));
        DbEntity note = dbEntity("NOTE");
        subToBase = sharedPrimaryKeyPair(base, sub);
        noteToBase = sharedPrimaryKeyPair(base, note);

        ObjEntity baseEntity = new ObjEntity("Base");
        baseEntity.setDbEntityName("BASE");
        map.addObjEntity(baseEntity);
        ObjEntity subEntity = subEntity("Sub", "Base");
        addAttribute(subEntity, "name", "sub.NAME");

        resolver = new EntityResolver(List.of(map));
    }

    @Test
    public void answersLikeThePredicate() {
        assertTrue(resolver.isVerticalInheritanceLink(subToBase));
        assertEquals(RelationshipPredicates.isVerticalInheritanceLink(subToBase),
                resolver.isVerticalInheritanceLink(subToBase));

        assertFalse(resolver.isVerticalInheritanceLink(noteToBase));
        assertEquals(RelationshipPredicates.isVerticalInheritanceLink(noteToBase),
                resolver.isVerticalInheritanceLink(noteToBase));
    }

    @Test
    public void keepsItsAnswerUntilTheMappingIsRefreshed() {
        assertTrue(resolver.isVerticalInheritanceLink(subToBase));

        map.removeObjEntity("Sub");
        assertFalse(RelationshipPredicates.isVerticalInheritanceLink(subToBase));
        assertTrue(resolver.isVerticalInheritanceLink(subToBase), "The answer should come from the cache");

        resolver.addDataMap(new DataMap("other"));
        assertFalse(resolver.isVerticalInheritanceLink(subToBase), "Adding a DataMap should clear the cache");
    }

    /**
     * Attributes whose db path doesn't resolve, by a missing column or a missing relationship, don't keep the other
     * attributes from answering.
     */
    @Test
    public void staleAttributePathsDoNotFailTheAnswer() {
        ObjEntity subEntity = map.getObjEntity("Sub");
        addAttribute(subEntity, "missingColumn", "sub.NO_SUCH_COLUMN");
        addAttribute(subEntity, "missingRelationship", "noSuchRelationship.NAME");

        assertTrue(RelationshipPredicates.isVerticalInheritanceLink(subToBase));
        assertFalse(RelationshipPredicates.isVerticalInheritanceLink(noteToBase));
    }

    /**
     * An attribute whose db path doesn't resolve maps over no relationship, even one its path starts with.
     */
    @Test
    public void relationshipOnlyAStalePathStartsWithIsNoLink() {
        ObjEntity noteEntity = subEntity("Note", "Base");
        addAttribute(noteEntity, "missingColumn", "note.NO_SUCH_COLUMN");

        assertFalse(RelationshipPredicates.isVerticalInheritanceLink(noteToBase));
    }

    /**
     * A sub-entity two levels down maps the root table, and reaches its own sub-table through the one above.
     */
    @Test
    public void linkOfASecondLevelSubTable() {
        DbEntity subSub = dbEntity("SUB_SUB");
        subSub.addAttribute(new DbAttribute("NAME", Types.VARCHAR, subSub));
        DbRelationship subSubToSub = sharedPrimaryKeyPair(sub, subSub);
        ObjEntity subSubEntity = subEntity("SubSub", "Sub");
        addAttribute(subSubEntity, "subSubName", "sub.sub_sub.NAME");

        assertTrue(RelationshipPredicates.isVerticalInheritanceLink(subSubToSub));
    }

    private ObjEntity subEntity(String name, String superEntityName) {
        ObjEntity entity = new ObjEntity(name);
        map.addObjEntity(entity);
        entity.setSuperEntityName(superEntityName);
        return entity;
    }

    private static void addAttribute(ObjEntity entity, String name, String dbAttributePath) {
        ObjAttribute attribute = new ObjAttribute(name, String.class.getName(), entity);
        attribute.setDbAttributePath(dbAttributePath);
        entity.addAttribute(attribute);
    }

    private DbEntity dbEntity(String name) {
        DbEntity entity = new DbEntity(name);
        DbAttribute id = new DbAttribute("ID", Types.INTEGER, entity);
        id.setPrimaryKey(true);
        entity.addAttribute(id);
        map.addDbEntity(entity);
        return entity;
    }

    // maps master -> dependent by the shared primary key, and returns dependent -> master
    private static DbRelationship sharedPrimaryKeyPair(DbEntity master, DbEntity dependent) {
        DbRelationship toDependent = new DbRelationship(dependent.getName().toLowerCase());
        toDependent.setSourceEntity(master);
        toDependent.setTargetEntityName(dependent);
        toDependent.setToDependentPK(true);
        toDependent.addJoin(new DbJoin(toDependent, "ID", "ID"));
        master.addRelationship(toDependent);

        DbRelationship toMaster = new DbRelationship("base");
        toMaster.setSourceEntity(dependent);
        toMaster.setTargetEntityName(master);
        toMaster.addJoin(new DbJoin(toMaster, "ID", "ID"));
        dependent.addRelationship(toMaster);
        return toMaster;
    }
}
