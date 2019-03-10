package com.conveyal.r5.profile.entur.rangeraptor.transit;


import com.conveyal.r5.profile.entur.api.transit.IntIterator;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;

import java.util.function.Function;

import static com.conveyal.r5.profile.entur.util.TimeUtils.hm2time;

/**
 * The transit calculator is used to calculate transit related stuff, like calculating
 * <em>earliest boarding time</em> and time-shifting the access legs.
 * <p/>
 * The calculator is shared between the state, worker and path mapping code. This
 * make the calculations consistent and let us hide the request parameters. Hiding the
 * request parameters ensure that this calculator is used.
 */
public interface TransitCalculator {

    /**
     * Add to time a value(delta) and return the result. In the case of a normal
     * forward search this will be a pluss '+' operation, while in a backwards
     * search (moveing back in time) this will be a minus '-' operation.
     */
    int add(int time, int delta);

    /**
     * Subtract from time a value(delta) and return the result. In the case of a normal
     * forward search this will be a minus '-' operation, while in a backwards
     * search (moveing back in time) this will be a pluss '+' operation.
     */
    int sub(int time, int delta);

    /**
     * Calculate the earlies possible board time, adding board slack in the case
     * of a forward search, adding nothing in the case of a reverse search.
     *
     * @param time - the arrival time (forward search) or board time (reverse search)
     * @return the earliest possible board time to use in the next trip search.
     */
    int earliestBoardTime(int time);

    /**
     * Add boardSlack to time, this is in effect the same as {@link #exceedsTimeLimit(int)}.
     *
     * @param time - any time
     * @return the time plus boardSlack
     */
    int addBoardSlack(int time);

    /**
     * For a normal search return the trip arrival time at stop position.
     * For a reverse search return the next trips departure time at stop position with the boardSlack added.
     *
     * @param onTrip the current boarded trip
     * @param stopPositionInPattern the stop position/index
     * @param <T> The TripSchedule type defined by the user of the range raptor API.
     */
    <T extends TripScheduleInfo> int latestArrivalTime(T onTrip, int stopPositionInPattern);


    /**
     * Stop the search when the time exceeds the latest-acceptable-arrival-time.
     * In a reverse search this is the earliest acceptable departure time.
     *
     * @return true if time exceeds limit, false means good to go.
     */
    boolean exceedsTimeLimit(int time);

    /**
     * Return a reason why a arrival time do not pass the {@link #exceedsTimeLimit(int)}
     */
    String exceedsTimeLimitReason();

    /**
     * Return true is the first argument (subject) is the best time, and false if not. If both
     * are equal false is retuned.
     * <p/>
     * In a normal forward search "best" is considered BEFORE in time, while AFTER in time
     * is considered best in a reverse seach.
     *
     * @return true is subject is better then the candidate; if not false.
     */
    boolean isBest(int subject, int candidate);


    /**
     * Calculate the origin departure time using the given transitBoardingTime and access leg duration.
     */
    int originDepartureTime(final int firstTransitBoardTime, final int accessLegDuration);

    /**
     * Uninitialized time values is set to this value to mark them as not set, and to mark the
     * arrival as unreached. A big value(or very small value) is used to simplify the comparisons
     * to see if a new arrival time is better (less).
     * <p/>
     * For a normal forward search this should be Integer.MAX_VALUE and for a reverse
     * search this should be Integer.MIN_VALUE.
     */
    int unreachedTime();

    /**
     * Return an iterator, iterating over the minutes in the RangeRaptor algorithm.
     */
    IntIterator rangeRaptorMinutes();

    /**
     * Return an iterator, iterating over the stop positions in a pattern.
     * Iterate from '0' to 'nStopsInPattern - 1' in a forward search and from
     * 'nStopsInPattern - 1' to '0' in a reverse search.
     *
     * @param nStopsInPattern the number of stops in the trip pattern
     */
    IntIterator patternStopIterator(int nStopsInPattern);

    /**
     * Return an iterator, iterating over the stop positions in a pattern.
     * Iterate from 'onTripStopPos + 1' to 'nStopsInPattern-1' in a forward search
     * and from 'onTripStopPos - 1' to 0 in a reverse search.
     *
     * @param onTripStopPos the iterator will start here(exclusive)
     * @param nStopsInPattern the number of stops in the trip pattern
     */
    IntIterator patternStopIterator(int onTripStopPos, int nStopsInPattern);

    /**
     * Create a trip search, to use to find the correct trip to board/alight for
     * a given pattern. This is used to to inject a forward or backward
     * search into the worker (strategy design pattern).
     *
     * @param pattern the pattern to search
     * @param <T> The TripSchedule type defined by the user of the range raptor API.
     * @return The trip search strategy implementation.
     */
    <T extends TripScheduleInfo> TripScheduleSearch<T> createTripSearch(
            TripPatternInfo<T> pattern,
            Function<T, Boolean> skipTripScheduleCallback
    );


    /**
     * Create a new path mapper depending on the search direction.
     *
     * @param <T> The TripSchedule type defined by the user of the range raptor API.
     */
    <T extends TripScheduleInfo> PathMapper<T> createPathMapper();

    /**
     * Return a calculator for test purpose. The following parameters are fixed:
     * <ul>
     *     <li>'binaryTripSearchThreshold' = 10
     *     <li>'earliestDepartureTime' = 08:00:00
     *     <li>'latestArrivalTime',  = 10:00:00
     *     <li>'iterationStep' = 60 seconds
     * </ul>
     */
    static TransitCalculator testDummyCalculator(int boardSlackInSeconds) {
        return new ForwardSearchTransitCalculator(
                10,
                boardSlackInSeconds,
                hm2time(8,0),
                2 * 60 * 60, // 2 hours
                -1,
                60
        );
    }
}
