package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AccessStopArrival;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.util.Debug;

import java.util.Collection;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;


/**
 * This class serve as a thin wrapper around the stops array and the destination arrivals.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
final class Stops<T extends TripScheduleInfo> {
    private final StopArrivals<T>[] stops;
    private final Destination<T> destination;
    private final PathMapper<T> pathMapper;
    private final TransitCalculator calculator;
    private final DebugHandlerFactory<T> debugHandlerFactory;

    /**
     * Set the time at a transit index iff it is optimal. This sets both the best time and the transfer time
     */
    Stops(
            int nStops,
            Collection<TransferLeg> egressLegs,
            TransitCalculator calculator,
            DebugHandlerFactory<T> debugHandlerFactory
    ) {
        //noinspection unchecked
        this.stops = (StopArrivals<T>[]) new StopArrivals[nStops];
        this.calculator = calculator;
        this.pathMapper = calculator.createPathMapper();
        this.debugHandlerFactory = debugHandlerFactory;
        this.destination = new Destination<>(debugHandlerFactory.debugDestinationArrival());

        for (TransferLeg it : egressLegs) {
            this.stops[it.stop()] = new EgressStopArrivals<T>(it, destination, debugHandlerFactory.debugStopArrival(it.stop()));
        }
    }

    void startNewIteration(int departureTime) {
        debugHandlerFactory.setIterationDepartureTime(departureTime);
    }

    void setInitialTime(TransferLeg accessLeg, int fromTime) {
        AccessStopArrival<T> newAccessArrival = new AccessStopArrival<>(
                accessLeg.stop(),
                fromTime,
                accessLeg.durationInSeconds(),
                accessLeg.cost(),
                calculator
        );
        addStopArrival(newAccessArrival);
    }

    boolean addStopArrival(AbstractStopArrival<T> arrival) {
        return findOrCreateSet(arrival.stop()).add(arrival);
    }

    Collection<Path<T>> extractPaths() {
        debugStateInfo();
        return destination.stream().map(pathMapper::mapToPath).collect(Collectors.toList());
    }

    /** List all transits arrived this round. */
    Iterable<AbstractStopArrival<T>> listArrivalsAfterMark(final int stop) {
        StopArrivals<T> it = stops[stop];
        if(it==null) {
            return emptyList();
        }
        return it.streamAfterMarker().collect(Collectors.toList());
    }

    void markAllStops() {
        for (StopArrivals<T> stop : stops) {
            if (stop != null) {
                stop.markAtEndOfSet();
            }
        }
    }

    private StopArrivals<T> findOrCreateSet(final int stop) {
        if(stops[stop] == null) {
            stops[stop] = new StopArrivals<T>(stop, debugHandlerFactory.debugStopArrival(stop));
        }
        return stops[stop];
    }

    private void debugStateInfo() {
        if(!Debug.isDebug()) return;

        long total = 0;
        long arrayLen = 0;
        long numOfStops = 0;
        int max = 0;

        for (StopArrivals stop : stops) {
            if(stop != null) {
                ++numOfStops;
                total += stop.size();
                max = Math.max(stop.size(), max);
                arrayLen += stop.internalArrayLength();
            }
        }
        double avg = ((double)total) / numOfStops;
        double arrayLenAvg = ((double)arrayLen) / numOfStops;

        System.err.printf(
                " => STOP ARRIVALS  %.1f / %d / %d'  Array Length: %.1f / %d'  Stops: %d' / %d'%n",
                avg, max, total/1000, arrayLenAvg, arrayLen/1000, numOfStops/1000, stops.length/1000
        );
    }
}
