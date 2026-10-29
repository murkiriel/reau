package com.reau.app.weather

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Open-Meteo's answer (a small one in its real shape: current, columnar hourly and daily) read by [WeatherParser]; on the device, where org.json is real. */
@RunWith(AndroidJUnit4::class)
class WeatherParserTest {
    private val answer = """
        {"current":{"time":"2030-01-15T15:00","temperature_2m":23.6,"apparent_temperature":25.1,"relative_humidity_2m":61,
          "precipitation":0.2,"weather_code":61,"wind_speed_10m":12.4,"wind_direction_10m":200,"wind_gusts_10m":30.2,
          "surface_pressure":1009.5,"cloud_cover":80,"is_day":1},
         "hourly":{"time":["2030-01-15T15:00","2030-01-15T16:00","2030-01-15T17:00"],
          "temperature_2m":[23.6,22.9,null],"precipitation_probability":[40,60,70],"weather_code":[61,63,63],"uv_index":[5.1,3.2,1.0]},
         "daily":{"time":["2030-01-15","2030-01-16"],"temperature_2m_max":[27.0,29.5],"temperature_2m_min":[18.0,null],
          "precipitation_probability_max":[70,10],"uv_index_max":[8.0,9.0],"sunrise":["2030-01-15T06:12","2030-01-16T06:13"],
          "sunset":["2030-01-15T18:47","2030-01-16T18:48"],"daylight_duration":[44400.0,44460.0],
          "wind_speed_10m_max":[20.0,15.0],"wind_gusts_10m_max":[40.0,30.0],"weather_code":[63,1]}}
    """.trimIndent()

    @Test
    fun theCurrentReadingHasEveryField() {
        val now = WeatherParser.parse(answer).current!!
        assertEquals(23.6, now.temperatureC, 0.001)
        assertEquals(61, now.relativeHumidityPercent)
        assertEquals(30.2, now.windGustsKmh!!, 0.001)
        assertEquals(1009.5, now.pressureHpa!!, 0.001)
        assertEquals(true, now.isDay)
        assertEquals(WeatherCondition.RAIN, WeatherCondition.forCode(now.weatherCode))
    }

    @Test
    fun anHourWithNoTemperatureAndADayWithNoMinimumAreLeftOut() {
        val forecast = WeatherParser.parse(answer)
        assertEquals(listOf("2030-01-15T15:00", "2030-01-15T16:00"), forecast.hourly.map { it.timeIso })
        assertEquals(listOf("2030-01-15"), forecast.daily.map { it.dateIso })
        assertEquals(70, forecast.daily.single().precipitationProbabilityMaxPercent)
        assertEquals("2030-01-15T06:12", forecast.daily.single().sunriseIso)
    }

    @Test
    fun theNextQuartersAndThePlacesOffsetAreRead() {
        val withQuarters = answer.replaceFirst("{\"current\"", "{\"utc_offset_seconds\":-10800,\"minutely_15\":{\"time\":[\"2030-01-15T15:00\",\"2030-01-15T15:15\",\"2030-01-15T15:30\"]," +
            "\"precipitation\":[0.0,0.4,null],\"weather_code\":[1,61,null],\"wind_gusts_10m\":[22.5,31.0,null]},\"current\"")
        val forecast = WeatherParser.parse(withQuarters)
        assertEquals(-10_800, forecast.utcOffsetSeconds)
        assertEquals("2030-01-15T15:00", forecast.current?.timeIso)
        assertEquals(listOf("2030-01-15T15:00", "2030-01-15T15:15", "2030-01-15T15:30"), forecast.minutely.map { it.timeIso })
        assertEquals(0.4, forecast.minutely[1].precipitationMm!!, 0.001)
        assertEquals(61, forecast.minutely[1].weatherCode)
        assertEquals(31.0, forecast.minutely[1].gustsKmh!!, 0.001)
        assertNull("a quarter with nothing said keeps its time and no values", forecast.minutely[2].precipitationMm)
        assertNull(forecast.minutely[2].weatherCode)
        assertNull(forecast.minutely[2].gustsKmh)
    }

    @Test
    fun anAnswerWithNoQuartersAndNoOffsetHasNone() {
        val forecast = WeatherParser.parse(answer)
        assertEquals(emptyList<MinutelyWeather>(), forecast.minutely)
        assertNull(forecast.utcOffsetSeconds)
    }

    @Test
    fun withoutATemperatureThereIsNoCurrentReading() {
        assertNull(WeatherParser.parse("""{"current":{"weather_code":1}}""").current)
        assertNull(WeatherParser.parse("{}").current)
    }
}
