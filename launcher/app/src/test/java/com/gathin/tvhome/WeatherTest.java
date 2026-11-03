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

    @Test public void aClearSkyIconDependsOnTheHour() {
        assertEquals(R.drawable.ic_weather_sun, Weather.icon(0, true));
        assertEquals(R.drawable.ic_weather_moon, Weather.icon(0, false));
        assertEquals(R.drawable.ic_weather_partly_night, Weather.icon(2, false));
    }

    @Test public void everyKnownCodeHasAnIconAndUnknownOnesHaveNone() {
        int[] known = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99};
        for (int code : known) assertTrue("code " + code, Weather.icon(code, true) != 0);
        assertEquals(0, Weather.icon(1234, true));
    }

    @Test public void aReadingAgesOut() {
        assertTrue(Weather.fresh(100, 150, 100));
        assertFalse(Weather.fresh(100, 200, 100));
        assertFalse(Weather.fresh(100, 50, 100));   // the clock went backwards: a new boot
    }
}
