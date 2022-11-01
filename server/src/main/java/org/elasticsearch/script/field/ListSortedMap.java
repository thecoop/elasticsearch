/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.script.field;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

class ListSortedMap<K, V> implements NavigableMap<K, V> {

    private ArrayList<K> keys;
    private ArrayList<V> values;
    private final Comparator<? super K> comparator;

    ListSortedMap() {
        this((Comparator<? super K>)null);
    }

    ListSortedMap(Comparator<? super K> comparator) {
        this.keys = new ArrayList<>();
        this.values = new ArrayList<>();
        this.comparator = comparator;
    }

    ListSortedMap(int size) {
        this(null, size);
    }

    ListSortedMap(Comparator<? super K> comparator, int size) {
        this.keys = new ArrayList<>(size);
        this.values = new ArrayList<>(size);
        this.comparator = comparator;
    }

    ListSortedMap(Map<K, V> map) {
        keys = new ArrayList<>(map.keySet());
        if (map instanceof SortedMap<K, V> sorted) {
            // values sorted in the same order as keys
            values = new ArrayList<>(sorted.values());
            comparator = sorted.comparator();
        }
        else {
            keys.sort(null);
            values = new ArrayList<>(keys.size());
            keys.stream().map(map::get).forEach(values::add);
            comparator = null;
        }
    }

    private void ensureCapacity(int size) {
        keys.ensureCapacity(size);
        values.ensureCapacity(size);
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
            return values.get(index);
        }

        @Override
        public V setValue(V value) {
            return values.set(index, value);
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
        values.clear();
    }

    @Override
    public boolean containsKey(Object key) {
        return findIndex(key) >= 0;
    }

    @Override
    public boolean containsValue(Object value) {
        return values.contains(value);
    }

    @Override
    public V get(Object key) {
        return getOrDefault(key, null);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        int index = findIndex(key);
        return index >= 0 ? values.get(index) : defaultValue;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (int i=0; i<keys.size(); i++) {
            action.accept(keys.get(i), values.get(i));
        }
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        for (int i=0; i<keys.size(); i++) {
            values.set(i, function.apply(keys.get(i), values.get(i)));
        }
    }

    @Override
    public V put(K key, V value) {
        int index = findIndex(key);
        if (index >= 0) {
            return values.set(index, value);
        }
        else {
            int insertion = -(index + 1);
            keys.add(insertion, key);
            values.add(insertion, value);
            return null;
        }
    }

