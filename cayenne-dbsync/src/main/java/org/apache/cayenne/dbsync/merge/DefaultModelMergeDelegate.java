/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *    or more contributor license agreements.  See the NOTICE file
 *    distributed with this work for additional information
 *    regarding copyright ownership.  The ASF licenses this file
 *    to you under the Apache License, Version 2.0 (the
 *    "License"); you may not use this file except in compliance
 *    with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing,
 *    software distributed under the License is distributed on an
 *    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *    KIND, either express or implied.  See the License for the
 *    specific language governing permissions and limitations
 *    under the License.
 */
package org.apache.cayenne.dbsync.merge;

import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjAttribute;
import org.apache.cayenne.map.ObjEntity;
import org.apache.cayenne.map.ObjRelationship;

/**
 * A default noop implementation of {@link ModelMergeDelegate}.
 */
public class DefaultModelMergeDelegate implements ModelMergeDelegate {

    @Override
    public void dbAttributeAdded(DbAttribute att) {
    }

    @Override
    public void dbAttributeModified(DbAttribute att) {
    }

    @Override
    public void dbAttributeRemoved(DbAttribute att) {
    }

    @Override
    public void dbEntityAdded(DbEntity ent) {
    }

    @Override
    public void dbEntityRemoved(DbEntity ent) {
    }

    @Override
    public void dbRelationshipAdded(DbRelationship rel) {
    }

    @Override
    public void dbRelationshipRemoved(DbRelationship rel) {
    }

    @Override
    public void objAttributeAdded(ObjAttribute att) {
    }

    @Override
    public void objAttributeModified(ObjAttribute att) {
    }

    @Override
    public void objAttributeRemoved(ObjAttribute att) {
    }

    @Override
    public void objEntityAdded(ObjEntity ent) {
    }

    @Override
    public void objEntityRemoved(ObjEntity ent) {
    }

    @Override
    public void objRelationshipAdded(ObjRelationship rel) {
    }

    @Override
    public void objRelationshipRemoved(ObjRelationship rel) {
    }

}
