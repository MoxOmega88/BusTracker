package com.mohith.bustracker.simulator.config;

import com.mohith.bustracker.common.gtfs.GtfsLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SimulatorConfig {
    @Bean
    GtfsLoader gtfsLoader(@Value("${bustracker.data-dir}") String dir) {
        return new GtfsLoader(dir);
    }
}