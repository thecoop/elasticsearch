/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class LittleMapStorage {

    private static class Node {
        private Object value;
        private NavigableMap<String, Node> nested;

        private NavigableMap<String, Node> nested() {
            return Objects.requireNonNullElse(nested, Collections.emptyNavigableMap());
        }

        private Node getContainer(String key) {
            if (nested == null) {
                nested = new TreeMap<>();
            }
            return nested.computeIfAbsent(key, k -> new Node());
        }

        private Object setValue(Object value) {
            assert value instanceof Node == false;
            Object existing = this.value;
            this.value = value;
            return existing;
        }

        // for debugging
        @Override
        public String toString() {
            return "<"
                + this.value
                + ">"
                + " ["
                + nested().keySet()
                + "]";
        }
    }

    private final Node root = new Node();

    public Optional<?> getCtx(String... field) {
        assert field.length > 0;
        Node curr = root;
        for (String f : field) {
            curr = curr.nested().get(f);
            if (curr == null) return Optional.empty();
        }
        return Optional.ofNullable(curr.value);
    }

    public List<?> getField(String... field) {
        List<Object> result = new ArrayList<>();
        return getField(result, root, Arrays.asList(field));
    }

    static List<?> getField(List<Object> result, Node root, List<String> field) {
        assert field.size() > 0;
        List<List<Node>> candidateNodes = field.stream().<List<Node>>map(s -> new ArrayList<>()).collect(Collectors.toList());
        candidateNodes.add(0, List.of(root));   // starting node

        for (int i = 0; i < field.size(); i++) {
            String f = field.get(i);
            if (f.contains(".")) throw new IllegalArgumentException();

            String min = f;
            String max = f + Character.MAX_VALUE;
            for (Node node : candidateNodes.get(i)) {
                // Unmanaged map
                if (node.value instanceof Map<?, ?> map) {
                    //searchDirect(result, i, field, (Map<String, ?>) map);
                }
                if (node.nested != null) {
                    searchNodes(
                        candidateNodes.subList(i+1, candidateNodes.size()),
                        field.subList(i, field.size()),
                        node.nested.subMap(min, true, max, true));
                }
            }
        }
        for (Node node : candidateNodes.get(candidateNodes.size()-1)) {
            if (node.value != null) {
                result.add(node.value);
            }
        }
        return result;
    }

    /**
     * Search for keys matching the substring path[start:] in root, and add them to result in the right place
     */
    private static void searchNodes(List<List<Node>> result, List<String> path, Map<String, Node> root) {
        for (Map.Entry<String, Node> entry : root.entrySet()) {
            int m = match(path, entry.getKey());
            result.get(m).add(entry.getValue());
        }
    }

    /**
     * Match segments from source against candidate.
     * Return:
     *   -1 if source does not match candidate
     *   0 if source exactly matches candidate
     *   > 0 if candidate fully matches some number of segments.  Returned value is the index of last matched segment
     */
    public static int match(List<String> path, String candidate) {
        int candidateStart = 0;
        for (int i = 0; i < path.size(); i++) {
            String pathSegment = path.get(i);
            int maxMatchLength = candidate.length() - candidateStart;
            if (pathSegment.length() > maxMatchLength) {
                // candidate too short, assumes no dots in path
                return -1;
            }
            // pathSegment is <= candidate length
            for (int j = 0; j < pathSegment.length(); j++) {
                if (pathSegment.charAt(j) != candidate.charAt(candidateStart + j)) {
                    // different character
                    return -1;
                }
            }
            candidateStart += pathSegment.length();
            if (candidateStart == candidate.length()) {
                return i;
            }
            // candidate has more values and we are at a segment boundary, so next char must be a dot.
            if (candidate.charAt(candidateStart++) != '.') {
                return -1;
            }
        }
        // matched all path segments but candidate had extra values (starting with dot due to last conditional)
        return -1;
    }

    public Object put(Object value, String... field) {
        Node curr = root;
        for (String f : field) {
            curr = curr.getContainer(f);
        }
        return curr.setValue(value);
    }
}
