@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.livelingo.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import kotlin.time.Instant

class UsageCostsTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilliseconds()
    private fun meeting(start: Long = ms("2026-10-31T16:00:00Z")) = MeetingUsage("m", "會議", start,
        ModelProvider.OPENAI, "gpt-live-1", "gpt-6-luna", CostRates(voicePerMinute = 0.05, inputPerMillion = 0.10, cachedPerMillion = 0.01, outputPerMillion = 0.50), endedAtMs = start + 3600_000)

    @Test fun cumulativeUsageDoesNotDoubleCountOrRoundUpMinutes() {
        var record = meeting()
        for (seconds in listOf(12, 60, 60, 128)) record = record.update(liveUsage("""{"type":"session.usage.updated","usage":{"seconds":$seconds}}""")!!)
        record = record.update(liveUsage("""{"type":"session.closed","usage":{"seconds":128}}""")!!)
        record = record.update(UsageSample("voice", UsageKind.VOICE, seconds = 130.0, estimated = true))
        assertEquals(1, record.samples.size)
        assertEquals(128.0 / 60 * 0.05, record.total().usd, 1e-12)
        assertFalse(record.samples.single().estimated)
    }

    @Test fun countsCachedTokensOnceAndDoesNotAddReasoningTwice() {
        val sample = chatUsage(WireJson.parseToJsonElement("""{"usage":{"prompt_tokens":1000,"prompt_tokens_details":{"cached_tokens":600},"completion_tokens":200,"completion_tokens_details":{"reasoning_tokens":150}}}""").jsonObject, "r", UsageKind.FORMATTING)
        assertEquals(0.000146, sample.cost(meeting().rates).usd, 1e-12)
        val updated = meeting().update(sample).update(sample)
        assertEquals(1, updated.samples.size)
        assertEquals(200L, updated.samples.single().outputTokens)
    }

    @Test fun pendingFailedAndMissingUsageAreNotReportedAsFree() {
        val pending = UsageSample("r", UsageKind.TRANSLATION, known = false)
        assertTrue(pending.cost(meeting().rates).incomplete)
        val missing = chatUsage(WireJson.parseToJsonElement("""{"choices":[]}""").jsonObject, "r", UsageKind.TRANSLATION)
        assertFalse(missing.known)
        assertEquals(1, meeting().update(pending).update(missing).total().missing)
        assertFalse(chatUsage(WireJson.parseToJsonElement("""{"usage":{"prompt_tokens":10,"completion_tokens":-1}}""").jsonObject, "r", UsageKind.TRANSLATION).known)
        assertFalse(chatUsage(WireJson.parseToJsonElement("""{"usage":{"prompt_tokens":10,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":20}}}""").jsonObject, "r", UsageKind.TRANSLATION).known)
    }

    @Test fun unknownPricesPreserveKnownSubtotalAndMayBeManuallyRepriced() {
        val value = meeting().copy(rates = CostRates(inputPerMillion = 1.0, outputPerMillion = 2.0))
            .update(UsageSample("voice", UsageKind.VOICE, seconds = 60.0))
            .update(UsageSample("text", UsageKind.TRANSLATION, inputTokens = 1000, outputTokens = 1000))
        assertEquals(0.003, value.total().usd, 1e-12)
        assertEquals(1, value.total().unpriced)
        assertEquals(0.053, value.copy(rates = value.rates.copy(voicePerMinute = 0.05)).total().usd, 1e-12)
        assertEquals(1, value.total().unpriced) // original snapshot unchanged
    }

    @Test fun taiwanMidnightMondayMonthAndLeapYearBoundaries() {
        assertEquals("2026-11-01", taipeiDate(ms("2026-10-31T16:00:00Z")))
        assertEquals("2026-10-26", taipeiDate(periodStart(ms("2026-11-01T15:59:59Z"), CostPeriod.WEEK)))
        assertEquals("2026-11-02", taipeiDate(periodStart(ms("2026-11-01T16:00:00Z"), CostPeriod.WEEK)))
        assertEquals("2026-11-01", taipeiDate(periodStart(ms("2026-10-31T16:00:00Z"), CostPeriod.MONTH)))
        assertEquals("2027-01-01", taipeiDate(shiftPeriod(ms("2026-11-30T16:00:00Z"), CostPeriod.MONTH, 1)))
        assertEquals("2024-02-01", taipeiDate(shiftPeriod(ms("2024-02-29T16:00:00Z"), CostPeriod.MONTH, -1)))
    }

    @Test fun graphFillsZeroPeriodsAndGroupsByStartEvenWhenMeetingCrossesMidnight() {
        val start = ms("2026-10-31T15:59:00Z")
        val record = meeting(start).copy(endedAtMs = ms("2026-10-31T16:01:00Z"))
            .update(UsageSample("voice", UsageKind.VOICE, seconds = 120.0))
        val old = TranscriptRecord("old", "舊會議", start, SessionSettings())
        val buckets = costBuckets(listOf(record), listOf(old), CostPeriod.DAY, ms("2026-10-31T16:05:00Z"))
        assertEquals(30, buckets.size)
        assertEquals(0.0, buckets.last().total.usd)
        assertEquals(0.1, buckets[buckets.lastIndex - 1].total.usd, 1e-12)
        assertEquals(1, buckets[buckets.lastIndex - 1].total.missing)
        assertEquals(2, buckets[buckets.lastIndex - 1].meetings)
    }

    @Test fun serializationRetainsRatesUsageAndOlderTranscriptsHaveNoInventedUsage() {
        val saved = meeting().update(UsageSample("voice", UsageKind.VOICE, seconds = 61.0))
        assertEquals(saved, WireJson.decodeFromString<MeetingUsage>(WireJson.encodeToString(saved)))
        val old = WireJson.decodeFromString<TranscriptRecord>("""{"id":"old","title":"old","createdAtMs":1,"settings":{}}""")
        assertNull(old.usage)
    }

    @Test fun foundryLiveAndCustomOpenaiIdsDoNotBorrowAnotherProvidersRates() {
        val azure = FoundryConnection("https://x.openai.azure.com", textModelKind = TextModel.GPT6_LUNA, textDeployment = "bank-luna")
        assertNull(azure.defaultCostRates().voicePerMinute)
        assertEquals(0.10, azure.defaultCostRates().inputPerMillion)
        val openai = azure.copy(provider = ModelProvider.OPENAI, endpoint = "https://api.openai.com", deployment = "gpt-live-1", textDeployment = "gpt-6-luna")
        assertEquals(0.05, openai.defaultCostRates().voicePerMinute)
        assertNull(openai.copy(deployment = "my-custom-live").defaultCostRates().voicePerMinute)
        assertNull(openai.copy(textDeployment = "my-custom-luna").defaultCostRates().inputPerMillion)
        assertNotEquals(azure.costRateKey(), openai.costRateKey())
        assertFalse(CostRates(voicePerMinute = Double.NaN).valid())
        assertFalse(CostRates(inputPerMillion = -1.0).valid())
    }

    @Test fun unfinishedMeetingsRetainKnownCostsButFlagMissingFinalUsage() {
        val unfinished = meeting().copy(endedAtMs = null).update(UsageSample("voice", UsageKind.VOICE, seconds = 60.0))
        assertEquals(0.05, unfinished.total().usd, 1e-12)
        assertTrue(unfinished.total().incomplete)
        assertFalse(unfinished.copy(endedAtMs = unfinished.startedAtMs + 60_000).total().incomplete)
        assertFalse(meeting().copy(endedAtMs = null).total().incomplete)
    }

    @Test fun sumsUnroundedFractionsBeforePresentingUsd() {
        val samples = (1..100).map { UsageSample("$it", UsageKind.TRANSLATION, inputTokens = 1) }
        assertEquals("US$0.0001", usd(meeting().copy(rates = CostRates(inputPerMillion = 1.0), samples = samples).total().usd))
    }
}
