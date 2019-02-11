package com.conveyal.r5.profile.entur.api.request;


import java.util.Objects;

/**
 * This class define how to calculate the cost when cost is part of the multi-criteria pareto function.
 */
public class MultiCriteriaCostFactors {
    public static final MultiCriteriaCostFactors DEFAULTS = new MultiCriteriaCostFactors();

    private final int boardCost;
    private final double walkReluctanceFactor;
    private final double waitReluctanceFactor;

    /**
     * Default constructor defines default values.
     */
    private MultiCriteriaCostFactors() {
        this.boardCost = 300;
        this.walkReluctanceFactor = 4.0;
        this.waitReluctanceFactor = 1.0;
    }

    MultiCriteriaCostFactors(RequestBuilder<?> builder) {
        this.boardCost = builder.multiCriteriaBoardCost();
        this.walkReluctanceFactor = builder.multiCriteriaWalkReluctanceFactor();
        this.waitReluctanceFactor = builder.multiCriteriaWaitReluctanceFactor();
    }

    public int boardCost() {
        return boardCost;
    }

    /**
     * A walk reluctance factor of 100 regarded as neutral. 400 means the rider
     * would rater sit 4 minutes extra on a buss, than walk 1 minute extra.
     */
    public double walkReluctanceFactor() {
        return walkReluctanceFactor;
    }

    public double waitReluctanceFactor() {
        return waitReluctanceFactor;
    }

    @Override
    public String toString() {
        return "MultiCriteriaCostFactors{" +
                "boardCost=" + boardCost +
                ", walkReluctanceFactor=" + walkReluctanceFactor +
                ", waitReluctanceFactor=" + waitReluctanceFactor +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MultiCriteriaCostFactors that = (MultiCriteriaCostFactors) o;
        return boardCost == that.boardCost &&
                Double.compare(that.walkReluctanceFactor, walkReluctanceFactor) == 0 &&
                Double.compare(that.waitReluctanceFactor, waitReluctanceFactor) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(boardCost, walkReluctanceFactor, waitReluctanceFactor);
    }
}