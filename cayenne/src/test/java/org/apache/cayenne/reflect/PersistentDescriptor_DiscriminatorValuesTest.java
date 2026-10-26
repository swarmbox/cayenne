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

package org.apache.cayenne.reflect;

import java.sql.Types;

import org.apache.cayenne.GenericPersistentObject;
import org.apache.cayenne.ObjectId;
import org.apache.cayenne.access.types.MockEnum3;
import org.apache.cayenne.access.types.ValueObjectTypeRegistry;
import org.apache.cayenne.di.spi.DefaultAdhocObjectFactory;
import org.apache.cayenne.di.spi.DefaultClassLoaderManager;
import org.apache.cayenne.exp.ExpressionFactory;
import org.apache.cayenne.map.DataMap;
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.ObjAttribute;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.reflect.generic.DefaultValueComparisonStrategyFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

public class PersistentDescriptor_DiscriminatorValuesTest {

    @Test
    public void enumLiteralOnExtendedEnumeration() {
        DataMap dataMap = new DataMap("map");

        DbEntity dbEntity = new DbEntity("TABLE");
        DbAttribute id = new DbAttribute("ID", Types.INTEGER, dbEntity);
        id.setPrimaryKey(true);
        dbEntity.addAttribute(id);
        dbEntity.addAttribute(new DbAttribute("TYPE", Types.INTEGER, dbEntity));
        dataMap.addDbEntity(dbEntity);

        // MockEnum3.B is stored as 2, but the qualifier names the constant itself
        ObjEntity entity = new ObjEntity("Entity");
        entity.setDbEntityName("TABLE");
        ObjAttribute type = new ObjAttribute("type", MockEnum3.class.getName(), entity);
        type.setDbAttributePath("TYPE");
        entity.addAttribute(type);
        entity.setDeclaredQualifier(ExpressionFactory.exp("type = enum:org.apache.cayenne.access.types.MockEnum3.B"));
        dataMap.addObjEntity(entity);

        EntityResolver resolver = new EntityResolver();
        resolver.addDataMap(dataMap);
        resolver.setValueComparisonStrategyFactory(new DefaultValueComparisonStrategyFactory(
                mock(ValueObjectTypeRegistry.class)));
        resolver.setObjectFactory(new DefaultAdhocObjectFactory(null, new DefaultClassLoaderManager()));

        GenericPersistentObject object = new GenericPersistentObject();
        object.setObjectId(ObjectId.of("Entity"));
        resolver.getClassDescriptor("Entity").injectDiscriminatorValues(object);

        assertSame(MockEnum3.B, object.readPropertyDirectly("type"));
    }
}
