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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cayenne.exp.path.CayennePath;
import org.apache.cayenne.map.JoinType;

/**
 * Removes unused joins from the table tree before {@link TableTreeStage}
 * generates the FROM clause. A join is unused when its alias is never
 * referenced by any content-producing stage and removing it would not
 * change the query result.
 *
 * @since 5.0
 */
class TableTreePruningStage implements TranslationStage {

    private static final Comparator<PruneCandidate> DEEPEST_FIRST = Comparator.comparingInt(
        (PruneCandidate c) -> c.node.getAttributePath().length()
    ).reversed();

    @Override
    public void perform(TranslatorContext context) {
        TableTree tree = context.getTableTree();
        if (tree.getNodeCount() <= 1) {
            return;
        }
        tree.prune(computePrunableNodes(tree));
    }

    private static Set<CayennePath> computePrunableNodes(TableTree tree) {
        Map<CayennePath, PruneCandidate> candidates = new LinkedHashMap<>();
        tree.visit(node -> {
            if (node.getRelationship() == null) {
                return;
            }
            candidates.put(node.getAttributePath(), new PruneCandidate(node));
            PruneCandidate parent = candidates.get(node.getAttributePath().parent());
            if (parent != null) {
                parent.childCount++;
            }
        });

        List<PruneCandidate> sorted = new ArrayList<>(candidates.values());
        sorted.sort(DEEPEST_FIRST);

        Set<CayennePath> prunable = new HashSet<>();
        for (PruneCandidate candidate : sorted) {
            if (candidate.childCount > 0 || !isPrunableJoin(candidate.node, tree)) {
                continue;
            }
            prunable.add(candidate.node.getAttributePath());
            PruneCandidate parent = candidates.get(candidate.node.getAttributePath().parent());
            if (parent != null) {
                parent.childCount--;
            }
        }
        return prunable;
    }

    private static boolean isPrunableJoin(TableTreeNode node, TableTree tree) {
        return !tree.isAliasUsed(node.getTableAlias())
                && !mayChangeRowCardinality(node)
                && node.getEntity().getQualifier() == null
                && node.getAdditionalQualifier() == null;
    }

    /**
     * Returns whether removing this join could change the number of rows in
     * the result. An INNER join may filter rows out unless the target is
     * guaranteed to exist (master PK).
     */
    private static boolean mayChangeRowCardinality(TableTreeNode node) {
        if (node.getRelationship().isToMany()) {
            return true;
        }
        if (node.getJoinType() == JoinType.LEFT_OUTER) {
            return false;
        }
        return !node.getRelationship().isToMasterPK();
    }

    private static class PruneCandidate {
        final TableTreeNode node;
        int childCount = 0;

        PruneCandidate(TableTreeNode node) {
            this.node = node;
        }
    }
}
