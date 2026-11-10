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
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.ObjRelationship;

import java.util.ArrayList;
import java.util.List;

/**
 * Names the columns that carry the key of a to-one relationship in a row fetched for its source entity. The SELECT
 * fetches these columns through path translation ({@code DbPathProcessor.processRelTermination}). This class names
 * them for a joint prefetch ({@code PrefetchProcessorJointNode}) to read back out of the rows of the query it is a
 * part of, and its test pins those names to that translation.
 *
 * @since 5.0
 */
public final class ToOneKeyColumns {

    private ToOneKeyColumns() {
    }

    /**
     * Returns whether a row fetched for the source entity of a to-one carries its key: the FK, when the source holds
     * it, or, for a flattened path whose first hop goes to a PK, the key at the end of the path, fetched through the
     * path joins under path-qualified names (CAY-2911). An inverse one-to-one has no column of its own in the row,
     * and neither has a flattened path entering through a join table.
     */
    public static boolean inSourceRow(ObjRelationship toOne) {
        return toOne.isFlattened()
                ? toOne.getDbRelationships().getFirst().isToPK()
                : toOne.isFromFK();
    }

    /**
     * Returns the path-qualified names, relative to the source entity, of the key of a flattened to-one in a row
     * fetched for its source entity, or an empty list if the relationship is not a flattened to-one or the row does
     * not carry its key. These are the columns the translation of the path ends with (see
     * {@code DbPathProcessor.processRelTermination}): the source columns of the last hop where it goes to a PK, under
     * the path of the hops before it, e.g. "impl.OTHER1_ID" for "impl.other1", and otherwise the PK of the last
     * hop's target under the full path.
     * <p>
     * The translation also fetches the PK of every table the path passes through. Those are not listed here, as they
     * are the PKs of the additional DB entities of the source entity's descriptor.
     */
    public static List<CayennePath> flattenedKeyPaths(ObjRelationship toOne) {
        if (!toOne.isFlattened() || toOne.isToMany() || !inSourceRow(toOne)) {
            return List.of();
        }

        List<DbRelationship> hops = toOne.getDbRelationships();
        int lastIndex = hops.size() - 1;
        CayennePath path = CayennePath.EMPTY_PATH;
        for (int i = 0; i < lastIndex; i++) {
            path = path.dot(hops.get(i).getName());
        }

        // no hop of a to-one is to-many, so only the direction of the last one matters
        DbRelationship last = hops.get(lastIndex);
        List<CayennePath> key = new ArrayList<>();
        if (last.isToPK()) {
            for (DbJoin join : last.getJoins()) {
                key.add(path.dot(join.getSourceName()));
            }
        } else {
            CayennePath targetPath = path.dot(last.getName());
            for (DbAttribute pk : last.getTargetEntity().getPrimaryKeys()) {
                key.add(targetPath.dot(pk.getName()));
            }
        }
        return key;
    }
}
