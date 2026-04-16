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
import org.apache.cayenne.map.DbAttribute;
import org.apache.cayenne.map.DbEntity;
import org.apache.cayenne.map.DbJoin;
import org.apache.cayenne.map.DbRelationship;
import org.apache.cayenne.map.EntityResolver;
import org.apache.cayenne.map.JoinType;
import org.apache.cayenne.util.RelationshipPredicates;

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

    // the relationship of each redirected path, which a walk up the path steps over in place of a node's
    private final Map<CayennePath, DbRelationship> redirectedRelationships;
    private final TableTreeNode root;
    private final EntityResolver resolver;

    JoinRedirectProcessor(NodeLookup nodeLookup, TableTreeNode root, EntityResolver resolver) {
        this.nodeLookup = nodeLookup;
        this.redirects = new HashMap<>();
        this.redirectedRelationships = new HashMap<>();
        this.root = root;
        this.resolver = resolver;
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
            redirectedRelationships.put(path, relationship);
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
     * guaranteed to be preserved across the chain. An outer step may find no
     * row, which the redirected alias would not reflect, so every outer step
     * must lead from a vertical inheritance sub-table row to its super-table
     * row, which is assumed to exist (see
     * {@link RelationshipPredicates#isVerticalInheritanceLink}). The joins must also carry
     * each primary key column of the matching ancestor back onto the same
     * column: a compound key joined with its columns swapped reaches another
     * row of the same table. This handles chains of any depth (e.g. child ->
     * parent -> grandparent -> parent -> child).
     * <p>
     * An outer-joined ancestor is a target only when every join below it, the
     * new one included, is outer too: an inner join below it drops the rows
     * that have no row of the ancestor, which the redirected alias would keep.
     * No table of the round trip may have a DbEntity qualifier, and no join of
     * it a join qualifier of its own (such as the subclass qualifier of a
     * prefetch), as either filters rows that the shared primary key doesn't
     * account for.
     * <p>
     * A relationship declared to-many is not redirected: the query reads
     * distinct rows when it joins over one, which it would not do with the
     * join redirected.
     *
     * @return a redirect to the matching ancestor, or {@code null} if no round-trip is detected
     */
    private AliasRedirect findInheritanceRoundTrip(CayennePath path, DbRelationship relationship, JoinType joinType) {
        if (relationship.isToMany() || !RelationshipPredicates.isSharedPrimaryKey(relationship)) {
            return null;
        }

        DbEntity targetEntity = relationship.getTargetEntity();
        if (targetEntity.getQualifier() != null) {
            return null;
        }

        boolean innerJoinBelow = joinType == JoinType.INNER;
        KeyColumnMapping keyColumns = KeyColumnMapping.of(relationship);
        CayennePath ancestor = path.parent();

        while (!ancestor.isEmpty()) {
            AliasRedirect existingRedirect = redirects.get(ancestor);
            if (existingRedirect != null) {
                if (existingRedirect.entity == targetEntity) {
                    boolean sameRow = keyColumns.isIdentity();
                    return sameRow && isRedirectTarget(existingRedirect.outerJoined, innerJoinBelow)
                            ? existingRedirect
                            : null;
                }
                DbRelationship redirectedRelationship = redirectedRelationships.get(ancestor);
                if (!RelationshipPredicates.isSharedPrimaryKey(redirectedRelationship)) {
                    return null;
                }
                keyColumns = keyColumns.through(redirectedRelationship);
                ancestor = ancestor.parent();
                continue;
            }

            TableTreeNode ancestorNode = nodeLookup.at(ancestor);
            if (ancestorNode == null) {
                return null;
            }
            if (ancestorNode.getAdditionalQualifier() != null) {
                return null;
            }
            boolean ancestorOuterJoined = ancestorNode.getJoinType() != JoinType.INNER;
            if (ancestorNode.getEntity() == targetEntity) {
                boolean sameRow = keyColumns.isIdentity();
                return sameRow && isRedirectTarget(ancestorOuterJoined, innerJoinBelow)
                        ? new AliasRedirect(ancestorNode.getTableAlias(), targetEntity, ancestorOuterJoined)
                        : null;
            }
            DbRelationship step = ancestorNode.getRelationship();
            if (step == null || !RelationshipPredicates.isSharedPrimaryKey(step)) {
                return null;
            }
            if (ancestorNode.getEntity().getQualifier() != null) {
                return null;
            }
            if (joinType == JoinType.INNER && ancestorOuterJoined) {
                return null;
            }
            if (ancestorOuterJoined && !resolver.isVerticalInheritanceLink(step)) {
                return null;
            }
            if (!ancestorOuterJoined) {
                innerJoinBelow = true;
            }
            keyColumns = keyColumns.through(step);
            ancestor = ancestor.parent();
        }

        if (targetEntity == root.getEntity() && keyColumns.isIdentity()) {
            return new AliasRedirect(root.getTableAlias(), targetEntity, false);
        }
        return null;
    }

    private static boolean isRedirectTarget(boolean targetOuterJoined, boolean innerJoinBelow) {
        return !targetOuterJoined || !innerJoinBelow;
    }

    /**
     * Checks whether the given path and relationship would create a redundant
     * FK round-trip: A -> B (via FK) -> A (via reverse FK). When B was joined
     * from A via an INNER join, the reverse always returns the original A row.
     * <p>
     * Only reverse joins on the full primary key of A are eligible, since
     * any other reverse could return multiple rows: a toMany one, and a
     * toOne one over columns that only the mapping declares unique. Only
     * INNER parent joins are eligible, since a LEFT OUTER parent could
     * produce NULLs where the redirect would not.
     *
     * @return a redirect to the grandparent alias, or {@code null} if no round-trip is detected
     */
    private AliasRedirect findFkRoundTrip(CayennePath path, DbRelationship relationship) {
        if (relationship.isToMany() || !RelationshipPredicates.isToFullPrimaryKey(relationship)) {
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
                return new AliasRedirect(root.getTableAlias(), expectedEntity, false);
            }
            return null;
        }

        AliasRedirect redirect = redirects.get(path);
        if (redirect != null && redirect.entity == expectedEntity) {
            return redirect;
        }

        TableTreeNode node = nodeLookup.at(path);
        if (node != null && node.getEntity() == expectedEntity) {
            return new AliasRedirect(node.getTableAlias(), expectedEntity, node.getJoinType() != JoinType.INNER);
        }

        return null;
    }

    /**
     * Maps each primary key column of a row on the walked path to the primary key column of the new join's row that
     * the shared primary key joins between the two carry its value to.
     */
    private static final class KeyColumnMapping {

        private final Map<DbAttribute, DbAttribute> newRowColumns;

        private KeyColumnMapping(Map<DbAttribute, DbAttribute> newRowColumns) {
            this.newRowColumns = newRowColumns;
        }

        /**
         * Returns the mapping from the source row of a shared primary key relationship to its target row.
         */
        static KeyColumnMapping of(DbRelationship relationship) {
            Map<DbAttribute, DbAttribute> columns = new HashMap<>();
            for (DbJoin join : relationship.getJoins()) {
                columns.put(join.getSource(), join.getTarget());
            }
            return new KeyColumnMapping(columns);
        }

        /**
         * Returns the mapping from the source row of a shared primary key relationship whose target is this mapping's
         * row.
         */
        KeyColumnMapping through(DbRelationship relationship) {
            Map<DbAttribute, DbAttribute> columns = new HashMap<>();
            for (DbJoin join : relationship.getJoins()) {
                columns.put(join.getSource(), newRowColumns.get(join.getTarget()));
            }
            return new KeyColumnMapping(columns);
        }

        /**
         * Returns whether each column maps onto itself, so that a row of the new join's table reached this way is the
         * row the mapping starts from.
         */
        boolean isIdentity() {
            for (Map.Entry<DbAttribute, DbAttribute> column : newRowColumns.entrySet()) {
                if (column.getKey() != column.getValue()) {
                    return false;
                }
            }
            return true;
        }
    }
}