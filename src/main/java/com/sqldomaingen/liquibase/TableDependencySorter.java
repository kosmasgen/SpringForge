package com.sqldomaingen.liquibase;

import java.util.*;

/**
 * Sorts table names in deterministic topological order based on dependency graph.
 *
 * <p>Input graph format:
 * <ul>
 *   <li>key = table name</li>
 *   <li>value = parent tables that must be created before the key table</li>
 * </ul>
 *
 * <p>Behavior:
 * <ul>
 *   <li>Ignores self-dependencies (A -> A)</li>
 *   <li>Ignores unknown/external dependencies (dependency not present as graph key)</li>
 *   <li>Returns deterministic order using lexicographical tie-breaking</li>
 *   <li>Throws exception if a cycle exists among known tables</li>
 * </ul>
 */
public class TableDependencySorter {

    /**
     * Sorts tables in topological order using Kahn's algorithm with deterministic tie-breaking.
     *
     * @param dependencyGraph map of table -> required parent tables
     * @return ordered table names (parents before children)
     * @throws IllegalStateException if a cycle is detected among known tables
     */
    public List<String> sortTables(Map<String, Set<String>> dependencyGraph) {
        if (dependencyGraph == null || dependencyGraph.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Set<String>> normalizedGraph = normalizeGraph(dependencyGraph);
        Map<String, Integer> inDegree = buildInDegreeMap(normalizedGraph);
        Map<String, Set<String>> childrenByParent = buildChildrenIndex(normalizedGraph);

        Deque<String> ready = initializeReadyQueue(inDegree);
        List<String> ordered = new ArrayList<>(normalizedGraph.size());

        processReadyNodes(ready, inDegree, childrenByParent, ordered);

        validateNoCycle(normalizedGraph, ordered);

        return ordered;
    }

    /**
     * Creates a normalized dependency graph:
     * keeps only known tables, removes self-dependencies, and preserves all nodes.
     *
     * @param dependencyGraph raw input graph
     * @return normalized graph
     */
    private Map<String, Set<String>> normalizeGraph(Map<String, Set<String>> dependencyGraph) {
        Set<String> knownTables = dependencyGraph.keySet();
        Map<String, Set<String>> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            String table = entry.getKey();
            Set<String> rawDependencies = entry.getValue();

            Set<String> filteredDependencies = filterDependencies(table, rawDependencies, knownTables);
            normalized.put(table, filteredDependencies);
        }

        return normalized;
    }

    /**
     * Filters dependencies for one table:
     * removes nulls, self-dependencies, and unknown/external tables.
     *
     * @param table current table
     * @param rawDependencies raw dependency set
     * @param knownTables all known tables in the graph
     * @return filtered dependency set (deterministic order)
     */
    private Set<String> filterDependencies(
            String table,
            Set<String> rawDependencies,
            Set<String> knownTables) {

        if (rawDependencies == null || rawDependencies.isEmpty()) {
            return new TreeSet<>();
        }

        Set<String> filtered = new TreeSet<>();

        for (String dep : rawDependencies) {
            if (dep == null) {
                continue;
            }
            if (Objects.equals(dep, table)) {
                continue; // ignore self-reference
            }
            if (!knownTables.contains(dep)) {
                continue; // ignore external/unknown dependency
            }
            filtered.add(dep);
        }

        return filtered;
    }

    /**
     * Builds in-degree map (number of known parent dependencies per table).
     *
     * @param normalizedGraph normalized graph
     * @return in-degree map
     */
    private Map<String, Integer> buildInDegreeMap(Map<String, Set<String>> normalizedGraph) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : normalizedGraph.entrySet()) {
            inDegree.put(entry.getKey(), entry.getValue().size());
        }

        return inDegree;
    }

    /**
     * Builds reverse index parent -> children for efficient in-degree updates.
     *
     * @param normalizedGraph normalized graph
     * @return children index
     */
    private Map<String, Set<String>> buildChildrenIndex(Map<String, Set<String>> normalizedGraph) {
        Map<String, Set<String>> childrenByParent = new LinkedHashMap<>();

        initializeEmptyChildrenSets(childrenByParent, normalizedGraph.keySet());

        for (Map.Entry<String, Set<String>> entry : normalizedGraph.entrySet()) {
            String child = entry.getKey();
            Set<String> parents = entry.getValue();

            for (String parent : parents) {
                childrenByParent.get(parent).add(child);
            }
        }

        return childrenByParent;
    }

    /**
     * Initializes empty child sets for every known table.
     *
     * @param childrenByParent target map
     * @param tables known tables
     */
    private void initializeEmptyChildrenSets(
            Map<String, Set<String>> childrenByParent,
            Collection<String> tables) {

        for (String table : tables) {
            childrenByParent.put(table, new TreeSet<>());
        }
    }

    /**
     * Initializes ready queue with all zero in-degree nodes using deterministic order.
     *
     * @param inDegree in-degree map
     * @return queue of ready nodes
     */
    private Deque<String> initializeReadyQueue(Map<String, Integer> inDegree) {
        TreeSet<String> sortedReady = new TreeSet<>();

        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                sortedReady.add(entry.getKey());
            }
        }

        return new ArrayDeque<>(sortedReady);
    }

    /**
     * Processes ready nodes and builds the final topological order.
     *
     * @param ready queue of zero in-degree nodes
     * @param inDegree mutable in-degree map
     * @param childrenByParent reverse index parent -> children
     * @param ordered output ordered list
     */
    private void processReadyNodes(
            Deque<String> ready,
            Map<String, Integer> inDegree,
            Map<String, Set<String>> childrenByParent,
            List<String> ordered) {

        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            ordered.add(current);

            Set<String> children = childrenByParent.getOrDefault(current, Collections.emptySet());
            for (String child : children) {
                int newDegree = inDegree.get(child) - 1;
                inDegree.put(child, newDegree);

                if (newDegree == 0) {
                    insertSorted(ready, child);
                }
            }
        }
    }

    /**
     * Inserts a node into the queue while preserving lexicographical order.
     *
     * @param ready target queue
     * @param node node to insert
     */
    private void insertSorted(Deque<String> ready, String node) {
        if (ready.isEmpty()) {
            ready.add(node);
            return;
        }

        List<String> buffer = new ArrayList<>(ready.size() + 1);
        boolean inserted = false;

        while (!ready.isEmpty()) {
            String current = ready.removeFirst();

            if (!inserted && node.compareTo(current) < 0) {
                buffer.add(node);
                inserted = true;
            }

            buffer.add(current);
        }

        if (!inserted) {
            buffer.add(node);
        }

        for (String item : buffer) {
            ready.addLast(item);
        }
    }

    /**
     * Validates that all nodes were processed.
     * If not, a cycle exists among known tables.
     *
     * @param normalizedGraph normalized graph
     * @param ordered output order
     * @throws IllegalStateException if cycle detected
     */
    private void validateNoCycle(
            Map<String, Set<String>> normalizedGraph,
            List<String> ordered
    ) {
        if (ordered.size() == normalizedGraph.size()) {
            return;
        }

        Set<String> unresolved = new TreeSet<>(normalizedGraph.keySet());
        Set<String> resolved = new HashSet<>(ordered);

        unresolved.removeAll(resolved);

        throw new IllegalStateException(
                "Cyclic dependency detected among tables: " + unresolved
        );
    }
}
