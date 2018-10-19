package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.SearchAlgorithm;
import com.conveyal.r5.profile.mcrr.PathBuilder;
import com.conveyal.r5.profile.mcrr.PathBuilderCursorBased;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorker;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerState;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerStateImpl;
import com.conveyal.r5.profile.mcrr.RaptorWorkerTransitDataProvider;
import com.conveyal.r5.profile.mcrr.StopStateCollection;
import com.conveyal.r5.profile.mcrr.StopStatesIntArray;
import com.conveyal.r5.profile.mcrr.StopStatesStructArray;
import com.conveyal.r5.profile.mcrr.Worker;
import com.conveyal.r5.profile.mcrr.mc.McRangeRaptorWorker;
import com.conveyal.r5.profile.mcrr.mc.McWorkerState;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


enum ProfileFactory {
    original("original", "The original code with FastRaptorWorker"),
    int_arrays("int", "Flyweight stop state using int arrays with new RangeRaptorWorker"),
    struct_arrays("struct", "Simple POJO stop arrival state with new RangeRaptorWorker") {
        @Override
        StopStateCollection createStopStateCollection(int nRounds, int nStops) {
            return new StopStatesStructArray(nRounds, nStops);
        }
    },
    multi_criteria("mc", "Multi criteria pareto state with new McRangeRaptor") {
        @Override
        public Worker createWorker(ProfileRequest request, int nRounds, int nStops, RaptorWorkerTransitDataProvider transitData, EgressAccessRouter streetRouter) {
            McWorkerState state = new McWorkerState(
                    nRounds,
                    nStops,
                    request.maxTripDurationMinutes * 60
            );

            return new McRangeRaptorWorker(
                    transitData,
                    state,
                    request.fromTime,
                    request.toTime,
                    request.walkSpeed,
                    request.maxWalkTime,
                    streetRouter.accessTimesToStopsInSeconds,
                    streetRouter.egressTimesToStopsInSeconds.keys()
            );
        }
    }
    ;
    final String shortName;
    final String description;

    ProfileFactory(String shortName, String description) {
        this.shortName = shortName;
        this.description = description;
    }

    static ProfileFactory[] parse(String profiles) {
        return Arrays.stream(profiles.split(",")).map(ProfileFactory::parseOne).toArray(ProfileFactory[]::new);
    }

    static ProfileFactory from(SearchAlgorithm algorithm, ProfileFactory defaultFactory) {
        if(algorithm == null) return defaultFactory;

        switch (algorithm) {
            case RangeRaptor: return original;
            case MultiCriteriaRangeRaptor: return multi_criteria;
            case StructRangeRaptor: return struct_arrays;
            case IntArrayRangeRaptor: return int_arrays;
        }
        return defaultFactory;
    }

    public static List<String> options() {
        return Arrays.stream(values()).map(ProfileFactory::description).collect(Collectors.toList());
    }

    StopStateCollection createStopStateCollection(int nRounds, int nStops) {
        return new StopStatesIntArray(nRounds, nStops);
    }

    RangeRaptorWorkerState createWorkerState(int nRounds, int nStops, int earliestDepartureTime, int maxDurationSeconds, StopStateCollection stops) {
        return new RangeRaptorWorkerStateImpl(nRounds, nStops, earliestDepartureTime, maxDurationSeconds, stops);
    }

    PathBuilder createPathBuilder(StopStateCollection stops) {
        return new PathBuilderCursorBased(stops.newCursor());
    }

    Worker createWorker(ProfileRequest request, int nRounds, int nStops, RaptorWorkerTransitDataProvider transitData, EgressAccessRouter streetRouter) {
        StopStateCollection stops = createStopStateCollection(nRounds, nStops);

        RangeRaptorWorkerState state = createWorkerState(
                nRounds,
                nStops,
                request.fromTime,
                request.maxTripDurationMinutes * 60,
                stops
        );

        return new RangeRaptorWorker(
                transitData,
                state,
                createPathBuilder(stops),
                request.fromTime,
                request.toTime,
                request.walkSpeed,
                request.maxWalkTime,
                streetRouter.accessTimesToStopsInSeconds,
                streetRouter.egressTimesToStopsInSeconds.keys()
        );
    }

    boolean isOriginal() {
        return this == original;
    }


    /* private methods */

    private static ProfileFactory parseOne(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            for (ProfileFactory it : values()) {
                if(it.shortName.toLowerCase().startsWith(value)) {
                    return it;
                }
            }
            throw e;
        }
    }

    private String description() {
        if(name().equals(shortName)) {
            return String.format("'%s' : %s",   name(), description);
        }
        else {
            return String.format("'%s' or '%s' : %s",   name(), shortName, description);
        }
    }
}