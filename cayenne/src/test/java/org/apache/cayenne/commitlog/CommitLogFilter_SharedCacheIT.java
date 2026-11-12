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

package org.apache.cayenne.commitlog;

import org.apache.cayenne.CayenneRuntimeBuilder;
import org.apache.cayenne.CoreModule;
import org.apache.cayenne.ObjectContext;
import org.apache.cayenne.Persistent;
import org.apache.cayenne.commitlog.db.Auditable4;
import org.apache.cayenne.commitlog.db.Auditable5;
import org.apache.cayenne.commitlog.db.AuditableChild5;
import org.apache.cayenne.commitlog.model.ChangeMap;
import org.apache.cayenne.commitlog.model.ObjectChange;
import org.apache.cayenne.commitlog.unit.AuditableRuntimeCase;
import org.apache.cayenne.query.ObjectSelect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The commit log of a context that commits after a peer context sharing the snapshot cache committed the same row,
 * before the committing context merged the peer's change, lists only the changes of the committing context.
 */
public class CommitLogFilter_SharedCacheIT extends AuditableRuntimeCase {

    private final Map<ObjectContext, ChangeMap> commitLogs = new ConcurrentHashMap<>();

    private ObjectContext context;
    private ObjectContext peerContext;

    @Override
    protected CayenneRuntimeBuilder configureCayenne() {
        CommitLogListener listener = commitLogs::put;
        return super.configureCayenne().addModule(b -> CoreModule.extend(b).addCommitLogListener(listener));
    }

    @BeforeEach
    public void before() {
        context = runtime.newContext();
        peerContext = runtime.newContext();
    }

    @Test
    public void peerAttributeChangeIsNotLogged() throws Exception {
        auditable4.insert(1, "p1", "p2", null);

        Auditable4 object = ObjectSelect.query(Auditable4.class).selectOne(context);
        object.setCharProperty1("mine");

        ObjectSelect.query(Auditable4.class).selectOne(peerContext).setCharProperty2("peer");
        peerContext.commitChanges();

        context.commitChanges();

        ObjectChange change = loggedChange(object);
        assertEquals(Set.of(Auditable4.CHAR_PROPERTY1.getName()), change.getAttributeChanges().keySet());
    }

    @Test
    public void peerToOneChangeIsNotLogged() throws Exception {
        auditable5.insert(1, "a5-1");
        auditable5.insert(2, "a5-2");
        auditableChild5.insert(1, 1, "c1");

        AuditableChild5 object = ObjectSelect.query(AuditableChild5.class).selectOne(context);
        assertEquals("a5-1", object.getParent().getCharProperty1());
        object.setCharProperty1("mine");

        AuditableChild5 peerObject = ObjectSelect.query(AuditableChild5.class).selectOne(peerContext);
        peerObject.setParent(ObjectSelect.query(Auditable5.class)
                .where(Auditable5.SELF.eqId(2))
                .selectOne(peerContext));
        peerContext.commitChanges();

        context.commitChanges();

        ObjectChange change = loggedChange(object);
        assertEquals(Set.of(AuditableChild5.CHAR_PROPERTY1.getName()), change.getAttributeChanges().keySet());
        assertEquals(Set.of(), change.getToOneRelationshipChanges().keySet());
    }

    private ObjectChange loggedChange(Persistent object) {
        ChangeMap changes = commitLogs.get(context);
        assertNotNull(changes, "no commit log");

        ObjectChange change = changes.getChanges().get(object.getObjectId());
        assertNotNull(change, "no change logged for " + object.getObjectId());
        return change;
    }
}
