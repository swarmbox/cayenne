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

import java.util.HashMap;
import java.util.Map;

import org.apache.cayenne.exp.Expression;
import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.JoinType;

/**
 * Detects, registers, and looks up redirects for table joins added to a
 * {@link TableTree}. When a join would duplicate a row already reachable via
 * an existing alias, the processor returns (and remembers) a redirect to that
 * alias instead of requiring a new JOIN.
 * <p>
 * Two round-trip patterns are recognized:
 * <ul>
 *   <li><b>Vertical inheritance chain</b> (A -> B shared PK -> A):
 *       the shared primary key guarantees the same row across the chain.
 *   <li><b>FK round-trip on INNER joins</b> (A -> B via FK -> A via
 *       reverse FK): the reverse always returns the originating row.
 * </ul>
 *
 * @since 5.0
 */
class JoinRedirectProcessor {

    private final NodeLookup nodeLookup;
    private final Map<CayennePath, AliasRedirect> redirects;
    private final TableTreeNode root;

    JoinRedirectProcessor(NodeLookup nodeLookup, TableTreeNode root) {
        this.nodeLookup = nodeLookup;
        this.redirects = new HashMap<>();
        this.root = root;
    }

    @FunctionalInterface
    interface NodeLookup {
        TableTreeNode at(CayennePath path);
    }

    AliasRedirect redirectFor(CayennePath path) {
        return redirects.get(path);
    }

    /**
     * Runs detection for the given path and relationship. If a round-trip
     * pattern is identified, registers and returns an {@link AliasRedirect}
     * pointing at the existing alias. Otherwise returns {@code null}.
     */
    AliasRedirect detect(CayennePath path, DbRelationship relationship, JoinType joinType, Expression additionalQualifier) {
        if (additionalQualifier != null) {
            return null;
        }
        AliasRedirect redirect = findInheritanceRoundTrip(path, relationship, joinType);
        if (redirect == null) {
            redirect = findFkRoundTrip(path, relationship);
        }
        if (redirect != null) {
            redirects.put(path, redirect);
        }
        return redirect;
    }

    /**
     * Detects redundant round-trips through vertical inheritance chains:
     * A -> B (shared PK) -> A, where the second join to A is redundant
     * because the shared primary key guarantees the same row.
     * <p>
     * Walks up the ancestor chain from {@code path} looking for a node whose
     * entity matches the target of {@code relationship}. Every step in the
     * walk must be a shared primary key join so that the primary key value is
     * guaranteed to be preserved across the chain. This handles chains of any
     * depth (e.g. child -> parent -> grandparent -> parent -> child).
     *
     * @return a redirect to the matching ancestor, or {@code null} if no round-trip is detected
     */
    private AliasRedirect findInheritanceRoundTrip(CayennePath path, DbRelationship relationship, JoinType joinType) {
        if (!relationship.isSharedPrimaryKey()) {
            return null;
        }

        DbEntity targetEntity = relationship.getTargetEntity();
        CayennePath ancestor = path.parent();

        while (!ancestor.isEmpty()) {
            AliasRedirect existingRedirect = redirects.get(ancestor);
            if (existingRedirect != null) {
                if (existingRedirect.entity == targetEntity) {
                    return existingRedirect;
                }
                ancestor = ancestor.parent();
                continue;
            }

            TableTreeNode ancestorNode = nodeLookup.at(ancestor);
            if (ancestorNode == null) {
                return null;
            }
            if (ancestorNode.getEntity() == targetEntity) {
                return new AliasRedirect(ancestorNode.getTableAlias(), targetEntity);
            }
            if (ancestorNode.getRelationship() == null || !ancestorNode.getRelationship().isSharedPrimaryKey()) {
                return null;
            }
            if (joinType == JoinType.INNER && ancestorNode.getJoinType() != JoinType.INNER) {
                return null;
            }
            ancestor = ancestor.parent();
        }

        if (targetEntity == root.getEntity()) {
            return new AliasRedirect(root.getTableAlias(), targetEntity);
        }
        return null;
    }

    /**
     * Checks whether the given path and relationship would create a redundant
     * FK round-trip: A -> B (via FK) -> A (via reverse FK). When B was joined
     * from A via an INNER join, the reverse always returns the original A row.
     * <p>
     * Only toOne reverse joins are eligible, since a toMany reverse could
     * return multiple rows. Only INNER parent joins are eligible, since a
     * LEFT OUTER parent could produce NULLs where the redirect would not.
     *
     * @return a redirect to the grandparent alias, or {@code null} if no round-trip is detected
     */
    private AliasRedirect findFkRoundTrip(CayennePath path, DbRelationship relationship) {
        if (relationship.isToMany()) {
            return null;
        }

        CayennePath parentPath = path.parent();
        if (parentPath.isEmpty()) {
            return null;
        }

        TableTreeNode parentNode = nodeLookup.at(parentPath);
        if (parentNode == null || parentNode.getRelationship() == null) {
            return null;
        }

        if (parentNode.getJoinType() != JoinType.INNER) {
            return null;
        }

        if (relationship != parentNode.getRelationship().getReverseRelationship()) {
            return null;
        }

        return getOrCreateRedirect(parentPath.parent(), relationship.getTargetEntity());
    }

    private AliasRedirect getOrCreateRedirect(CayennePath path, DbEntity expectedEntity) {
        if (path.isEmpty()) {
            if (expectedEntity == root.getEntity()) {
                return new AliasRedirect(root.getTableAlias(), expectedEntity);
            }
            return null;
        }

        AliasRedirect redirect = redirects.get(path);
        if (redirect != null && redirect.entity == expectedEntity) {
            return redirect;
        }

        TableTreeNode node = nodeLookup.at(path);
        if (node != null && node.getEntity() == expectedEntity) {
            return new AliasRedirect(node.getTableAlias(), expectedEntity);
        }

        return null;
    }
}