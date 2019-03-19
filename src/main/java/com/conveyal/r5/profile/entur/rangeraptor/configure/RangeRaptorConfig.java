package com.conveyal.r5.profile.entur.rangeraptor.configure;

import com.conveyal.r5.profile.entur.api.request.RangeRaptorProfile;
import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.request.RequestBuilder;
import com.conveyal.r5.profile.entur.api.request.TuningParameters;
import com.conveyal.r5.profile.entur.api.transit.TransitDataProvider;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.view.Heuristics;
import com.conveyal.r5.profile.entur.api.view.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.configure.McRangeRaptorConfig;
import com.conveyal.r5.profile.entur.rangeraptor.standard.configure.StdRangeRaptorConfig;
import com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics.HeuristicSearch;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.service.WorkerPerformanceTimersCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * This class is responsible for creating a new search and holding
 * application scoped Range Raptor state.
 * <p/>
 * This class should have APPLICATION scope. It manage a threadPool,
 * and hold a reference to the application tuning parameters.
 */
public class RangeRaptorConfig<T extends TripScheduleInfo> {
    private final ExecutorService threadPool;
    private final TuningParameters tuningParameters;
    private final WorkerPerformanceTimersCache timers;


    public RangeRaptorConfig(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
        this.threadPool = createNewThreadPool(tuningParameters.searchThreadPoolSize());
        this.timers = new WorkerPerformanceTimersCache(isMultiThreaded());
    }

    public SearchContext<T> context(TransitDataProvider<T> transit, RangeRaptorRequest<T> request) {
        return new SearchContext<>(request, tuningParameters, transit, timers.get(request));
    }

    public Worker<T> createStdWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        return StdRangeRaptorConfig.createSearch(context(transitData, request));
    }

    public Worker<T> createMcWorker(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request, Heuristics heuristics) {
        final SearchContext<T> context = context(transitData, request);
        return new McRangeRaptorConfig<T>(context).createWorker(heuristics);
    }

    public HeuristicSearch<T> createHeuristicSearch(TransitDataProvider<T> transitData, RangeRaptorRequest<T> request) {
        SearchContext<T> context = context(transitData, request);
        return StdRangeRaptorConfig.createHeuristicSearch(context);
    }

    public HeuristicSearch<T> createHeuristicSearch(
            TransitDataProvider<T> transitData,
            RangeRaptorProfile profile,
            RangeRaptorRequest<T> request,
            boolean forward
    ) {
        RangeRaptorRequest<T> req = heuristicReq(request, profile, forward);
        return  createHeuristicSearch(transitData, req);
    }

    public boolean isMultiThreaded() {
        return threadPool != null;
    }

    public ExecutorService threadPool() {
        return threadPool;
    }

    public void shutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
        }
    }

    /* private factory methods */

    private RangeRaptorRequest<T> heuristicReq(RangeRaptorRequest<T> request, RangeRaptorProfile profile, boolean forward) {
        RequestBuilder<T> copy = request.mutate();
        copy.profile(profile).searchDirection(forward);
        copy.searchParams().searchOneIterationOnly();
        return copy.build();
    }

    private ExecutorService createNewThreadPool(int size) {
        return size > 0 ? Executors.newFixedThreadPool(size) : null;
    }

}