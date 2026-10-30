package com.mohith.bustracker.eta.config;

import com.mohith.bustracker.common.gtfs.GtfsLoader;
import com.mohith.bustracker.common.gtfs.GtfsModel.GtfsData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class EtaConfig {
    @Bean
    GtfsData gtfsData(@Value("${bustracker.data-dir}") String dir) throws IOException {
        return new GtfsLoader(dir).load();
    }
}