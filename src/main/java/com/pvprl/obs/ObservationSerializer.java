package com.pvprl.obs;

import com.google.gson.Gson;

/**
 * Serializes {@link Observation} trees to JSON.
 *
 * <p>Gson serializes Java records field-by-field out of the box, so the records in this
 * package define the exact wire schema. A single shared {@link Gson} instance is reused
 * because it is thread-safe and avoids per-tick allocation.</p>
 */
public final class ObservationSerializer {
    private static final Gson GSON = new Gson();

    private ObservationSerializer() {
    }

    /**
     * @param observation the observation to encode
     * @return the JSON representation
     */
    public static String toJson(Observation observation) {
        return GSON.toJson(observation);
    }
}
