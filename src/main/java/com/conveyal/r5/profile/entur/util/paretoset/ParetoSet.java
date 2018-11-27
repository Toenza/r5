package com.conveyal.r5.profile.entur.util.paretoset;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * This {@link java.util.Collection} store all pareto-optimal elements. The
 * {@link #add(Object)} method returns {@code true} if and only if the element
 * was added successfully. When an element is added other elements witch are no
 * longer pareto-optimal are dropped.
 * <p/>
 * Like the {@link java.util.ArrayList} the elements are stored internally in
 * an array for performance reasons, and the order is guaranteed to be the same
 * as the order the elements are added. New elements are added at the end, while
 * dominated elements are removed. Elements in between are shifted towards the
 * beginning of the list:
 * <p/>
 * {@code  [[1,7], [3,5], [5,3]] + [2,4] => [[1,7], [5,3], [2,4]]   -- less than dominates}
 * <p/>
 * No methods for removing elements like {@link #remove(Object)} are supported.
 *
 * @param <T> the element type
 */
public class ParetoSet<T> extends AbstractCollection<T> {
    private final DropEventListener<T> dropEventListener;
    private final ParetoComparator<T> comparator;
    @SuppressWarnings("unchecked")
    private T[] elements = (T[])new Object[16];
    private int size = 0;


    /**
     * Create a new ParetoSet with a comparator and a drop event listener.
     *
     * @param comparator The comparator to use with this set
     * @param dropEventListener At most one subscriber can be registered to listen for drop events.
     */
    public ParetoSet(ParetoComparator<T> comparator, DropEventListener<T> dropEventListener) {
        this.comparator = comparator;
        this.dropEventListener = dropEventListener;
    }

    /**
     * Create a new ParetoSet with a comparator.
     */
    public ParetoSet(ParetoComparator<T> comparator) {
        this(comparator, (oe, ne) -> {/* NOOP */});
    }

    public T get(int index) {
        return elements[index];
    }

    @Override
    public int size() {
        return size;
    }


    @Override
    @SuppressWarnings("NullableProblems")
    public final Iterator<T> iterator() {
        return stream(0).iterator();
    }

    final Stream<T> stream(int startInclusive) {
        return Arrays.stream(elements, startInclusive, size);
    }

    @Override
    public boolean add(T  newValue) {
        if (size == 0) {
            appendValue(newValue);
            return true;
        }

        boolean mutualDominanceExist = false;
        boolean equivalentVectorExist = false;

        for (int i = 0; i < size; ++i) {
            T it = elements[i];

            boolean leftDominance = leftDominanceExist(newValue, it);
            boolean rightDominance = rightDominanceExist(newValue, it);

            if (leftDominance && rightDominance) {
                mutualDominanceExist = true;
            }
            else if (leftDominance) {
                removeDominatedElementsFromRestOfSetAndAddNewElement(newValue, i);
                return true;
            }
            else if (rightDominance) {
                return false;
            }
            else {
                equivalentVectorExist = true;
            }
        }

        if (mutualDominanceExist && !equivalentVectorExist) {
            assertEnoughSpaceInSet();
            appendValue(newValue);
            return true;
        }

        // No dominance found, newValue is equivalent with all values in the set
        return false;
    }

    /**
     * Test if an element qualify - the element is NOT added. Use the {@link #add(T)}
     * method directly if the purpose is to add the new element to the collection.
     * <p/>
     * Both methods are optimized for performance; hence the add method does not use this method.
     */
    public boolean qualify(T  newValue) {
        if (size == 0) {
            return true;
        }

        boolean mutualDominanceExist = false;
        boolean equivalentVectorExist = false;

        for (int i = 0; i < size; ++i) {
            boolean leftDominance = leftDominanceExist(newValue, elements[i]);
            boolean rightDominance = rightDominanceExist(newValue, elements[i]);


            if (leftDominance && rightDominance) {
                if(equivalentVectorExist) {
                    return false;
                }
                mutualDominanceExist = true;
            }
            else if (leftDominance) {
                return true;
            }
            else if (rightDominance) {
                return false;
            }
            else {
                if(mutualDominanceExist) {
                    return false;
                }
                equivalentVectorExist = true;
            }
        }
        return mutualDominanceExist;
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        size = 0;
    }

    /**
     * This is used for logging and tuning purposes - by looking at the statistics we can decide
     * a good value for the initial size.
     */
    public final int internalArrayLength() {
        return elements.length;
    }

    @Override
    public String toString() {
        return "{" + Arrays.stream(elements, 0, size)
                .map(Object::toString)
                .collect(Collectors.joining(", ")) + "}";
    }


    /**
     * Notify subclasses about reindexing. This method is empty,
     * and only exist for subclasses to override it.
     */
    protected void notifyReindex(int fromIndex, int toIndex) {
        // Noop
    }


    /* private methods */

    /**
     * Remove all elements dominated by the {@code newValue} starting from
     * {@code index + 1}. The element at {@code index} is dropped.
     */
    private void removeDominatedElementsFromRestOfSetAndAddNewElement(final T newValue, final int index) {
        // Let 'i' be the current element index for removal
        int i = index;
        // Let 'j' be the next element to compare
        int j = index + 1;

        notifyElementDropped(elements[i], newValue);

        while (j < size) {
            notifyReindex(j, i);
            // Move next element(j) forward if it is not dominated by the new value
            if (!leftVectorDominatesRightVector(newValue, elements[j])) {
                elements[i] = elements[j];
                ++i;
            }
            else {
                notifyElementDropped(elements[j], newValue);
            }
            // Goto the next element
            ++j;
        }
        notifyReindex(j, i);
        elements[i] = newValue;
        size = i+1;
    }

    private void notifyElementDropped(T oldElement, T newElement) {
        dropEventListener.elementDroppedFromParetoSet(oldElement, newElement);
    }

    private boolean leftVectorDominatesRightVector(T left, T right) {
        return leftDominanceExist(left, right) && !rightDominanceExist(left, right);
    }

    private void appendValue(T newValue) {
        elements[size++] = newValue;
    }

    private void assertEnoughSpaceInSet() {
        if (size == elements.length) {
            elements = Arrays.copyOf(elements, elements.length * 2);
        }
    }

    private boolean leftDominanceExist(T left, T right) {
        return comparator.leftDominanceExist(left, right);
    }

    private boolean rightDominanceExist(T left, T right) {
        return comparator.leftDominanceExist(right, left);
    }
}
