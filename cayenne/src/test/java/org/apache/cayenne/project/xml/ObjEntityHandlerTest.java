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

package org.apache.cayenne.project.xml;

import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.ObjAttribute;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.project.EmptyProjectNodeVisitor;
import org.apache.cayenne.util.XMLEncoder;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ObjEntityHandlerTest extends BaseHandlerTest {

    private DataMap parseMap(String... objEntities) throws Exception {
        DataMap map = new DataMap();
        for (String xml : objEntities) {
            parse("objEntity", xml, parent -> new ObjEntityHandler(parent, map));
        }
        return map;
    }

    private static String encode(ObjEntity entity) {
        StringWriter out = new StringWriter();
        entity.encodeAsXML(new XMLEncoder(new PrintWriter(out)), new EmptyProjectNodeVisitor());
        return out.toString();
    }

    @Test
    public void load() throws Exception {

        final DataMap map = new DataMap();
        assertEquals(0, map.getObjEntities().size());

        parse("objEntity", parent -> new ObjEntityHandler(parent, map));

        assertEquals(1, map.getObjEntities().size());
        ObjEntity entity = map.getObjEntity("ArtistCallback");
        assertNotNull(entity);
        assertTrue(entity.isAbstract());
        assertTrue(entity.isReadOnly());
        assertEquals(3, entity.getAttributes().size());
        assertEquals(8, entity.getCallbackMethods().size());
        assertEquals(ObjEntity.LOCK_TYPE_OPTIMISTIC, entity.getDeclaredLockType());
        assertEquals("org.apache.cayenne.testdo.testmap.ArtistCallback", entity.getClassName());
        assertEquals("super.class", entity.getSuperClassName(), "a declared superclass is kept on an inherited entity");
        assertEquals("Artist", entity.getSuperEntityName());
        assertEquals("ARTIST_CT", entity.getDbEntityName());

        ObjAttribute attribute = entity.getAttribute("artistName");
        assertNotNull(attribute);
        assertEquals("NAME", attribute.getDbAttributeName());
        assertEquals("java.lang.String", attribute.getType());
        assertTrue(attribute.isUsedForLocking());

        attribute = entity.getAttribute("dateOfBirth");
        assertNotNull(attribute);
        assertNull(attribute.getDbAttributeName());
        assertEquals("java.util.Date", attribute.getType());
        assertFalse(attribute.isUsedForLocking());

        attribute = entity.getAttribute("embeddable1");
        assertNotNull(attribute);
        assertNull(attribute.getDbAttributeName());
        assertEquals("org.apache.cayenne.testdo.embeddable.Embeddable1", attribute.getType());
        assertFalse(attribute.isUsedForLocking());

        CayennePath override = entity.getDeclaredAttributeOverrides().get("name");
        assertEquals("parent.child.name", override.value());
    }

    @Test
    public void superClassNameRoundTrip() throws Exception {
        DataMap map = parseMap("""
                <objEntity name="Parent" className="org.example.Parent"/>
                """, """
                <objEntity name="Inherited" superEntityName="Parent" className="org.example.Inherited"/>
                """, """
                <objEntity name="Overridden" superEntityName="Parent" className="org.example.Overridden"
                           superClassName="org.example.OverrideBase"/>
                """, """
                <objEntity name="Plain" className="org.example.Plain" superClassName="org.example.Base"/>
                """);

        assertEquals("org.example.Parent", map.getObjEntity("Inherited").getSuperClassName());
        assertEquals("org.example.OverrideBase", map.getObjEntity("Overridden").getSuperClassName());
        assertEquals("org.example.Base", map.getObjEntity("Plain").getSuperClassName());

        // saving writes the inherited superclass of an entity that declares none
        assertTrue(encode(map.getObjEntity("Inherited")).contains("superClassName=\"org.example.Parent\""));

        DataMap reloaded = parseMap(map.getObjEntities().stream().map(ObjEntityHandlerTest::encode)
                .toArray(String[]::new));
        for (String name : List.of("Inherited", "Overridden", "Plain")) {
            ObjEntity entity = map.getObjEntity(name);
            ObjEntity reloadedEntity = reloaded.getObjEntity(name);
            assertEquals(entity.getSuperClassName(), reloadedEntity.getSuperClassName(), name);
            assertEquals(encode(entity), encode(reloadedEntity), name);
        }
    }
}