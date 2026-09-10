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

package org.apache.cayenne.access;

import org.apache.cayenne.query.ObjectSelect;
import org.apache.cayenne.testdo.testmap.Artist;
import org.apache.cayenne.testdo.testmap.Painting;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public class DataContextValidationIT {

    /**
     * Enough objects that a hash-ordered map is overwhelmingly unlikely to iterate them in the order they were
     * changed by coincidence.
     */
    private static final int ORDER_PROBE_SIZE = 32;

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);

    private DataContext newContext(boolean validatingObjectsOnCommit) {
        DataDomain domain = env.runtime().getDataDomain();
        return DataContext.builder(domain)
                .snapshotCache(domain.getSharedSnapshotCache())
                .validatingObjectsOnCommit(validatingObjectsOnCommit)
                .build();
    }

    @Test
    public void validatingObjectsOnCommitProperty() {
        assertTrue(newContext(true).isValidatingObjectsOnCommit());
        assertFalse(newContext(false).isValidatingObjectsOnCommit());
    }

    @Test
    public void validatingObjectsOnCommit() {
        // test that validation is called properly

        DataContext validating = newContext(true);
        Artist a1 = validating.newObject(Artist.class);
        a1.setArtistName("a1");
        validating.commitChanges();
        assertTrue(a1.isValidateForSaveCalled());

        DataContext nonValidating = newContext(false);
        Artist a2 = nonValidating.newObject(Artist.class);
        a2.setArtistName("a2");
        nonValidating.commitChanges();
        assertFalse(a2.isValidateForSaveCalled());
    }

    @Test
    public void validationModifyingContext() {

        Consumer<Artist> callback = a -> {
            Painting p = a.getObjectContext().newObject(Painting.class);
            p.setPaintingTitle("XXX");
            p.setToArtist(a);
        };

        assertTrue(env.context().isValidatingObjectsOnCommit());
        Artist a1 = env.context().newObject(Artist.class);
        a1.setValidationCallback(callback);
        a1.setArtistName("a1");

        // add another artist to ensure that modifying context works when more than one
        // object is committed
        Artist a2 = env.context().newObject(Artist.class);
        a2.setValidationCallback(callback);
        a2.setArtistName("a2");
        env.context().commitChanges();

        assertEquals(2, ObjectSelect.query(Painting.class).select(env.context()).size());
    }

    /**
     * Validation is user code that can depend on the order it runs in, e.g. a {@code validateForInsert} that numbers
     * line items from a sequence. New objects are validated in the order they were created, not in the hash order
     * of their temporary ObjectIds.
     */
    @Test
    public void newObjectsAreValidatedInCreationOrder() {
        List<String> created = new ArrayList<>(ORDER_PROBE_SIZE);
        List<String> validated = new ArrayList<>(ORDER_PROBE_SIZE);

        for (int i = 0; i < ORDER_PROBE_SIZE; i++) {
            Artist artist = env.context().newObject(Artist.class);
            artist.setArtistName("a" + i);
            artist.setValidationCallback(a -> validated.add(a.getArtistName()));
            created.add(artist.getArtistName());
        }
        env.context().commitChanges();

        assertEquals(created, validated, "objects must be validated in creation order, not in ObjectId hash order");
    }

    /**
     * Modified objects are validated in the order they were first changed, which here is the reverse of their
     * primary key order, so neither key order nor key hash order can pass for it.
     */
    @Test
    public void modifiedObjectsAreValidatedInModificationOrder() {
        List<Artist> artists = new ArrayList<>(ORDER_PROBE_SIZE);
        for (int i = 0; i < ORDER_PROBE_SIZE; i++) {
            Artist artist = env.context().newObject(Artist.class);
            artist.setArtistName("a" + i);
            artists.add(artist);
        }
        env.context().commitChanges();

        List<String> modified = new ArrayList<>(ORDER_PROBE_SIZE);
        List<String> validated = new ArrayList<>(ORDER_PROBE_SIZE);
        for (Artist artist : artists.reversed()) {
            artist.setArtistName(artist.getArtistName() + "x");
            artist.setValidationCallback(a -> validated.add(a.getArtistName()));
            modified.add(artist.getArtistName());
        }
        env.context().commitChanges();

        assertEquals(modified, validated,
                "objects must be validated in the order they were changed, not in ObjectId hash order");
    }
}
