package com.mohith.bustracker.eta.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

@Component
public class AccuracyTracker {
    private static final Logger log = LoggerFactory.getLogger(AccuracyTracker.class);

    private final DoubleAdder schedErr = new DoubleAdder();
    private final DoubleAdder corrErr = new DoubleAdder();
    private final LongAdder count = new LongAdder();

    public void record(double schedErrSec, double corrErrSec) {
        schedErr.add(schedErrSec);
        corrErr.add(corrErrSec);
        count.increment();
    }

    @Scheduled(fixedRate = 30000, initialDelay = 30000)
    public void report() {
        long n = count.sum();
        if (n == 0) return;
        double s = schedErr.sum() / n;
        double c = corrErr.sum() / n;
        double improvement = s > 0 ? (1 - c / s) * 100 : 0;
        log.info(String.format(
                "ETA accuracy over %d predictions: schedule MAE=%.1fs, corrected MAE=%.1fs, improvement=%.1f%%",
                n, s, c, improvement));
    }
}