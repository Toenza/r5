package com.conveyal.r5.profile.entur.rangeraptor.standard;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.request.DebugRequest;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.debug.DebugHandlerFactory;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;

import java.util.Collection;


/**
 * Tracks the state of a Range Raptor search, specifically the best arrival times at each transit stop at the end of a
 * particular round, along with associated data to reconstruct paths etc.
 * <p>
 * This is grouped into a separate class (rather than just having the fields in the raptor worker class) because we
 * want to separate the logic of maintaining stop arrival state and performing the steps of the algorithm. This
 * also make it possible to have more than one state implementation, which have ben used in the past to test different
 * memory optimizations.
 * <p>
 * Note that this represents the entire state of the Range Raptor search for all rounds, rather than the state at
 * a particular transit stop.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public final class StdRangeRaptorWorkerState<T extends TripScheduleInfo> extends BestTimesWorkerState<T> {

    private final Stops<T> stops;
    private final DestinationArrivals<T> results;
    private final DebugState<T> debug;

    /**
     * Create a Standard range raptor state for the given context
     */
    public StdRangeRaptorWorkerState(SearchContext<T> c) {
        this(
                c.nRounds(),
                c.nStops(),
                c.numberOfAdditionalTransfers(),
                c.egressStops(),
                c.calculator(),
                c.debugFactory(),
                c.egressLegs(),
                c.debugRequest()
        );
    }

    private StdRangeRaptorWorkerState(
            int nRounds,
            int nStops,
            int numberOfAdditionalTransfers,
            int[] destinationStops,
            TransitCalculator calculator,
            DebugHandlerFactory<T> dFactory,
            Collection<TransferLeg> egressLegs,
            DebugRequest<T> debugRequest
    ) {
        super(nRounds, nStops, numberOfAdditionalTransfers, destinationStops, calculator);
        this.stops = new Stops<>(nRounds, nStops, egressLegs, this::handleEgressStopArrival);
        this.results = new DestinationArrivals<>(nRounds, calculator, new StopsCursor<>(stops, calculator), dFactory);
        this.debug = debugRequest.isDebug()
                ? new StateDebugger<>(new StopsCursor<>(stops, calculator), dFactory.debugStopArrival())
                : DebugState.noop();
    }

    @Override
    final public void setupIteration2(int iterationDepartureTime) {
        debug.setIterationDepartureTime(iterationDepartureTime);
        results.setIterationDepartureTime(iterationDepartureTime);
    }

    @Override
    protected final void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        stops.setInitialTime(round(), stop, arrivalTime, durationInSeconds);
        debug.accept(round(), stop);
    }

    @Override
    public final Collection<Path<T>> extractPaths() {
        return results.paths();
    }

    @Override
    public final int bestTimePreviousRound(int stop) {
        return stops.get(round() - 1, stop).time();
    }


    @Override
    protected void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        debug.dropOldStateAndAcceptNewState(
                round(),
                stop,
                () -> stops.transitToStop(round(), stop, alightTime, boardStop, boardTime, trip, newBestOverall)
        );
    }

    @Override
    protected void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        if(debug.isDebug(stop)) {
            debug.rejectTransit(round(), stop, alightTime, trip, boardStop, boardTime);
        }
    }

    /**
     * Create paths for current iteration.
     */
    @Override
    public final void iterationComplete() {
        results.addPathsForCurrentIteration();
    }

    @Override
    protected void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        debug.dropOldStateAndAcceptNewState(
                round(),
                transferLeg.stop(),
                () -> stops.transferToStop(round(), fromStop, transferLeg, arrivalTime)
        );
    }

    @Override
    protected void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        if(debug.isDebug(transferLeg.stop())) {
            debug.rejectTransfer(round(), fromStop, transferLeg, transferLeg.stop(), arrivalTime);
        }
    }

    private void handleEgressStopArrival(EgressStopArrivalState<T> arrival) {
        results.add(arrival);
    }
}