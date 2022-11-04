/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import org.elasticsearch.common.util.Maps;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class ListSortedMap<K, V> implements NavigableMap<K, V> {

    private ArrayList<K> keys;  // defines the iteration order
    private final Map<K, V> entries;  // stores the values, provides constant-time lookup
    private final Comparator<? super K> comparator;

    ListSortedMap() {
        this((Comparator<? super K>)null);
    }

    ListSortedMap(Comparator<? super K> comparator) {
        this.keys = new ArrayList<>();
        this.entries = new HashMap<>();
        this.comparator = comparator;
    }

    ListSortedMap(int size) {
        this(null, size);
    }

    ListSortedMap(Comparator<? super K> comparator, int size) {
        this.keys = new ArrayList<>(size);
        this.entries = Maps.newHashMapWithExpectedSize(size);
        this.comparator = comparator;
    }

    ListSortedMap(Map<K, V> map) {
        keys = new ArrayList<>(map.keySet());
        if (map instanceof SortedMap<K, V> sorted) {
            // already sorted
            comparator = sorted.comparator();
        }
        else {
            keys.sort(null);
            comparator = null;
        }
        entries = new HashMap<>(map);
    }

    private void ensureCapacity(int size) {
        keys.ensureCapacity(size);
    }

    @SuppressWarnings("unchecked")
    private int findIndex(Object key) {
        return Collections.binarySearch(keys, (K)key, comparator);
    }

    @SuppressWarnings("unchecked")
    private int findIndex(Object key, int lowerBound, int upperBound) {
        return Collections.binarySearch(keys.subList(lowerBound, upperBound), (K)key, comparator);
    }

    private class MapEntry implements Entry<K, V> {
        private final int index;

        private MapEntry(int index) {
            this.index = index;
        }

        @Override
        public K getKey() {
            return keys.get(index);
        }

        @Override
        public V getValue() {
            return entries.get(getKey());
        }

        @Override
        public V setValue(V value) {
            return entries.put(getKey(), value);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Entry<?, ?> e
                && Objects.equals(getKey(), e.getKey())
                && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hash(getValue());
        }
    }

    @Override
    public Comparator<? super K> comparator() {
        return comparator;
    }

    @Override
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    public void clear() {
        keys.clear();
        entries.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return entries.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return entries.get(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return entries.getOrDefault(key, defaultValue);
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (K key : keys) {
            action.accept(key, entries.get(key));
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        for (K key : keys) {
            // don't use compute() & friends, don't want to remove if value is set null
            entries.put(key, function.apply(key, entries.get(key)));
        }
    }

    @Override
    public V put(K key, V value) {
        // need to disambiguate between null value and not existing at all
        boolean exists = entries.containsKey(key);
        if (exists == false) {
            int insertIndex = -(findIndex(key) + 1);
            keys.add(insertIndex, key);
        }
        return entries.put(key, value);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        // need to disambiguate between null value and not existing at all
        boolean exists = entries.containsKey(key);
        if (exists == false) {
            int insertIndex = -(findIndex(key) + 1);
            keys.add(insertIndex, key);
        }
        return entries.putIfAbsent(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        ensureCapacity(m.size());
        // if instanceof SortedMap can do a sorted mergesort
        m.forEach(this::put);
    }

    @Override
    public V replace(K key, V value) {
        return entries.replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return entries.replace(key, oldValue, newValue);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        boolean[] computed = new boolean[1];
        V value = entries.computeIfAbsent(key, k -> {
            computed[0] = true;
            return mappingFunction.apply(k);
        });
        if (computed[0]) {
            int insertIndex = -(findIndex(key) + 1);
            keys.add(insertIndex, key);
        }
        return value;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        boolean[] computed = new boolean[1];
        V value = entries.computeIfPresent(key, (k, v) -> {
            computed[0] = true;
            return remappingFunction.apply(k, v);
        });
        if (value == null && computed[0]) {
            // remove from the keylist
            keys.remove(findIndex(key));
        }
        return value;
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        boolean existsBefore = entries.containsKey(key);
        V value = entries.compute(key, remappingFunction);
        boolean existsAfter = value != null || entries.containsKey(key);
        if (existsBefore != existsAfter) {
            int index = findIndex(key);
            if (existsBefore) {
                // removed
                keys.remove(index);
            }
            else {
                // added
                int insertion = -(index + 1);
                keys.add(insertion, key);
            }
        }
        return value;
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        boolean[] computed = new boolean[1];
        V newValue = entries.merge(key, value, (k, v) -> {
            computed[0] = true;
            return remappingFunction.apply(k, v);
        });
        if (computed[0] == false) {
            // added to the map
            int insertIndex = -(findIndex(key) + 1);
            keys.add(insertIndex, key);
        }
        else if (newValue == null) {
            // removed from map
            keys.remove(findIndex(key));
        }
        return newValue;
    }

    @Override
    public V remove(Object key) {
        boolean exists = entries.containsKey(key);
        V value = entries.remove(key);
        if (exists) {
            keys.remove(findIndex(key));
        }
        return value;
    }

    @Override
    public boolean remove(Object key, Object value) {
        boolean removed = entries.remove(key, value);
        if (removed) {
            keys.remove(findIndex(key));
        }
        return removed;
    }

    private boolean removeIf(IntPredicate predicate) {
        ArrayList<K> newKeys = new ArrayList<>(keys.size());

        for (int i=0; i<keys.size(); i++) {
            if (predicate.test(i)) {
                newKeys.add(keys.get(i));
            }
            else {
                entries.remove(keys.get(i));
            }
        }

        if (newKeys.size() < keys.size()) {
            newKeys.trimToSize();
            keys = newKeys;
            // entries already modified
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public K firstKey() {
        if (keys.isEmpty())
            throw new NoSuchElementException();
        return keys.get(0);
    }

    @Override
    public Entry<K, V> firstEntry() {
        if (keys.isEmpty())
            return null;
        return new MapEntry(0);
    }

    @Override
    public Entry<K, V> pollFirstEntry() {
        if (keys.isEmpty())
            return null;
        K key = keys.remove(0);
        return Map.entry(key, entries.remove(key));
    }

    @Override
    public K floorKey(K key) {
        Entry<K, V> entry = floorEntry(key);
        return entry != null ? entry.getKey() : null;
    }

    @Override
    public Entry<K, V> floorEntry(K key) {
        int index = findIndex(key);
        if (index < 0) {
            index = -(index + 1) - 1;
        }
        return index >= 0 ? new MapEntry(index) : null;
    }

    @Override
    public K lowerKey(K key) {
        Entry<K, V> entry = lowerEntry(key);
        return entry != null ? entry.getKey() : null;
    }

    @Override
    public Entry<K, V> lowerEntry(K key) {
        int index = findIndex(key);
        if (index < 0) {
            index = -(index + 1);
        }
        return index >= 1 ? new MapEntry(index-1) : null;
    }

    @Override
    public K lastKey() {
        if (keys.isEmpty())
            throw new NoSuchElementException();
        return keys.get(keys.size()-1);
    }

    @Override
    public Entry<K, V> lastEntry() {
        if (keys.isEmpty())
            return null;
        return new MapEntry(keys.size()-1);
    }

    @Override
    public Entry<K, V> pollLastEntry() {
        if (keys.isEmpty())
            return null;
        K key = keys.remove(keys.size()-1);
        return Map.entry(key, entries.remove(key));
    }

    @Override
    public K ceilingKey(K key) {
        Entry<K, V> entry = ceilingEntry(key);
        return entry != null ? entry.getKey() : null;
    }

    @Override
    public Entry<K, V> ceilingEntry(K key) {
        int index = findIndex(key);
        if (index < 0) {
            index = -(index + 1);
        }
        return index < keys.size() ? new MapEntry(index) : null;
    }

    @Override
    public K higherKey(K key) {
        Entry<K, V> entry = higherEntry(key);
        return entry != null ? entry.getKey() : null;
    }

    @Override
    public Entry<K, V> higherEntry(K key) {
        int index = findIndex(key);
        if (index < 0) {
            index = -(index + 1);
        }
        else {
            index++;
        }
        return index < keys.size() ? new MapEntry(index) : null;
    }

    private abstract class MapIterator<T> implements Iterator<T> {
        private int nextIndex;
        private int stopIndex;

        private MapIterator(int startIndex, int stopIndex) {
            nextIndex = startIndex;
            this.stopIndex = stopIndex;
        }

        @Override
        public boolean hasNext() {
            return nextIndex < stopIndex;
        }

        public int nextIndex() {
            if (nextIndex >= stopIndex)
                throw new NoSuchElementException();
            return nextIndex++;
        }

        @Override
        public void remove() {
            nextIndex--;
            K key = keys.remove(nextIndex);
            entries.remove(key);
            stopIndex--;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean keyWithinBounds(Object o, Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
        Comparator c = comparator();
        if (c == null) c = Comparator.naturalOrder();
        try {
            if (lowerBound != null && (lowerInclusive ? c.compare(o, lowerBound[0]) < 0 : c.compare(o, lowerBound[0]) <= 0))
                return false;
            if (upperBound != null && (upperInclusive ? c.compare(o, upperBound[0]) > 0 : c.compare(o, upperBound[0]) >= 0))
                return false;
        }
        catch (ClassCastException e) {
            // not the right type
            return false;
        }
        return true;
    }

    private int getLowerIndex(Object[] lowerBound, boolean inclusive) {
        int lowerIndex = 0;
        if (lowerBound != null) {
            lowerIndex = findIndex(lowerBound[0]);
            if (lowerIndex < 0) {
                lowerIndex = -(lowerIndex + 1);
            }
            else if (inclusive == false) {
                lowerIndex++;
            }
        }
        return lowerIndex;
    }

    private int getUpperIndex(Object[] upperBound, boolean inclusive) {
        int upperIndex = keys.size();
        if (upperBound != null) {
            upperIndex = findIndex(upperBound[0]);
            if (upperIndex < 0) {
                upperIndex = -(upperIndex + 1);
            }
            else if (inclusive) {
                upperIndex++;
            }
        }
        return upperIndex;
    }

    @SuppressWarnings("unchecked")
    private Object[] combineLowerBound(Object[] existingBound, K newBound) {
        return new Object[] {existingBound != null
            ? Collections.max(Arrays.asList((K) existingBound[0], newBound), comparator())
            : newBound};
    }

    @SuppressWarnings("unchecked")
    private Object[] combineUpperBound(Object[] existingBound, K newBound) {
        return new Object[] {existingBound != null
            ? Collections.min(Arrays.asList((K) existingBound[0], newBound), comparator())
            : newBound};
    }

    private abstract class MapCollection<E> extends AbstractCollection<E> {
        // arrays to disambiguate between a null bound & no bound at all
        final Object[] lowerBound;
        final boolean lowerInclusive;
        final Object[] upperBound;
        final boolean upperInclusive;

        MapCollection(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            this.lowerBound = lowerBound;
            this.lowerInclusive = lowerInclusive;
            this.upperBound = upperBound;
            this.upperInclusive = upperInclusive;
        }

        /**
         * Returns the inclusive lower index of the range of this collection
         */
        int lowerIndex() {
            return getLowerIndex(lowerBound, lowerInclusive);
        }

        /**
         * Returns the exclusive upper index of the range of this collection
         */
        int upperIndex() {
            return getUpperIndex(upperBound, upperInclusive);
        }

        @Override
        public boolean isEmpty() {
            return lowerIndex() == upperIndex();
        }

        @Override
        public int size() {
            return upperIndex() - lowerIndex();
        }

        @Override
        public void clear() {
            int lower = lowerIndex();
            int upper = upperIndex();
            List<K> remove = keys.subList(lower, upper);
            remove.forEach(entries::remove);
            remove.clear();
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return c.stream().allMatch(this::contains);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return removeIf(k -> c.contains(k) == false);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            return removeIf(c::contains);
        }

        @Override
        public Object[] toArray() {
            return toArray(Object[]::new);
        }
    }

    @Override
    public SortedSet<K> keySet() {
        return new KeySet(null, false, null, false);
    }

    @Override
    public NavigableSet<K> navigableKeySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public NavigableSet<K> descendingKeySet() {
        throw new UnsupportedOperationException();
    }

    private class KeyIterator extends MapIterator<K> {
        private KeyIterator(int lowerBound, int upperBound) {
            super(lowerBound, upperBound);
        }

        @Override
        public K next() {
            return keys.get(nextIndex());
        }
    }

    private class KeySet extends MapCollection<K> implements SortedSet<K> {

        private KeySet(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            super(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        private boolean withinBounds(Object o) {
            return keyWithinBounds(o, lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        @Override
        public Comparator<? super K> comparator() {
            return ListSortedMap.this.comparator();
        }

        @Override
        public boolean contains(Object o) {
            return withinBounds(o) && containsKey(o);
        }

        @Override
        public K first() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                throw new NoSuchElementException();
            return keys.get(lower);
        }

        @Override
        public K last() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                throw new NoSuchElementException();
            return keys.get(upper-1);
        }

        @Override
        public boolean remove(Object o) {
            boolean exists = withinBounds(o) && entries.containsKey(o);
            if (exists) {
                keys.remove(findIndex(o));
                entries.remove(o);
                return true;
            }
            else {
                return false;
            }
        }

        @Override
        public boolean removeIf(Predicate<? super K> filter) {
            int lowerIndex = lowerIndex();
            int upperIndex = upperIndex();
            return ListSortedMap.this.removeIf(i -> i >= lowerIndex && i < upperIndex && filter.test(keys.get(i)));
        }

        @Override
        public SortedSet<K> headSet(K toElement) {
            return headSet(toElement, false);
        }

        @SuppressWarnings("unchecked")
        private SortedSet<K> headSet(K toElement, boolean inclusive) {
            return new KeySet(lowerBound, lowerInclusive, combineUpperBound(upperBound, toElement), inclusive);
        }

        @Override
        public SortedSet<K> tailSet(K fromElement) {
            return tailSet(fromElement, true);
        }

        @SuppressWarnings("unchecked")
        private SortedSet<K> tailSet(K fromElement, boolean inclusive) {
            return new KeySet(combineLowerBound(lowerBound, fromElement), inclusive, upperBound, upperInclusive);
        }

        @Override
        public SortedSet<K> subSet(K fromElement, K toElement) {
            return subSet(fromElement, true, toElement, false);
        }

        private SortedSet<K> subSet(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
            return new KeySet(
                combineLowerBound(lowerBound, fromElement), fromInclusive,
                combineUpperBound(upperBound, toElement), toInclusive);
        }

        @Override
        public Iterator<K> iterator() {
            return new KeyIterator(lowerIndex(), upperIndex());
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return keys.subList(lowerIndex(), upperIndex()).toArray(a);
        }

        @Override
        public <T> T[] toArray(IntFunction<T[]> generator) {
            return keys.subList(lowerIndex(), upperIndex()).toArray(generator);
        }
    }

    @Override
    public Collection<V> values() {
        return new Values(null, false, null, false);
    }

    private class ValueIterator extends MapIterator<V> {
        private ValueIterator(int lowerBound, int upperBound) {
            super(lowerBound, upperBound);
        }

        @Override
        public V next() {
            return entries.get(keys.get(nextIndex()));
        }
    }

    private class Values extends MapCollection<V> {
        private Values(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            super(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        private record Indexed<K, V>(int index, K key, V value) {}

        private Stream<Indexed<K, V>> values() {
            return IntStream.range(lowerIndex(), upperIndex()).mapToObj(i -> {
                K key = keys.get(i);
                return new Indexed<>(i, key, entries.get(key));
            });
        }

        @Override
        public boolean contains(Object o) {
            return values().anyMatch(i -> Objects.equals(i.value(), o));
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return values().map(Indexed::value).collect(Collectors.toSet()).containsAll(c);
        }

        @Override
        public boolean remove(Object o) {
            var v = values().filter(i -> Objects.equals(i.value(), o)).findFirst();
            v.ifPresent(i -> {
                keys.remove(i.index());
                entries.remove(i.key());
            });
            return v.isPresent();
        }

        @Override
        public boolean removeIf(Predicate<? super V> filter) {
            int lowerIndex = lowerIndex();
            int upperIndex = upperIndex();
            return ListSortedMap.this.removeIf(i -> i >= lowerIndex && i < upperIndex && filter.test(entries.get(keys.get(i))));
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator(lowerIndex(), upperIndex());
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return values().toList().toArray(a);
        }

        @Override
        public <T> T[] toArray(IntFunction<T[]> generator) {
            return values().toArray(generator);
        }
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new EntrySet(null, false, null, false);
    }

    private class EntryIterator extends MapIterator<Entry<K, V>> {
        private EntryIterator(int lowerBound, int upperBound) {
            super(lowerBound, upperBound);
        }

        @Override
        public Entry<K, V> next() {
            return new MapEntry(nextIndex());
        }
    }

    private class EntrySet extends MapCollection<Entry<K, V>> implements Set<Entry<K, V>> {
        private EntrySet(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            super(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        @Override
        public boolean contains(Object o) {
            return entries.entrySet().contains(o) && findIndex(((Entry<?, ?>)o).getKey()) >= 0;
        }

        @Override
        public boolean remove(Object o) {
            int index;
            Entry<?, ?> e;
            if (entries.entrySet().contains(o) && (index = findIndex((e = (Entry<?, ?>)o).getKey())) >= 0) {
                keys.remove(index);
                entries.remove(e.getKey());
                return true;
            }
            else {
                return false;
            }
        }

        @Override
        public boolean removeIf(Predicate<? super Entry<K, V>> filter) {
            return ListSortedMap.this.removeIf(i -> filter.test(new MapEntry(i)));
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator(lowerIndex(), upperIndex());
        }
    }

    @Override
    public NavigableMap<K, V> headMap(K toKey) {
        return headMap(toKey, false);
    }

    public NavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        return new SubMap(null, false, new Object[] { toKey }, inclusive);
    }

    @Override
    public NavigableMap<K, V> tailMap(K fromKey) {
        return tailMap(fromKey, true);
    }

    public NavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        return new SubMap(new Object[] { fromKey }, inclusive, null, false);
    }

    @Override
    public NavigableMap<K, V> subMap(K fromKey, K toKey) {
        return subMap(fromKey, true, toKey, false);
    }

    public NavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        return new SubMap(new Object[] { fromKey }, fromInclusive, new Object[] { toKey }, toInclusive);
    }

    @Override
    public NavigableMap<K, V> descendingMap() {
        throw new UnsupportedOperationException();
    }

    private class SubMap extends AbstractMap<K, V> implements NavigableMap<K, V> {
        // arrays to disambiguate between a null bound & no bound at all
        private final Object[] lowerBound;
        private final boolean lowerInclusive;
        private final Object[] upperBound;
        private final boolean upperInclusive;

        private SubMap(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            this.lowerBound = lowerBound;
            this.lowerInclusive = lowerInclusive;
            this.upperBound = upperBound;
            this.upperInclusive = upperInclusive;
        }

        private boolean withinBounds(Object o) {
            return keyWithinBounds(o, lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        /**
         * Returns the inclusive lower index of the range of this collection
         */
        int lowerIndex() {
            return getLowerIndex(lowerBound, lowerInclusive);
        }

        /**
         * Returns the exclusive upper index of the range of this collection
         */
        int upperIndex() {
            return getUpperIndex(upperBound, upperInclusive);
        }

        @Override
        public Comparator<? super K> comparator() {
            return ListSortedMap.this.comparator();
        }

        @Override
        public boolean isEmpty() {
            return lowerIndex() == upperIndex();
        }

        @Override
        public int size() {
            return upperIndex() - lowerIndex();
        }

        @Override
        public void clear() {
            int lower = lowerIndex();
            int upper = upperIndex();
            List<K> remove = keys.subList(lower, upper);
            remove.forEach(entries::remove);
            remove.clear();
        }

        @Override
        public boolean containsKey(Object key) {
            return entries.containsKey(key) && withinBounds(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values().contains(value);
        }

        @Override
        public V get(Object key) {
            return getOrDefault(key, null);
        }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            if (withinBounds(key) == false)
                return defaultValue;
            return entries.getOrDefault(key, defaultValue);
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            int end = upperIndex();
            for (int i=lowerIndex(); i<end; i++) {
                K key = keys.get(i);
                action.accept(key, entries.get(key));
            }
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            int end = upperIndex();
            for (int i=lowerIndex(); i<end; i++) {
                K key = keys.get(i);
                entries.put(key, function.apply(key, entries.get(key)));
            }
        }

        @Override
        public V put(K key, V value) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            ensureCapacity(m.size());
            // if instanceof SortedMap can do a sorted mergesort
            m.forEach(this::put);
        }

        @Override
        public V replace(K key, V value) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.replace(key, oldValue, newValue);
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.computeIfAbsent(key, mappingFunction);
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.computeIfPresent(key, remappingFunction);
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.compute(key, remappingFunction);
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            if (withinBounds(key) == false)
                throw new IllegalArgumentException("Out of range");
            return ListSortedMap.this.merge(key, value, remappingFunction);
        }

        @Override
        public V remove(Object key) {
            if (withinBounds(key) == false)
                return null;
            return ListSortedMap.this.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            if (withinBounds(key) == false)
                return false;
            return ListSortedMap.this.remove(key, value);
        }

        @Override
        public K firstKey() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                throw new NoSuchElementException();
            return keys.get(lower);
        }

        @Override
        public Entry<K, V> firstEntry() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                return null;
            return new MapEntry(lower);
        }

        @Override
        public Entry<K, V> pollFirstEntry() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                return null;
            K key = keys.remove(lower);
            return Map.entry(key, entries.remove(key));
        }

        @Override
        public K floorKey(K key) {
            Entry<K, V> entry = floorEntry(key);
            return entry != null ? entry.getKey() : null;
        }

        @Override
        public Entry<K, V> floorEntry(K key) {
            int index = findIndex(key);
            if (index < 0) {
                index = -(index + 1) - 1;
            }
            return index >= lowerIndex() && index < upperIndex() ? new MapEntry(index) : null;
        }

        @Override
        public K lowerKey(K key) {
            Entry<K, V> entry = lowerEntry(key);
            return entry != null ? entry.getKey() : null;
        }

        @Override
        public Entry<K, V> lowerEntry(K key) {
            int index = findIndex(key);
            if (index < 0) {
                index = -(index + 1);
            }
            return index >= lowerIndex() + 1 && index < upperIndex() ? new MapEntry(index-1) : null;
        }

        @Override
        public K lastKey() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                throw new NoSuchElementException();
            return keys.get(upper-1);
        }

        @Override
        public Entry<K, V> lastEntry() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                throw new NoSuchElementException();
            return new MapEntry(upper-1);
        }

        @Override
        public Entry<K, V> pollLastEntry() {
            int lower = lowerIndex();
            int upper = upperIndex();
            if (lower == upper)
                return null;
            K key = keys.remove(upper-1);
            return Map.entry(key, entries.remove(key));
        }

        @Override
        public K ceilingKey(K key) {
            Entry<K, V> entry = ceilingEntry(key);
            return entry != null ? entry.getKey() : null;
        }

        @Override
        public Entry<K, V> ceilingEntry(K key) {
            int index = findIndex(key);
            if (index < 0) {
                index = -(index + 1);
            }
            return index >= lowerIndex() && index < upperIndex() ? new MapEntry(index) : null;
        }

        @Override
        public K higherKey(K key) {
            Entry<K, V> entry = higherEntry(key);
            return entry != null ? entry.getKey() : null;
        }

        @Override
        public Entry<K, V> higherEntry(K key) {
            int index = findIndex(key);
            if (index < 0) {
                index = -(index + 1);
            }
            else {
                index++;
            }
            return index >= lowerIndex() && index < upperIndex() ? new MapEntry(index) : null;
        }

        @Override
        public Set<K> keySet() {
            return new KeySet(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        @Override
        public NavigableSet<K> navigableKeySet() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NavigableSet<K> descendingKeySet() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<V> values() {
            return new Values(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return new EntrySet(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        @Override
        public NavigableMap<K, V> headMap(K toElement) {
            return headMap(toElement, false);
        }

        @SuppressWarnings("unchecked")
        public NavigableMap<K, V> headMap(K toElement, boolean inclusive) {
            return new SubMap(lowerBound, lowerInclusive, combineUpperBound(upperBound, toElement), inclusive);
        }

        @Override
        public NavigableMap<K, V> tailMap(K fromElement) {
            return tailMap(fromElement, true);
        }

        @SuppressWarnings("unchecked")
        public NavigableMap<K, V> tailMap(K fromElement, boolean inclusive) {
            return new SubMap(combineLowerBound(lowerBound, fromElement), inclusive, upperBound, upperInclusive);
        }

        @Override
        public NavigableMap<K, V> subMap(K fromElement, K toElement) {
            return subMap(fromElement, true, toElement, false);
        }

        public NavigableMap<K, V> subMap(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
            return new SubMap(
                combineLowerBound(lowerBound, fromElement), fromInclusive,
                combineUpperBound(upperBound, toElement), toInclusive);
        }

        @Override
        public NavigableMap<K, V> descendingMap() {
            throw new UnsupportedOperationException();
        }
    }
}
