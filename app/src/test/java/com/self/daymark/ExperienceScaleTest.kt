package com.self.daymark

import org.junit.Assert.*
import org.junit.Test

class ExperienceScaleTest {
    @Test fun ratingsAcceptNegativeNeutralPositiveAndUnansweredValues() {
        for (score in listOf(null, -2, -1, 0, 1, 2)) {
            Event(title="体验", startAt=10, feeling=score, worth=score).validate()
            Forecast(eventId="event", expectedFeeling=score).validate()
        }
        for (score in listOf(-3, 3, 5)) {
            assertThrows(IllegalArgumentException::class.java) { Event(title="体验", startAt=10, feeling=score).validate() }
            assertThrows(IllegalArgumentException::class.java) { Event(title="体验", startAt=10, worth=score).validate() }
            assertThrows(IllegalArgumentException::class.java) { Forecast(eventId="event", expectedFeeling=score).validate() }
        }
    }

    @Test fun neutralIsIncludedInPredictionErrorsWhileUnansweredRemainsUnknown() {
        val event=Event(id="event",title="体验",startAt=10,feeling=0,firstOutcomeAt=20)
        val forecast=Forecast(eventId=event.id,madeAt=1,expectedFeeling=-2,prospective=true)
        assertEquals(2,Analytics.errors(LedgerData(events=listOf(event),forecasts=listOf(forecast))).single().feelingError)
        assertNull(Analytics.errors(LedgerData(events=listOf(event.copy(feeling=null)),forecasts=listOf(forecast))).single().feelingError)
    }
}
