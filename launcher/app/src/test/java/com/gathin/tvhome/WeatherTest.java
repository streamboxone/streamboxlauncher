package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WeatherTest {
    @Test public void celsiusRoundsAsRead() {
        assertEquals(12, Weather.degrees(11.6, false));
        assertEquals(-3, Weather.degrees(-3.4, false));
    }

    @Test public void fahrenheitConvertsBeforeRounding() {
        assertEquals(32, Weather.degrees(0, true));
        assertEquals(53, Weather.degrees(11.6, true));
    }

    @Test public void aClearSkyDependsOnTheHour() {
        assertEquals(R.string.weather_sunny, Weather.condition(0, true));
        assertEquals(R.string.weather_clear, Weather.condition(0, false));
    }

    @Test public void codesMapToWords() {
        assertEquals(R.string.weather_cloudy, Weather.condition(3, true));
        assertEquals(R.string.weather_rain, Weather.condition(63, true));
        assertEquals(R.string.weather_heavy_snow, Weather.condition(75, false));
        assertEquals(R.string.weather_thunderstorm, Weather.condition(95, true));
    }

    @Test public void anUnknownCodeSaysSoRatherThanGuessing() {
        assertEquals(R.string.weather_unknown, Weather.condition(1234, true));
    }

    @Test public void aReadingAgesOut() {
        assertTrue(Weather.fresh(100, 150, 100));
        assertFalse(Weather.fresh(100, 200, 100));
        assertFalse(Weather.fresh(100, 50, 100));   // the clock went backwards: a new boot
    }
}
