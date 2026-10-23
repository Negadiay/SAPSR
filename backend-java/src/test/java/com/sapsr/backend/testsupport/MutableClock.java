package com.sapsr.backend.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Часы для тестов тайм-аутов: время стоит, пока тест не сдвинет его сам. */
public final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
