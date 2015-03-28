/**
 * Copyright (c) 2009-2011, The HATS Consortium. All rights reserved.
 * This file is licensed under the terms of the Modified BSD License.
 */
package abs.frontend.delta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ArrayTable;
import com.google.common.collect.Table;


/* Sorting object that computes a list of elements sorted according to a given partial order
 * Usage:
 *     1. instantiate giving all elements to be sorted
 *     2. define partial order by repeatedly calling addEdge(e1, e2), where e1 > e2
 *     3. call sort()
 *     4. obtain a valid order with getPreferredOrder() or getAnOrder()
 */
public class TopologicalSorting<T> {

    private final Set<T> nodes;
    private final Table<T, T, Boolean> incidence;
    List<Set<T>> partition;
    private List<T> preferredOrder;

    public TopologicalSorting(Set<T> nodes) {
        this.nodes = nodes;

        if (nodes.size() == 0) {
            preferredOrder = Collections.emptyList();
            incidence = null;
            return;
        }

        incidence = ArrayTable.create(nodes, nodes);
        for (T cn : nodes)
            for (T rn : nodes)
                incidence.put(cn, rn, false);

        partition = new ArrayList<Set<T>>();

    }

    /* The delta partition is an ordered list of sets of deltas. All deltas in a
     * certain set have the same precedence, that is, they can be applied in any order.
     * The partition is initially an empty list and is computed by calling sort().
     */
    public List<Set<T>> getPartition() {
        return partition;
    }

    public void addEdge(T high, T low) throws DeltaModellingException {
        if (incidence == null)
            throw new DeltaModellingException("Delta order: nodes not found [" + high.toString() + "; " + low.toString() + "] -- graph is empty");
        if (! incidence.containsColumn(high))
            throw new DeltaModellingException("Delta order: node not found [" + high.toString() + "]");
        if (! incidence.containsRow(low))
            throw new DeltaModellingException("Delta order: node not found [" + low.toString() + "]");
        incidence.put(high, low, true);
    }

    public void sort() throws DeltaModellingException {
        boolean rootNode;
        Set<T> nodes = new HashSet<T>(this.nodes);

        int currentSet = 0;
        while (nodes.size() > 0) {
            partition.add(new HashSet<T>());

            for (T node : nodes) {
                rootNode = true;
                for (T cn : nodes) {
                    if (incidence.get(cn, node) == true) {
                        rootNode = false;
                        break; // not a root node
                    }
                }
                if (rootNode)
                    partition.get(currentSet).add(node);
            }
            // no nodes in set means there is a cycle among the remaining nodes
            if (partition.get(currentSet).isEmpty())
                throw new DeltaModellingException("Delta order: cycle detected among the following nodes: " + nodes.toString());

            // for all nodes in set: remove these nodes from node set
            for (T node : partition.get(currentSet))
                nodes.remove(node);

            currentSet++;
        }
    }

    /*
     * Returns a single, valid delta order
     *
     * TODO: eventually this should compute an implication-determined order (cf. Damiani and Schaefer 2012),
     * which yields a PFGT with a minimal number of nodes
     */
    public List<T> getPreferredOrder() {
        if (preferredOrder != null) // only compute once
            return preferredOrder;

        preferredOrder = new ArrayList<T>(nodes.size());
        for (Set<T> set : partition)
            preferredOrder.addAll(set);

        return preferredOrder;
    }

    /*
     * Returns a single, valid delta order
     */
    public List<T> getAnOrder() {
        return getPreferredOrder();
    }

}
