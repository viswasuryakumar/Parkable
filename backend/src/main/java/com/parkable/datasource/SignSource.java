package com.parkable.datasource;

import com.parkable.model.Rule;

import java.time.ZoneId;
import java.util.List;

/**
 * Source seam for parking signs near a location. A future government-data
 * source can implement this without changing the rules engine.
 */
public interface SignSource {

    List<Rule> fetch(double latitude, double longitude, ZoneId zone);
}
