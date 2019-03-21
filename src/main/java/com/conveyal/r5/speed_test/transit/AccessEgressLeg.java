package com.conveyal.r5.speed_test.transit;

import com.conveyal.r5.profile.otp2.api.transit.TransferLeg;
import com.conveyal.r5.profile.otp2.util.TimeUtils;

public class AccessEgressLeg implements TransferLeg {
    private final int stop, durationInSeconds;

    public AccessEgressLeg(int stop, int durationInSeconds) {
        this.stop = stop;
        this.durationInSeconds = durationInSeconds;
    }

    @Override
    public int stop() {
        return stop;
    }

    @Override
    public int durationInSeconds() {
        return durationInSeconds;
    }


    @Override
    public String toString() {
        return TimeUtils.timeToStrCompact(durationInSeconds) + " " + stop;
    }
}
