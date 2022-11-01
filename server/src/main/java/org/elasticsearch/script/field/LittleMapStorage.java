/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LittleMapStorage {

    private static class Node {
        private Object value;
        private NavigableMap<String, Node> nested;

        private NavigableMap<String, Node> nested() {
            return Objects.requireNonNullElse(nested, Collections.emptyNavigableMap());
        }

        private Node getContainer(String key) {
            if (nested == null) {
                nested = new ListSortedMap<>();
            }
            return nested.computeIfAbsent(key, k -> new Node());
        }

        /**
         * Map the key to an existing {@link Node}.  This only occurs during rehoming via ctx access.
         */
        void setContainer(String key, Node value) {
            if (nested == null) {
                nested = new ListSortedMap<>();
            }
            nested.put(key, value);
        }

        Object ctxGet() {
            // TODO: disambiguate better
            if (value != null) {
                return value;
            }
            else {
                return new NestedCtxMap(this);
            }
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

        public boolean isEmpty() {
            return this.value == null && nested().isEmpty();
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

    public Object getCtxMap(String key) {
        return root.getContainer(key).ctxGet();
    }

    public List<?> getField(String... field) {
        List<Object> result = new ArrayList<>();
        return getField(result, root, Arrays.asList(field));
    }

    @SuppressWarnings("unchecked")
    static List<?> getField(List<Object> result, Node root, List<String> field) {
        assert field.size() > 0;
        List<List<Object>> candidateNodes = field.stream().<List<Object>>map(s -> new ArrayList<>()).collect(Collectors.toList());
        candidateNodes.add(0, List.of(root));   // starting node

        for (int i = 0; i < field.size(); i++) {
            String f = field.get(i);
            if (f.contains(".")) throw new IllegalArgumentException();

            String min = f;
            String max = f + Character.MAX_VALUE;
            for (Object o : candidateNodes.get(i)) {
                if (o instanceof Map<?, ?> m) {
                    // external map
                    searchNodes(
                        candidateNodes.subList(i+1, candidateNodes.size()),
                        field.subList(i, field.size()),
                        (Map<String, ?>)m);
                }
                else if (o instanceof Node n) {
                    if (n.value instanceof Map<?, ?> nm) {
                        // external map
                        searchNodes(
                            candidateNodes.subList(i+1, candidateNodes.size()),
                            field.subList(i, field.size()),
                            (Map<String, ?>)nm);
                    }
                    else {
                        searchNodes(
                            candidateNodes.subList(i + 1, candidateNodes.size()),
                            field.subList(i, field.size()),
                            n.nested().subMap(min, true, max, true));
                    }
                }
            }
        }

        candidateNodes.get(candidateNodes.size()-1).stream()
            .flatMap(o -> o instanceof Node n ? Optional.ofNullable(n.value).stream() : Stream.of(o))
            .forEach(result::add);
        return result;
    }

    /**
     * Search for keys matching the substring path[start:] in root, and add them to result in the right place
     */
    private static void searchNodes(List<List<Object>> result, List<String> path, Map<String, ?> node) {
        for (Map.Entry<String, ?> entry : node.entrySet()) {
            int m = match(path, entry.getKey());
            result.get(m).add(entry.getValue());
        }
    }

    public void removePath(String... path) {
        removePathViaNode(root, Arrays.asList(path));
    }

    @SuppressWarnings("unchecked")
    static void removePathViaNode(Node node, List<String> field) {
        assert field.size() > 0;

        for (int i = 0; i < field.size(); i++) {
            String f = field.get(i);

            String min = f;
            String max = f + Character.MAX_VALUE;

            if (node.value instanceof Map<?, ?> external) {
                removePathViaMap((Map<String, ?>)external, field);
                if (external.isEmpty()) {
                    node.value = null;
                }
            }
            removePathViaMap(node.nested().subMap(min, true, max, true), field.subList(i, field.size()));
        }
    }

    @SuppressWarnings("unchecked")
    static void removePathViaMap(Map<String, ?> map, List<String> field) {
        String segment = field.get(0);
        field = field.subList(1, field.size());
        for (Iterator<? extends Map.Entry<String, ?>> it = map.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, ?> entry = it.next();
            if (segment.equals(entry.getKey()) == false) {
                continue;
            }
            boolean remove = false;
            if (entry.getValue() instanceof Node node) {
                if (field.isEmpty()) {
                    node.value = null;
                } else {
                    removePathViaNode(node, field);
                }
                remove = node.isEmpty();
            } else if (entry.getValue() instanceof Map<?, ?> subMap) {
                if (field.isEmpty()) {
                    remove = true;
                } else {
                    removePathViaMap((Map<String, ?>) subMap, field);
                    remove = subMap.isEmpty();
                }
            }
            if (remove) {
                it.remove();
            }
        }
    }

    /**
     * Remove fields for the fields interface.  The field arguments must not have dots.
     */
    public void remove(String... field) {
        removeViaNode(root, Arrays.asList(field));
    }

    @SuppressWarnings("unchecked")
    static void removeViaNode(Node node, List<String> field) {
        assert field.size() > 0;

        for (int i = 0; i < field.size(); i++) {
            String f = field.get(i);
            if (f.contains(".")) throw new IllegalArgumentException();

            String min = f;
            String max = f + Character.MAX_VALUE;

            if (node.value instanceof Map<?, ?> external) {
                removeViaMap((Map<String, ?>)external, field);
                if (external.isEmpty()) {
                    node.value = null;
                }
            }
            removeViaMap(node.nested().subMap(min, true, max, true), field.subList(i, field.size()));
        }
    }

    @SuppressWarnings("unchecked")
    static void removeViaMap(Map<String, ?> map, List<String> field) {
        for (Iterator<? extends Map.Entry<String, ?>> it = map.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, ?> entry = it.next();
            List<String> unmatched = match2(field, entry.getKey());
            if (unmatched == null) {
                continue;
            }
            boolean remove = false;
            if (entry.getValue() instanceof Node node) {
                if (unmatched.isEmpty()) {
                    node.value = null;
                } else {
                    removeViaNode(node, unmatched);
                }
                remove = node.isEmpty();
            } else if (entry.getValue() instanceof Map<?, ?> subMap) {
                if (unmatched.isEmpty()) {
                    remove = true;
                } else {
                    removeViaMap((Map<String, ?>) subMap, unmatched);
                    remove = subMap.isEmpty();
                }
            }
            if (remove) {
                it.remove();
            }
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
            for (int j = 0; j < pathSegment.length(); j++) {
                if (pathSegment.charAt(j) != candidate.charAt(candidateStart + j)) {
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

    /**
     * Match segments from source against candidate.
     * Return:
     *   null if source does not match candidate
     *   0 if source exactly matches candidate
     *   > 0 if candidate fully matches some number of segments.  Returned value is the index of last matched segment
     */
    public static List<String> match2(List<String> path, String candidate) {
        int candidateStart = 0;
        for (int i = 0; i < path.size(); i++) {
            String pathSegment = path.get(i);
            int maxMatchLength = candidate.length() - candidateStart;
            if (pathSegment.length() > maxMatchLength) {
                // candidate too short, assumes no dots in path
                return null;
            }
            for (int j = 0; j < pathSegment.length(); j++) {
                if (pathSegment.charAt(j) != candidate.charAt(candidateStart + j)) {
                    return null;
                }
            }
            candidateStart += pathSegment.length();
            if (candidateStart == candidate.length()) {
                return path.subList(i + 1, path.size());
            }
            // candidate has more values and we are at a segment boundary, so next char must be a dot.
            if (candidate.charAt(candidateStart++) != '.') {
                return null;
            }
        }
        // matched all path segments but candidate had extra values (starting with dot due to last conditional)
        return null;
    }

    public Object put(Object value, String... field) {
        return put(root, value, field);
    }

    static Object put(Node curr, Object value, String... field) {
        for (int i = 0; i < field.length-1; i++) {
            String f = field[i];
            curr = curr.getContainer(f);
        }
        if (value instanceof NestedCtxMap nm) {
            curr.setContainer(field[field.length-1], nm.node);
            return null;
        }
        else {
            return curr.getContainer(field[field.length-1]).setValue(value);
        }
    }

    private static class NestedCtxMap extends AbstractMap<String, Object> {
        private final Node node;

        private NestedCtxMap(Node node) {
            this.node = node;
        }

        @Override
        public int size() {
            return node.nested().size();
        }

        @Override
        public Object put(String key, Object value) {
            return LittleMapStorage.put(node, value, key);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                final Set<Entry<String, Node>> values = node.nested().entrySet();

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    return new Iterator<>() {

                        Iterator<Entry<String, Node>> it = values.iterator();

                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            Entry<String, Node> entry = it.next();
                            return new Entry<>() {
                                @Override
                                public String getKey() {
                                    return entry.getKey();
                                }

                                @Override
                                public Object getValue() {
                                    return entry.getValue().ctxGet();
                                }

                                @Override
                                public Object setValue(Object value) {
                                    // TODO(stu): untested
                                    return null;
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            it.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return NestedCtxMap.this.size();
                }
            };
        }
    }
}
