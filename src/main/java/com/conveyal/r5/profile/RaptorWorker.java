package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.BoardingAssumption;
import com.conveyal.r5.publish.StaticPropagatedTimesStore;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.protobuf.CodedOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.PropagatedTimesStore.ConfidenceCalculationMethod;
import com.conveyal.r5.streets.PointSetTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * This is an exact copy of RaptorWorker that's being modified to work with new TransportNetworks (from R5)
 * instead of (old) OTP Graphs. We can afford the maintainability nightmare of duplicating so much code because
 * we will soon delete the old OTP-based raptor worker class entirely.
 *
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing
 * computation by a generic function-execution service like AWS Lambda. The gains in efficiency were significant enough
 * that this is now the way we do all analysis work.
 *
 * This system also accounts for pure-frequency routes by using Monte Carlo methods (generating randomized schedules).
 *
 * This implements the RAPTOR algorithm; see http://research.microsoft.com/pubs/156567/raptor_alenex.pdf
 */
public class RaptorWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RaptorWorker.class);
    public static final int UNREACHED = Integer.MAX_VALUE;
    static final int MAX_DURATION = Integer.MAX_VALUE - 48 * 60 * 60;

    /**
     * The number of randomized frequency schedule draws to take for each minute of the search.
     *
     * We loop over all departure minutes and do a search on the scheduled portion of the network, and then while
     * holding the departure minute and scheduled search results stable, we run this many Monte Carlo searches with
     * randomized frequency schedules that minute. The number of Monte Carlo draws does not need to be particularly
     * high as it happens each minute, and there is likely a lot of repetition in the scheduled service
     * (i.e. many minutes look like each other), so several minutes' Monte Carlo draws are effectively pooled.
     */
    public int MONTE_CARLO_COUNT_PER_MINUTE = 1;

    /** If there are no schedules, the number of Monte Carlo draws to take. */
    public int TOTAL_MONTE_CARLO_COUNT = 99;

    /** Minimum slack time to board transit in seconds. */
    public static final int BOARD_SLACK_SECONDS = 60;

    /** How much to decrease the departure time between scheduled search iterations. */
    private static final int DEPARTURE_STEP_SEC = 60;

    int max_time = 0;
    int round = 0;
    private int scheduledRounds = -1;

    /**
     * One {@link RaptorState} per round of the scheduled search. We don't need to keep a separate RaptorState at each
     * departure minute because we use range-raptor on the scheduled search. We step backward through the departure
     * minutes and reuse the RaptorStates.
     */
    List<RaptorState> scheduleState;

    TransitLayer data;

    /** Patterns touched during this round. */
    BitSet patternsTouched;

    /** Stops touched during this round. */
    BitSet stopsTouched;

    /**
     * Stops touched during this search (with "search" meaning either a scheduled search at a particular minute,
     * or one particular draw within a departure minute when working with frequency routes).
     * Used in propagating travel times out to targets.
     * Cleared before each search; in order for propagation to be correct, doPropagation must be called after every
     * minute of the scheduled search, and after each search for a frequency draw. The propagation output table for
     * the scheduled search at minute M is saved and reused in the scheduled search for minute M-1.
     * The propagation output table for a scheduled search is also copied into each of the frequency searches within
     * that same minute, so we only need to do propagation from stops that have been touched in a particular search,
     * and update the tables.
     */
    BitSet allStopsTouched;

    private ProfileRequest req;

    /** Clock time spent on propagation, for display and debugging. */
    private long totalPropagationTime = 0;

    private FrequencyRandomOffsets offsets;

    public PropagatedTimesStore propagatedTimesStore;

    public LinkedPointSet targets;

    public BitSet servicesActive;

    /**
     * After each iteration (search at a single departure time with a specific Monte Carlo draw of schedules for
     * frequency routes) we store the {@link RaptorState}.
     */
    public List<RaptorState> statesEachIteration = new ArrayList<>();

    /**
     * After each iteration (search at a single departure time with a specific Monte Carlo draw of schedules for
     * frequency routes) we store travel time to every target. (or arrival time? TODO clarify)
     */
    public int[][] timesAtTargetsEachIteration;

    /**
     * Whether each iteration (departure time / frequency draw) should be included in averages.
     * The frequency searches that represent lower and upper bounds rather than randomly selected schedules
     * should not be included in the averages.
     */
    public BitSet includeInAverages = new BitSet();

    public RaptorWorker (TransitLayer data, LinkedPointSet targets, ProfileRequest req) {
        this.data = data;
        int nStops = data.streetVertexForStop.size();

        stopsTouched = new BitSet(nStops);
        patternsTouched = new BitSet(data.tripPatterns.size());
        allStopsTouched = new BitSet(nStops);
        this.scheduleState = new ArrayList<>();
        this.scheduleState.add(new RaptorState(nStops));

        this.targets = targets;

        this.servicesActive = data.getActiveServicesForDate(req.date);

        this.req = req.clone();
        offsets = new FrequencyRandomOffsets(data);
    }

    /**
     * Prepare the RaptorWorker for the next RAPTOR round, copying or creating states as needed.
     * If no previous departure minute or frequency draw has reached the next round before, initialize
     * the next round from the current round. This function gets called in both scheduled searches and frequency
     * searches, and the frequency search may proceed for more rounds than the scheduled search did.
     * We always need to initialize the scheduled state here even when running a frequency search, because
     * we copy that scheduled state to initialize each round of the frequency search.
     */
    public void advance () {
        if (scheduleState.size() == round + 1) {
            scheduleState.add(scheduleState.get(round).copy());
        } else {
            // Copy best times forward
            scheduleState.get(round + 1).min(scheduleState.get(round));
        }
        round++;
    }

    /**
     * This is the entry point to kick off a full RAPTOR search over many departure minutes randomizing
     * frequency routes as needed.
     * @param accessTimes a map from transit stops to the time it takes to reach those stops
     * @param nonTransitTimes the time to reach all targets without transit. Targets can be vertices or points/samples.
     */
    public PropagatedTimesStore runRaptor (TIntIntMap accessTimes, PointSetTimes nonTransitTimes, TaskStatistics ts) {
        long beginCalcTime = System.currentTimeMillis();
        TIntIntMap initialStops = new TIntIntHashMap(accessTimes);
        TIntIntIterator initialIterator = accessTimes.iterator();
        // TODO Isn't this just copying an int-int map into another one? Why reimplement map copy?
        // It appears that we could just use accessTimes directly without even copying it.
        while (initialIterator.hasNext()) {
            initialIterator.advance();
            int stopIndex = initialIterator.key();
            int accessTime = initialIterator.value();
            if (accessTime <= 0) {
                LOG.error("access time to stop {} is {}", stopIndex, accessTime);
            }
            initialStops.put(stopIndex, accessTime);
        }

        // We don't propagate travel times from stops out to the targets or street intersections when generating
        // a static site because the Javascript client does the propagation.
        boolean doPropagation = targets != null;

        // In normal usage propagatedTimesStore is null here, but in tests a custom one may have been injected.
        if (propagatedTimesStore == null) {
            if (doPropagation) {
                // Store min, max, avg travel times to all search targets (a set of points of interest in a PointSet)
                propagatedTimesStore = new PropagatedTimesStore(targets.size());
            } else {
                // Store all the travel times (not averages) to all transit stops.
                propagatedTimesStore = new StaticPropagatedTimesStore(data.getStopCount());
            }
        }

        int nTargets = targets != null ? targets.size() : data.getStopCount();

        // Optimization: if there are no scheduled routes, only run Monte Carlo frequencies
        int fromTime = req.fromTime;
        int monteCarloDraws = MONTE_CARLO_COUNT_PER_MINUTE;
        if (!data.hasSchedules) {
            // only do one iteration
            // FIXME this is not actually correct when the frequencies are changing over the departure time window
            fromTime = req.toTime - DEPARTURE_STEP_SEC;
            monteCarloDraws = TOTAL_MONTE_CARLO_COUNT;
        }

        // If there are no frequency routes, we don't do any Monte Carlo draws within departure minutes.
        // i.e. only a single RAPTOR search is run per departure minute.
        // If we do Monte Carlo, we do more iterations. But we only do Monte Carlo when we have frequencies.

        // First, set the number of iterations to the number of departure minutes.
        int iterations = (req.toTime - fromTime - DEPARTURE_STEP_SEC) / DEPARTURE_STEP_SEC + 1;

        // Now multiply the number of departure minutes by the number of Monte Carlo frequency draws per minute.
        if (data.hasFrequencies) {
            // We add 2 because we do two additional iterations for zero and maximal boarding times (not Monte Carlo draws).
            iterations *= (monteCarloDraws + 2);
        }

        ts.searchCount = iterations;

        // We will iterate backward through minutes (range-raptor) taking a snapshot travel times to targets after each iteration.
        timesAtTargetsEachIteration = new int[iterations][nTargets];

        ts.timeStep = DEPARTURE_STEP_SEC;

        // times at targets from scheduled search
        // we keep a single output array with clock times, and range raptor updates it only as needed as the departure
        // minute moves backwards.
        int[] scheduledTimesAtTargets = new int[nTargets];
        Arrays.fill(scheduledTimesAtTargets, UNREACHED);

        int iteration = 0; // The current iteration (over all departure minutes and Monte Carlo draws)
        int minuteNumber = 0; // How many different departure minutes have been hit so far, for display purposes.

        // FIXME this should be changed to tolerate a zero-width time range
        for (int departureTime = req.toTime - DEPARTURE_STEP_SEC; departureTime >= fromTime; departureTime -= DEPARTURE_STEP_SEC) {
            if (minuteNumber++ % 15 == 0) {
                LOG.info("minute {}", minuteNumber);
            }

            final int departureTimeFinal = departureTime;
            scheduleState.stream().forEach(rs -> rs.departureTime = departureTimeFinal);

            // Run the search on scheduled routes.
            this.runRaptorScheduled(initialStops, departureTime);

            // If we're doing propagation from transit stops out to street vertices, do it now.
            // If we are instead saving travel times to transit stops (not propagating out to the streets)
            // we skip this step -- we'll just copy them all at once, below. TODO clarify "all at once"
            if (doPropagation) {
                this.doPropagation(scheduleState.get(round).bestNonTransferTimes, scheduledTimesAtTargets, departureTime);

                // Copy in the pre-transit times
                // we don't want to force people to ride transit instead of walking a block.
                for (int i = 0; i < scheduledTimesAtTargets.length; i++) {
                    int nonTransitTravelTime = nonTransitTimes.getTravelTimeToPoint(i);
                    int nonTransitClockTime = nonTransitTravelTime + departureTime;
                    if (nonTransitTravelTime != UNREACHED && nonTransitClockTime < scheduledTimesAtTargets[i]) {
                        scheduledTimesAtTargets[i] = nonTransitClockTime;
                    }
                }
            }

            // Run any searches on frequency-based routes.
            if (data.hasFrequencies) {
                for (int i = 0; i < monteCarloDraws + 2; i++) {
                    offsets.randomize();

                    boolean includeThisIterationInAverages;

                    RaptorState stateCopy;
                    if (i == 0) {
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.BEST_CASE);
                        includeThisIterationInAverages = false; // don't include extrema in averages
                    }
                    else if (i == 1) {
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.WORST_CASE);
                        includeThisIterationInAverages = false;
                    }
                    else {
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.RANDOM);
                        includeThisIterationInAverages = true;
                    }

                    // do propagation
                    int[] frequencyTimesAtTargets = timesAtTargetsEachIteration[iteration++];
                    if (doPropagation) {
                        // copy scheduled times into frequency array so that we don't have to propagate them again, we'll
                        // just update them where they've improved, see #137.
                        System.arraycopy(scheduledTimesAtTargets, 0, frequencyTimesAtTargets, 0,
                                scheduledTimesAtTargets.length);
                        // updates timesAtTargetsEachIteration directly because it has a reference into the array.
                        this.doPropagation(stateCopy.bestNonTransferTimes, frequencyTimesAtTargets,
                                departureTime);
                    } else {
                        // copy times at stops into output (includes frequency and scheduled times because we copied the scheduled state)
                        System.arraycopy(stateCopy.bestNonTransferTimes, 0, frequencyTimesAtTargets, 0, stateCopy.bestNonTransferTimes.length);
                    }

                    if (includeThisIterationInAverages) includeInAverages.set(statesEachIteration.size());
                    statesEachIteration.add(stateCopy.deepCopy());

                    // convert to elapsed time
                    for (int t = 0; t < frequencyTimesAtTargets.length; t++) {
                        if (frequencyTimesAtTargets[t] != UNREACHED)
                            frequencyTimesAtTargets[t] -= departureTime;
                    }
                }
            } else {
                final int dt = departureTime;
                final RaptorState state = scheduleState.get(round);
                // if we're doing propagation, use propagated times, otherwise use times at stops
                timesAtTargetsEachIteration[iteration++] = IntStream.of(doPropagation ? scheduledTimesAtTargets : state.bestNonTransferTimes)
                        .map(i -> i != UNREACHED ? i - dt : i)
                        .toArray();
                includeInAverages.set(statesEachIteration.size());
                statesEachIteration.add(state.deepCopy());
            }
        }

        // make sure we filled the array, otherwise results are garbage.
        // This implies a bug in R5, but it has happened in the past when we did
        // not set the number of iterations correctly.
        // iteration should be incremented past end of array by ++ in assignment above
        if (iteration != iterations)
            throw new IllegalStateException("Iterations did not completely fill output array");

        long calcTime = System.currentTimeMillis() - beginCalcTime;
        LOG.info("calc time {}sec", calcTime / 1000.0);
        LOG.info("  propagation {}sec", totalPropagationTime / 1000.0);
        LOG.info("  raptor {}sec", (calcTime - totalPropagationTime) / 1000.0);
        LOG.info("{} rounds", round + 1);
        ts.propagation = (int) totalPropagationTime;
        ts.transitSearch = (int) (calcTime - totalPropagationTime);
        //dumpVariableByte(timesAtTargetsEachMinute);
        // we can use min_max here as we've also run it once with best case and worst case board,
        // so the best and worst cases are meaningful.
        propagatedTimesStore.setFromArray(timesAtTargetsEachIteration, includeInAverages, ConfidenceCalculationMethod.MIN_MAX);
        return propagatedTimesStore;
    }

    public void dumpVariableByte(int[][] array) {
        try {
            FileOutputStream fos = new FileOutputStream("/Users/abyrd/results.dat");
            CodedOutputStream cos = CodedOutputStream.newInstance(fos);
            cos.writeUInt32NoTag(array.length);
            for (int[] subArray : array) {
                cos.writeUInt32NoTag(subArray.length);
                for (int val : subArray) {
                    cos.writeInt32NoTag(val);
                }
            }
            fos.close();
        } catch (FileNotFoundException e) {
            LOG.error("File not found for dumping raptor results", e);
        } catch (IOException e) {
            LOG.error("IOException dumping raptor results", e);
        }
    }

    /** Run a raptor search not using frequencies */
    public void runRaptorScheduled (TIntIntMap initialStops, int departureTime) {
        max_time = departureTime + MAX_DURATION;
        round = 0;
        patternsTouched.clear(); // clear patterns left over from previous calls.
        allStopsTouched.clear();
        stopsTouched.clear();
        // Copy initial stops over to the first round
        TIntIntIterator iterator = initialStops.iterator();
        while (iterator.hasNext()) {
            iterator.advance();
            int stopIndex = iterator.key();
            int time = iterator.value() + departureTime;
            // note not setting bestNonTransferTimes here because the initial walk is effectively a "transfer"
            RaptorState state = scheduleState.get(0);
            if (time < state.bestTimes[stopIndex]) {
                state.bestTimes[stopIndex] = time;
                // don't clear previousPatterns/previousStops because we want to avoid egressing from the stop at which
                // we boarded, allowing one to blow past the walk limit. See #22.
                state.transferStop[stopIndex] = -1;
                markPatternsForStop(stopIndex);
            }
        }

        advance(); // go to first round

        // Anytime a round updates some stops, move on to another round
        while (doOneRound(scheduleState.get(round - 1), scheduleState.get(round), false, null)) {
            advance();
        }

        // we need to save the number of scheduled rounds so we can do at least this many frequency rounds plus one.
        // however, we can't do the test below as empty rounds are added to the end of scheduleState when the frequency
        // search runs off the end of it.
        // we can't just use scheduleState.size() because it will be expanded with empty states by the frequency search.
        scheduledRounds = round + 1;

        // make sure new times are propagated all the way to the end even if we did fewer rounds on a future search
        while (round < scheduleState.size() - 1) {
            scheduleState.get(round + 1).min(scheduleState.get(round));
            round++;
        }
    }

    /**
     * Run a RAPTOR search using frequencies. Return the resulting state, which is a copy of scheduled states with
     * frequencies applied. We make a copy because range-RAPTOR is invalid with frequencies.
     */
    public RaptorState runRaptorFrequency (int departureTime, BoardingAssumption boardingAssumption) {
        max_time = departureTime + MAX_DURATION;

        round = 0;
        advance(); // go to first round
        patternsTouched.clear(); // clear patterns left over from previous calls.
        allStopsTouched.clear();
        stopsTouched.clear();

        // mark only frequency patterns here. Any scheduled patterns that are reached downstream of frequency patterns
        // will be marked during the search and explored in subsequent rounds.
        for (int p = 0; p < data.tripPatterns.size(); p++) {
            TripPattern pat = data.tripPatterns.get(p);
            if (pat.hasFrequencies) {
                patternsTouched.set(p);
            }
        }

        // initialize with first round from scheduled search
        // no need to make a copy here as this is not updated in the search
        RaptorState previousRound = scheduleState.get(round - 1);
        RaptorState currentRound = scheduleState.get(round).copy();
        currentRound.previous = previousRound;

        // Anytime a round updates some stops, move on to another round
        // Do at least as many rounds as were done in the scheduled search plus one, so that we don't return a state
        // at a previous round and cut off the scheduled search after 0, 1 or 2 transfers (see https://github.com/conveyal/r5/issues/82)
        // However, if we didn't run a scheduled search, don't apply this constraint
        while (doOneRound(previousRound, currentRound, true, boardingAssumption) || (scheduledRounds != -1 && round <= scheduledRounds)) {
            advance();
            previousRound = currentRound;
            currentRound = previousRound.copy();
            // copy in scheduled times
            currentRound.min(scheduleState.get(round));

            // re-mark all frequency patterns if we did a scheduled search, so that we explore them again;
            // they may be reached at the second, third or later round by a scheduled trips
            if (data.hasSchedules) {
                for (int p = 0; p < data.tripPatterns.size(); p++) {
                    TripPattern pat = data.tripPatterns.get(p);
                    if (pat.hasFrequencies) {
                        patternsTouched.set(p);
                    }
                }
            }
        }
        
        return currentRound;
    }

    /**
     * perform one round, possibly using frequencies with the defined boarding assumption
     * (which is ignored and may be set to null if useFrequencies == false)
     *
     * Note that schedules are always used. This is important, because it is possible to transfer between frequency and
     * scheduled service an arbitrary number of times. So we can run the scheduled search and get a certain output, but
     * when we run a frequency search on top of that, we may be able to catch a scheduled vehicle earlier by transferring
     * from the frequency vehicle. So when we run the frequency search we also include any scheduled patterns touched
     * during the frequency search.
     */
    public boolean doOneRound (RaptorState inputState, RaptorState outputState, boolean useFrequencies, BoardingAssumption boardingAssumption) {
        // We need to have a separate state we copy from to prevent ridiculous paths from being taken because multiple
        // vehicles can be ridden in the same round (see issue #23)

        // Suppose lines are arranged in the graph in order 1...n, and line 2 goes towards the destination and 1 away
        // from it. The RAPTOR search will first encounter line 1 and ride it away from the destination. Then it will
        // board line 2 (in the same round) and ride it towards the destination. If frequencies are low enough, it will
        // catch the same trip it would otherwise have caught by boarding at the origin, so will not re-board at the
        // origin, hence the crazy path (which is still optimal in the earliest-arrival sense). This is also why some
        // paths have a huge tangle of routes, because the router is finding different ways to kill time somewhere along
        // the route before catching the bus that will take you to the destination.
        //LOG.info("round {}", round);
        stopsTouched.clear(); // clear any stops left over from previous round.
        PATTERNS: for (int p = patternsTouched.nextSetBit(0); p >= 0; p = patternsTouched.nextSetBit(p+1)) {
            //LOG.info("pattern {} {}", p, data.patternNames.get(p));
            TripPattern timetable = data.tripPatterns.get(p);
            // Do not even consider patterns that have no trips on active service IDs.
            // Anecdotally this can double or triple search speed.
            if ( ! timetable.servicesActive.intersects(servicesActive)) {
                continue;
            }
            int stopPositionInPattern = -1; // first increment will land this at zero

            int bestFreqBoardTime = Integer.MAX_VALUE;
            int bestFreqBoardStop = -1;
            int bestFreqBoardStopIndex = -1;
            TripSchedule bestFreqTrip = null; // this is which _trip_ we are on if we are riding a frequency-based service. It is
            // not important which frequency entry we used to board it.

            // first look for a frequency entry
            if (useFrequencies) {
                for (int stopIndex : timetable.stops) {
                    stopPositionInPattern += 1;

                    // the time at this stop if we remain on board a vehicle we had already boarded
                    int remainOnBoardTime;
                    if (bestFreqTrip != null) {
                        // we are already aboard a trip, stay on board
                        remainOnBoardTime = bestFreqBoardTime + bestFreqTrip.arrivals[stopPositionInPattern] -
                                bestFreqTrip.departures[bestFreqBoardStop];
                    } else {
                        // we cannot remain on board as we are not yet on board
                        remainOnBoardTime = Integer.MAX_VALUE;
                    }

                    // the time at this stop if we board a new vehicle
                    if (inputState.bestTimes[stopIndex] != UNREACHED) {
                        for (int tripScheduleIdx = 0; tripScheduleIdx < timetable.tripSchedules.size(); tripScheduleIdx++) {
                            TripSchedule ts = timetable.tripSchedules.get(tripScheduleIdx);
                            if (ts.headwaySeconds == null || !servicesActive.get(ts.serviceCode))
                                continue; // not a frequency trip

                            // TODO: boarding assumptions, transfer rules?
                            // figure out best board time for each frequency entry on this trip, and choose the best of those
                            // this is a valid thing to do and doesn't exhibit the problems we've seen in the past with
                            // monte carlo simulations where we take the min of several random variables (e.g. when we used
                            // to randomly choose a boarding wait on each boarding, see https://github.com/opentripplanner/OpenTripPlanner/issues/2072 and
                            // https://github.com/opentripplanner/OpenTripPlanner/issues/2065).
                            // In this case it is completely valid to assume that any frequency entries are uncorrelated,
                            // and it's only a problem when they overlap anyhow.
                            int boardTime = Integer.MAX_VALUE;
                            FREQUENCY_ENTRIES: for (int freqEntryIdx = 0; freqEntryIdx < ts.headwaySeconds.length; freqEntryIdx++) {
                                int boardTimeThisEntry;

                                if (boardingAssumption == BoardingAssumption.BEST_CASE) {
                                    if (inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern])
                                        continue FREQUENCY_ENTRIES; // it's too late, can't board.

                                    // best case boarding time is now, or when this frequency entry starts _at this stop_,
                                    // whichever is later
                                    boardTimeThisEntry = Math.max(inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS, ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern]);
                                }
                                else if (boardingAssumption == BoardingAssumption.WORST_CASE) {
                                    // worst case: cannot board this entry if there is not the full headway remaining before the end of the entry, we
                                    // might miss the vehicle.
                                    if (inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] - ts.headwaySeconds[freqEntryIdx])
                                        continue FREQUENCY_ENTRIES;

                                    boardTimeThisEntry = Math.max(inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS + ts.headwaySeconds[freqEntryIdx],
                                            ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] + ts.headwaySeconds[freqEntryIdx]);
                                }
                                else {
                                    // should not throw NPE, if it does something is messed up because these should
                                    // only be null for scheduled trips on a trip pattern with some frequency trips.
                                    // we shouldn't be considering scheduled trips here.
                                    int offset = offsets.offsets.get(p)[tripScheduleIdx][freqEntryIdx];

                                    // earliest board time is start time plus travel time plus offset
                                    boardTimeThisEntry = ts.startTimes[freqEntryIdx] +
                                            ts.departures[stopPositionInPattern] +
                                            offset;

                                    while (boardTimeThisEntry < inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                                        boardTimeThisEntry += ts.headwaySeconds[freqEntryIdx];

                                        // subtract the travel time to this stop from the board time at this stop, this gives
                                        // us the terminal departure. If the terminal departure is after the end time, the vehicle
                                        // is not running.
                                        if (boardTimeThisEntry - ts.departures[stopPositionInPattern] > ts.endTimes[freqEntryIdx]) {
                                            // can't board this frequency entry
                                            continue FREQUENCY_ENTRIES;
                                        }
                                    }
                                }

                                // if we haven't continued the outer loop yet, we could potentially board this stop
                                boardTime = Math.min(boardTime, boardTimeThisEntry);
                            }

                            if (boardTime != Integer.MAX_VALUE && boardTime < remainOnBoardTime) {
                                // make sure we board the best frequency entry at a stop
                                if (bestFreqBoardStop == stopPositionInPattern && bestFreqBoardTime < boardTime)
                                    continue;

                                // board this vehicle
                                // note: this boards the trip with the lowest headway at the given time.
                                // if there are overtaking trips all bets are off.
                                bestFreqBoardTime = boardTime;
                                bestFreqBoardStop = stopPositionInPattern;
                                bestFreqBoardStopIndex = stopIndex;
                                bestFreqTrip = ts;
                                // note that we do not break the loop in case there's another frequency entry that is better
                            }
                        }
                    }

                    // save the remain on board time. If we boarded a new trip then we know that the
                    // remain on board time must be larger than the arrival time at the stop so will
                    // not be saved; no need for an explicit check.
                    if (remainOnBoardTime != Integer.MAX_VALUE && remainOnBoardTime < max_time) {
                        if (outputState.bestNonTransferTimes[stopIndex] > remainOnBoardTime) {
                            outputState.bestNonTransferTimes[stopIndex] = remainOnBoardTime;
                            outputState.previousPatterns[stopIndex] = p;
                            outputState.previousStop[stopIndex] = bestFreqBoardStopIndex;

                            stopsTouched.set(stopIndex);
                            allStopsTouched.set(stopIndex);

                            if (outputState.bestTimes[stopIndex] > remainOnBoardTime) {
                                outputState.bestTimes[stopIndex] = remainOnBoardTime;
                                outputState.transferStop[stopIndex] = -1; // not reached via a transfer
                            }

                            if (outputState.bestNonTransferTimes[stopIndex] > inputState.bestNonTransferTimes[stopIndex] ||
                                    outputState.bestTimes[stopIndex] > inputState.bestTimes[stopIndex]) {
                                LOG.error("Relaxing stop increased travel time at stop {}, can't happen", stopIndex);
                            }

                            if (remainOnBoardTime < outputState.departureTime) {
                                LOG.error("Negative speed travel, path dump follows:\n{}", outputState.dump(stopIndex));
                            }
                        }
                    }
                }

                // don't mix frequencies and timetables
                // TODO should we have this condition here?
                if (bestFreqTrip != null)
                    continue PATTERNS;
            }

            // perform scheduled search
            // We always perform a scheduled search, even when we're doing a frequency search as well. This is important
            // in mixed networks, because the frequency trips may allow you to reach scheduled trips more quickly. We perform
            // an initial search with only schedules which serves as a bound, but we must finish with a search that includes
            // _all_ transit service.
            TripSchedule onTrip = null;
            int onTripIdx = -1;
            int boardStopIndex = -1;
            stopPositionInPattern = -1;
            for (int stopIndex : timetable.stops) {
                stopPositionInPattern += 1;
                if (onTrip == null) {
                    // We haven't boarded yet
                    if (inputState.bestTimes[stopIndex] == UNREACHED) {
                        continue; // we've never reached this stop, we can't board.
                    }
                    // Stop has been reached before. Attempt to board here.

                    int tripIdx = -1;
                    for (TripSchedule trip : timetable.tripSchedules) {
                        tripIdx++;
                        // Do not board frequency trips or trips whose services are not active on the given day
                        if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode))
                            continue;

                        int dep = trip.departures[stopPositionInPattern];
                        if (dep > inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                            onTrip = trip;
                            onTripIdx = tripIdx;
                            boardStopIndex = stopIndex;
                            break; // trips are sorted, we've found the earliest usable one
                        }
                    }

                    continue; // boarded or not, we move on to the next stop in the sequence
                } else {
                    // We're on board a trip. At this particular stop on this trip, update best arrival time
                    // if we've improved on the existing one.
                    int arrivalTime = onTrip.arrivals[stopPositionInPattern];

                    if (arrivalTime > max_time)
                        // Cut off the search, don't continue searching this pattern
                        continue PATTERNS;

                    if (arrivalTime < outputState.bestNonTransferTimes[stopIndex]) {
                        outputState.bestNonTransferTimes[stopIndex] = arrivalTime;
                        outputState.previousPatterns[stopIndex] = p;
                        outputState.previousStop[stopIndex] = boardStopIndex;

                        stopsTouched.set(stopIndex);
                        allStopsTouched.set(stopIndex);

                        if (arrivalTime < outputState.bestTimes[stopIndex]) {
                            outputState.bestTimes[stopIndex] = arrivalTime;
                            outputState.transferStop[stopIndex] = -1; // not reached via transfer
                        }
                    }

                    // Check whether we can switch to an earlier trip, because there was a faster way to
                    // get to this stop than the trip we're currently on.
                    if (inputState.bestTimes[stopIndex] < arrivalTime) {
                        int bestTripIdx = onTripIdx;
                        // Step backward toward index 0 (inclusive!)
                        while (--bestTripIdx >= 0) {
                            TripSchedule trip = timetable.tripSchedules.get(bestTripIdx);
                            if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode)) {
                                // This is a frequency trip or it is not running on the day of the search.
                                continue;
                            }
                            if (trip.departures[stopPositionInPattern] > inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                                // This trip is running and departs later than we have arrived at this stop.
                                onTripIdx = bestTripIdx;
                                onTrip = trip;
                                boardStopIndex = stopIndex;
                            } else {
                                // This trip arrives at this stop too early. Trips are sorted by time, don't keep looking.
                                break;
                            }
                        }
                    }
                }
            }
        }

        doTransfers(outputState);
        // doTransfers will have marked some patterns if the search reached any stops.
        return !patternsTouched.isEmpty();
    }

    /**
     * Apply transfers.
     * This is also where patterns are marked for exploration on future rounds;
     * all the patterns passing through these stops and any stops transferred to will be marked.
     *
     * This operates on a single round; we don't have separate rounds for transfers; see comments in RaptorState.
     */
    private void doTransfers(RaptorState state) {
        patternsTouched.clear();
        for (int stop = stopsTouched.nextSetBit(0); stop >= 0; stop = stopsTouched.nextSetBit(stop + 1)) {
            // First, mark all patterns at this stop (the trivial "stay at the stop where you are" loop transfer).
            markPatternsForStop(stop);

            // Then follow all transfers out of this stop, marking patterns that pass through those target stops.
            int fromTime = state.bestNonTransferTimes[stop];
            TIntList transfers = data.transfersForStop.get(stop);
            // Transfers are stored as a flattened 2D array, advance two elements at a time.
            for (int i = 0; i < transfers.size(); i += 2) {
                int toStop = transfers.get(i);
                int distance = transfers.get(i + 1);
                int toTime = fromTime + (int) (distance / req.walkSpeed);
                if (toTime < max_time && toTime < state.bestTimes[toStop]) {
                    state.bestTimes[toStop] = toTime;
                    state.transferStop[toStop] = stop;
                    markPatternsForStop(toStop);
                }
            }
        }
    }

    /**
     * Propagate from the transit network to the street network.
     * Uses allStopsTouched to determine from whence to propagate.
     *
     * This is valid both for randomized frequencies and for schedules, because the stops that have
     * been updated will be in allStopsTouched.
     *
     * It must be called after every search (either a minute of the scheduled search, or a frequency search on top of the
     * scheduled network). This is because propagation only occurs from stops that are marked in allStopsTouched, which
     * is cleared before each search.
     */
    public void doPropagation (int[] timesAtTransitStops, int[] timesAtTargets, int departureTime) {
        long beginPropagationTime = System.currentTimeMillis();

        // Record distances to each sample or intersection
        // We need to propagate all the way to samples (or intersections if there are no samples)
        // when doing repeated RAPTOR.
        // Consider the situation where there are two parallel transit lines on
        // 5th Street and 6th Street, and you live on A Street halfway between 5th and 6th.
        // Both lines run at 30 minute headways, but they are exactly out of phase, and for the
        // purposes of this conversation both go the same place with the same in-vehicle travel time.
        // Thus, even though the lines run every 30 minutes, you never experience a wait of more than
        // 15 minutes because you are clever when you choose which line to take. The worst case at each
        // transit stop is much worse than the worst case at samples. While unlikely, it is possible that
        // a sample would be able to reach these two stops within the walk limit, but that the two
        // intersections it is connected to cannot reach both.

        // only loop over stops that were touched in this particular search (schedule or frequency). We are updating
        // timesAtTargets, which already contains times that were found during previous searches (either scheduled searches
        // at previous minutes, or in the case of a frequency search, the scheduled search that was run at the same minute)
        for (int s = allStopsTouched.nextSetBit(0); s >= 0; s = allStopsTouched.nextSetBit(s + 1)) {
            // it's safe to use the best time at this stop for any number of transfers, even in range-raptor,
            // because we allow unlimited transfers. this is slightly different from the original RAPTOR implementation:
            // we do not necessarily compute all pareto-optimal paths on (journey time, number of transfers).
            int baseTimeSeconds = timesAtTransitStops[s];
            if (baseTimeSeconds != UNREACHED) {
                int[] targets = this.targets.stopTrees.get(s);
                if (targets == null) {
                    continue;
                }
                // Targets contains pairs of (targetIndex, time).
                // The cache has time in seconds rather than distance to avoid costly floating-point divides and integer casts here.
                for (int i = 0; i < targets.length; ) { // Counter i is incremented in two places below.
                    int targetIndex = targets[i++]; // Increment i after read
                    int propagated_time = baseTimeSeconds + targets[i++]; // Increment i after read

                    if (propagated_time < departureTime) {
                        //LOG.error("negative propagated time, will crash shortly");
                    }

                    if (timesAtTargets[targetIndex] > propagated_time) {
                        timesAtTargets[targetIndex] = propagated_time;
                    }
                }
            }
        }
        totalPropagationTime += (System.currentTimeMillis() - beginPropagationTime);
    }

    /** Mark all the patterns passing through the given stop. */
    private void markPatternsForStop(int stop) {
        TIntList patterns = data.patternsForStop.get(stop);
        for (TIntIterator it = patterns.iterator(); it.hasNext();) {
            int pattern = it.next();
            patternsTouched.set(pattern);
        }
    }
}
