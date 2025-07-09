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

import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.reflect.AdditionalDbEntityDescriptor;
import org.apache.cayenne.reflect.ClassDescriptor;
import org.apache.cayenne.unit.CayenneProjects;
import org.apache.cayenne.unit.CayenneTestsEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Condition 4's whole point, against real descriptors: the <strong>same route</strong> gets opposite answers
 * depending on which entity is asked, and the descriptor decides.
 * <p>
 * {@code PAINTING_INFO} is a shared-primary-key dependent of {@code PAINTING}, so it is one of the six
 * shared-PK-to-master hops across the test maps whose intermediate is a <em>distinct object</em> rather than a
 * vertical-inheritance sub-table. Flipping such a row to an {@code INSERT} would fabricate another entity's row.
 * Conditions 1 to 3 cannot tell the difference -- the key is fully determined and the target may well be
 * {@code NEW} -- and condition 4 separates them without any special-casing:
 * <ul>
 *     <li>{@code Painting} declares no flattened property through {@code toPaintingInfo}, so the route does not
 *     resolve and no flip happens. {@code PaintingInfo} is its own entity there;</li>
 *     <li>{@code CompoundPainting} maps the flattened attribute {@code textReview}
 *     ({@code toPaintingInfo.TEXT_REVIEW}), so {@code PAINTING_INFO} genuinely is one of its storage tables, the
 *     route resolves, and the flip would be correct.</li>
 * </ul>
 * None of these hops is currently reachable as a flattened-path remainder, so the false positive is latent rather
 * than live -- but it is unguarded without condition 4.
 */
public class FlattenedRouteOwnershipIT {

    @RegisterExtension
    static final CayenneTestsEnv env = CayenneTestsEnv.forProject(CayenneProjects.TESTMAP_PROJECT);

    private static final CayennePath TO_PAINTING_INFO = CayennePath.of("toPaintingInfo");

    @Test
    public void paintingDoesNotDeclarePaintingInfoAsItsOwnStorage() {
        ClassDescriptor painting = descriptor("Painting");

        assertFalse(painting.getAdditionalDbEntities().containsKey(TO_PAINTING_INFO),
                "Painting has no flattened property through toPaintingInfo, so PAINTING_INFO is a distinct "
                        + "entity's row and the route must not resolve");
    }

    @Test
    public void compoundPaintingDeclaresPaintingInfoAsItsOwnStorage() {
        ClassDescriptor compoundPainting = descriptor("CompoundPainting");

        AdditionalDbEntityDescriptor declared = compoundPainting.getAdditionalDbEntities().get(TO_PAINTING_INFO);
        assertNotNull(declared, "CompoundPainting maps the flattened attribute textReview through toPaintingInfo");
        assertEquals("PAINTING_INFO", declared.getDbEntity().getName(),
                "and the resolved entry must name PAINTING_INFO");
    }

    /**
     * The two answers are given for the identical route, which is the property that makes route resolution
     * positive identification rather than a heuristic.
     */
    @Test
    public void theSameRouteResolvesForOneEntityAndNotTheOther() {
        assertTrue(descriptor("CompoundPainting").getAdditionalDbEntities().containsKey(TO_PAINTING_INFO));
        assertFalse(descriptor("Painting").getAdditionalDbEntities().containsKey(TO_PAINTING_INFO));
    }

    private static ClassDescriptor descriptor(String entityName) {
        ClassDescriptor descriptor = env.runtime().newContext().getEntityResolver().getClassDescriptor(entityName);
        assertNotNull(descriptor, "precondition: " + entityName + " is mapped");
        return descriptor;
    }
}
