package com.orderflow.inventory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A {@link Clock} whose "now" can be advanced by a test to trigger time-based logic. */
public class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        this.now = this.now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