    @Override
    public V putIfAbsent(K key, V value) {
        int index = findIndex(key);
        if (index >= 0) {
            V existing = values.get(index);
            if (existing == null) {
                return values.set(index, value);
            }
            else {
                return values.get(index);
            }
        }
        else {
            int insertion = -(index + 1);
            keys.add(insertion, key);
            values.add(insertion, value);
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        ensureCapacity(m.size());
        // if instanceof SortedMap can do a sorted mergesort
        m.forEach(this::put);
    }

    @Override
    public V replace(K key, V value) {
        int index = findIndex(key);
        if (index >= 0) {
            return values.set(index, value);
        }
        else {
            return null;
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        int index = findIndex(key);
        if (index >= 0 && Objects.equals(oldValue, values.get(index))) {
            values.set(index, newValue);
            return true;
        }
        else {
            return false;
        }
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        int index = findIndex(key);
        if (index >= 0) {
            return values.get(index);
        }
        else {
            V value = mappingFunction.apply(key);
            int insertion = -(index + 1);
            keys.add(insertion, key);
            values.add(insertion, value);
            return value;
        }
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        int index = findIndex(key);
        if (index >= 0) {
            V existing = values.get(index);
            if (existing == null) {
                return null;
            }
            V newV = remappingFunction.apply(key, existing);
            if (newV != null) {
                values.set(index, newV);
                return newV;
            }
            else {
                keys.remove(index);
                values.remove(index);
                return null;
            }
        }
        else {
            return null;
        }
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        int index = findIndex(key);
        if (index >= 0) {
            V newV = remappingFunction.apply(key, values.get(index));
            if (newV != null) {
                values.set(index, newV);
                return newV;
            }
            else {
                keys.remove(index);
                values.remove(index);
                return null;
            }
        }
        else {
            V newV = remappingFunction.apply(key, null);
            if (newV != null) {
                int insertion = -(index + 1);
                keys.add(insertion, key);
                values.add(insertion, newV);
                return newV;
            }
            else {
                return null;
            }
        }
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        int index = findIndex(key);
        if (index >= 0) {
            V existing = values.get(index);
            if (existing != null) {
                V newV = remappingFunction.apply(existing, value);
                if (newV != null) {
                    values.set(index, newV);
                    return newV;
                }
                else {
                    keys.remove(index);
                    values.remove(index);
                    return null;
                }
            }
            else {
                values.set(index, value);
                return value;
            }
        }
        else {
            int insertion = -(index + 1);
            keys.add(insertion, key);
            values.add(insertion, value);
            return value;
        }
    }

    @Override
    public V remove(Object key) {
        int index = findIndex(key);
        if (index >= 0) {
            keys.remove(index);
            return values.remove(index);
        }
        else {
            return null;
        }
    }

    @Override
    public boolean remove(Object key, Object value) {
        int index = findIndex(key);
        if (index >= 0 && Objects.equals(value, values.get(index))) {
            keys.remove(index);
            values.remove(index);
            return true;
        }
        else {
            return false;
        }
    }

    private boolean removeIf(IntPredicate predicate) {
        ArrayList<K> newKeys = new ArrayList<>(keys.size());
        ArrayList<V> newValues = new ArrayList<>(keys.size());

        for (int i=0; i<keys.size(); i++) {
            if (predicate.test(i)) {
                newKeys.add(keys.get(i));
                newValues.add(values.get(i));
            }
        }

        if (newKeys.size() < keys.size()) {
            newKeys.trimToSize();
            newValues.trimToSize();
            keys = newKeys;
            values = newValues;
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
        return Map.entry(keys.remove(0), values.remove(0));
    }

    @Override
    public K floorKey(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Entry<K, V> floorEntry(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public K lowerKey(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Entry<K, V> lowerEntry(K key) {
        throw new UnsupportedOperationException();
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
        return Map.entry(keys.remove(keys.size()-1), values.remove(values.size()-1));
    }

    @Override
    public K ceilingKey(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Entry<K, V> ceilingEntry(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public K higherKey(K key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Entry<K, V> higherEntry(K key) {
        throw new UnsupportedOperationException();
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
            keys.remove(nextIndex);
            values.remove(nextIndex);
            stopIndex--;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean keyWithinBounds(Object o, Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
        Comparator c = comparator();
        if (c == null) c = Comparator.naturalOrder();
        if (lowerBound != null && (lowerInclusive ? c.compare(o, lowerBound[0]) < 0 : c.compare(o, lowerBound[0]) <= 0))
            return false;
        if (upperBound != null && (upperInclusive ? c.compare(o, upperBound[0]) > 0 : c.compare(o, upperBound[0]) >= 0))
            return false;
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
            keys.subList(lower, upper).clear();
            values.subList(lower, upper).clear();
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
            if (withinBounds(o) == false)
                return false;
            return findIndex(o, lowerIndex(), upperIndex()) >= 0;
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
            if (withinBounds(o) == false)
                return false;
            int index = findIndex(o);
            if (index >= 0) {
                keys.remove(index);
                values.remove(index);
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

        private SortedSet<K> headSet(K toElement, boolean inclusive) {
            Object[] higherBound = new Object[] { this.upperBound != null
                ? Collections.min(Arrays.asList((K)this.upperBound[0], toElement), comparator())
                : toElement };
            return new KeySet(lowerBound, lowerInclusive, higherBound, inclusive);
        }

        @Override
        public SortedSet<K> tailSet(K fromElement) {
            return tailSet(fromElement, true);
        }

        private SortedSet<K> tailSet(K fromElement, boolean inclusive) {
            Object[] lowerBound = new Object[] { this.lowerBound != null
                ? Collections.max(Arrays.asList((K)this.lowerBound[0], fromElement), comparator())
                : fromElement };
            return new KeySet(lowerBound, inclusive, upperBound, upperInclusive);
        }

        @Override
        public SortedSet<K> subSet(K fromElement, K toElement) {
            return subSet(fromElement, true, toElement, false);
        }

        private SortedSet<K> subSet(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
            Object[] lowerBound = new Object[]{this.lowerBound != null
                ? Collections.max(Arrays.asList((K) this.lowerBound[0], fromElement), comparator())
                : fromElement};
            Object[] upperBound = new Object[]{this.upperBound != null
                ? Collections.min(Arrays.asList((K) this.upperBound[0], toElement), comparator())
                : toElement};

            return new KeySet(lowerBound, fromInclusive, upperBound, toInclusive);
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
            return values.get(nextIndex());
        }
    }

    private class Values extends MapCollection<V> {
        private Values(Object[] lowerBound, boolean lowerInclusive, Object[] upperBound, boolean upperInclusive) {
            super(lowerBound, lowerInclusive, upperBound, upperInclusive);
        }

        private List<V> values() {
            return values.subList(lowerIndex(), upperIndex());
        }

        @Override
        public boolean contains(Object o) {
            return values().contains(o);
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return values().containsAll(c);
        }

        @Override
        public boolean remove(Object o) {
            int lowerIndex = lowerIndex();
            int subIndex = values.subList(lowerIndex, upperIndex()).indexOf(o);
            if (subIndex >= 0) {
                keys.remove(subIndex + lowerIndex);
                values.remove(subIndex + lowerIndex);
                return true;
            }
            else {
                return false;
            }
        }

        @Override
        public boolean removeIf(Predicate<? super V> filter) {
            int lowerIndex = lowerIndex();
            int upperIndex = upperIndex();
            return ListSortedMap.this.removeIf(i -> i >= lowerIndex && i < upperIndex && filter.test(values.get(i)));
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator(lowerIndex(), upperIndex());
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return values().toArray(a);
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

        private int findIndex(Map.Entry<?, ?> entry) {
            int index = ListSortedMap.this.findIndex(entry.getKey());
            return index >= lowerIndex() && index < lowerIndex() && Objects.equals(entry.getValue(), values.get(index))
                ? index
                : -1;
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof Entry<?, ?> e) {
                return findIndex(e) >= 0;
            }
            return false;
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Entry<?, ?> e) {
                int index = findIndex(e);
                if (index >= 0) {
                    keys.remove(index);
                    values.remove(index);
                    return true;
                }
            }
            return false;
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
            keys.subList(lower, upper).clear();
            values.subList(lower, upper).clear();
        }

        @Override
        public boolean containsKey(Object key) {
            if (withinBounds(key) == false)
                return false;
            return findIndex(key) >= 0;
        }

        @Override
        public boolean containsValue(Object value) {
            return values.subList(lowerIndex(), upperIndex()).contains(value);
        }

        @Override
        public V get(Object key) {
            return getOrDefault(key, null);
        }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            if (withinBounds(key) == false)
                return defaultValue;
            int index = findIndex(key);
            return index >= 0 ? values.get(index) : defaultValue;
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            int end = upperIndex();
            for (int i=lowerIndex(); i<end; i++) {
                action.accept(keys.get(i), values.get(i));
            }
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            int end = upperIndex();
            for (int i=lowerIndex(); i<end; i++) {
                values.set(i, function.apply(keys.get(i), values.get(i)));
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
            return Map.entry(keys.remove(lower), values.remove(lower));
        }

        @Override
        public K floorKey(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry<K, V> floorEntry(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public K lowerKey(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry<K, V> lowerEntry(K key) {
            throw new UnsupportedOperationException();
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
            return Map.entry(keys.remove(upper-1), values.remove(upper-1));
        }

        @Override
        public K ceilingKey(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry<K, V> ceilingEntry(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public K higherKey(K key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry<K, V> higherEntry(K key) {
            throw new UnsupportedOperationException();
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

        public NavigableMap<K, V> headMap(K toElement, boolean inclusive) {
            Object[] higherBound = new Object[] { this.upperBound != null
                ? Collections.min(Arrays.asList((K)this.upperBound[0], toElement), comparator())
                : toElement };
            return new SubMap(lowerBound, lowerInclusive, higherBound, inclusive);
        }

        @Override
        public NavigableMap<K, V> tailMap(K fromElement) {
            return tailMap(fromElement, true);
        }

        public NavigableMap<K, V> tailMap(K fromElement, boolean inclusive) {
            Object[] lowerBound = new Object[] { this.lowerBound != null
                ? Collections.max(Arrays.asList((K)this.lowerBound[0], fromElement), comparator())
                : fromElement };
            return new SubMap(lowerBound, inclusive, upperBound, upperInclusive);
        }

        @Override
        public NavigableMap<K, V> subMap(K fromElement, K toElement) {
            return subMap(fromElement, true, toElement, false);
        }

        public NavigableMap<K, V> subMap(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
            Object[] lowerBound = new Object[]{this.lowerBound != null
                ? Collections.max(Arrays.asList((K) this.lowerBound[0], fromElement), comparator())
                : fromElement};
            Object[] upperBound = new Object[]{this.upperBound != null
                ? Collections.min(Arrays.asList((K) this.upperBound[0], toElement), comparator())
                : toElement};

            return new SubMap(lowerBound, fromInclusive, upperBound, toInclusive);
        }

        @Override
        public NavigableMap<K, V> descendingMap() {
            throw new UnsupportedOperationException();
        }
    }
}
