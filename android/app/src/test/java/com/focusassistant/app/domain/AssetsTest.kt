package com.focusassistant.app.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetsTest {
    private val today = LocalDate.of(2026, 6, 15)
    private val accounts = listOf(
        AssetAccount("bank", "储蓄卡", AssetKind.BANK),
        AssetAccount("card", "信用卡", AssetKind.DEBT)
    )

    private fun snap(id: String, account: String, date: String, yuan: Long, note: String = "") =
        AssetSnapshot(id, account, date, yuan * 100, note)

    @Test
    fun liabilitiesCountNegativeAndLatestSnapshotCarriesForward() {
        val book = AssetBook(accounts, listOf(
            snap("1", "bank", "2026-04-10", 1000),
            snap("2", "card", "2026-05-20", 300),
            snap("3", "bank", "2026-06-01", 1500, "发工资")
        ))
        val summary = AssetRules.summary(book, today)
        assertEquals(150_000L, summary.assetsCents)
        assertEquals(30_000L, summary.debtsCents)
        assertEquals(120_000L, summary.netCents)
        // 上月月末：储蓄卡沿用 4 月的 1000，信用卡 300。
        assertEquals(50_000L, summary.monthChangeCents)
        assertEquals(50_000.0 / 70_000.0, summary.monthRatio!!, 1e-9)
    }

    @Test
    fun sameDayTieUsesLaterEntryAndFutureIsIgnored() {
        val snapshots = listOf(
            snap("1", "bank", "2026-06-10", 100),
            snap("2", "bank", "2026-06-10", 200),
            snap("3", "bank", "2026-06-20", 900)
        )
        assertEquals("2", AssetRules.latest(snapshots, "bank", today)?.id)
        assertEquals(0L, AssetRules.balance(snapshots, "bank", LocalDate.of(2026, 6, 9)))
    }

    @Test
    fun monthChangeIsNullWithoutDataBeforeLastMonthEnd() {
        val summary = AssetRules.summary(AssetBook(accounts, listOf(snap("1", "bank", "2026-06-02", 500))), today)
        assertNull(summary.monthChangeCents)
        assertNull(summary.monthRatio)
        val zeroBase = AssetRules.summary(AssetBook(accounts, listOf(
            snap("1", "bank", "2026-05-02", 0), snap("2", "bank", "2026-06-02", 500)
        )), today)
        assertEquals(50_000L, zeroBase.monthChangeCents)
        assertNull(zeroBase.monthRatio)
    }

    @Test
    fun seriesUsesMonthEndsAndAttachesNotes() {
        val book = AssetBook(accounts, listOf(
            snap("1", "bank", "2026-03-31", 800),
            snap("2", "bank", "2026-05-05", 1200, "买基金"),
            snap("3", "bank", "2026-06-30", 9999, "未来")
        ))
        val series = AssetRules.series(book, today)
        assertEquals(6, series.size)
        assertEquals(YearMonth.of(2026, 1), series.first().month)
        assertFalse(series[0].known)
        assertEquals(80_000L, series[2].netCents)
        assertEquals(80_000L, series[3].netCents)
        assertEquals(listOf("2"), series[4].notes.map { it.id })
        assertEquals(120_000L, series[5].netCents)
        assertTrue(series[5].notes.isEmpty())
    }

    @Test
    fun wishProgressHandlesMonthlyNeedReachedAndOverdue() {
        val snapshots = listOf(snap("1", "bank", "2026-06-01", 4000))
        val wish = Wish("w", "旅行", 1_000_000, "2026-09-12", "bank")
        val progress = AssetRules.wishProgress(wish, snapshots, today)
        assertEquals(400_000L, progress.savedCents)
        assertEquals(0.4, progress.ratio, 1e-9)
        assertEquals(89L, progress.daysLeft)
        // 剩余 6000 元，89 天按 3 个月计。
        assertEquals(200_000L, progress.monthlyNeededCents)

        val odd = AssetRules.wishProgress(wish.copy(targetCents = 1_000_001), snapshots, today)
        assertEquals(200_100L, odd.monthlyNeededCents)

        val overdue = AssetRules.wishProgress(wish.copy(deadline = "2026-06-01"), snapshots, today)
        assertTrue(overdue.overdue)
        assertNull(overdue.monthlyNeededCents)

        val reached = AssetRules.wishProgress(wish.copy(targetCents = 300_000), snapshots, today)
        assertTrue(reached.reached)
        assertEquals(1.0, reached.ratio, 1e-9)
        assertNull(reached.monthlyNeededCents)

        assertNull(AssetRules.wishProgress(wish.copy(deadline = null), snapshots, today).monthlyNeededCents)
        assertEquals(600_000L, AssetRules.wishProgress(wish.copy(deadline = "2026-06-15"), snapshots, today).monthlyNeededCents)
    }

    @Test
    fun amountParsingAndValidationBoundaries() {
        assertEquals(123_456L, AssetRules.parseAmount("1,234.56").getOrThrow())
        assertEquals(0L, AssetRules.parseAmount("0").getOrThrow())
        assertEquals(AssetRules.MAX_AMOUNT_CENTS, AssetRules.parseAmount("10000000000").getOrThrow())
        assertTrue(AssetRules.parseAmount("10000000000.01").isFailure)
        assertTrue(AssetRules.parseAmount("1.234").isFailure)
        assertTrue(AssetRules.parseAmount("-5").isFailure)
        assertTrue(AssetRules.parseAmount("").isFailure)
        assertTrue(AssetRules.parseAmount("99999999999999999999").isFailure)

        assertTrue(runCatching { AssetRules.validateSnapshot(snap("1", "bank", "2026-06-16", 1), today) }.isFailure)
        assertTrue(runCatching { AssetRules.validateSnapshot(snap("1", "bank", "2026-02-30", 1), today) }.isFailure)
        assertTrue(runCatching { AssetRules.validateSnapshot(snap("1", "bank", "2026-06-15", 1, "备".repeat(41)), today) }.isFailure)
        AssetRules.validateSnapshot(snap("1", "bank", "2026-06-15", 1, "备".repeat(40)), today)
        assertTrue(runCatching { AssetRules.validateAccount(AssetAccount("a", "名".repeat(17), AssetKind.CASH)) }.isFailure)
    }

    @Test
    fun bookValidationRejectsDebtWishesAndDanglingReferences() {
        val base = AssetBook(accounts, listOf(snap("1", "bank", "2026-06-01", 1)), listOf(Wish("w", "旅行", 100, null, "bank")))
        AssetRules.validateBook(base)
        assertTrue(runCatching { AssetRules.validateBook(base.copy(wishes = listOf(Wish("w", "还款", 100, null, "card")))) }.isFailure)
        assertTrue(runCatching { AssetRules.validateBook(base.copy(snapshots = listOf(snap("1", "gone", "2026-06-01", 1)))) }.isFailure)
        val four = (1..4).map { Wish("w$it", "心愿$it", 100, null, "bank") }
        assertTrue(runCatching { AssetRules.validateBook(base.copy(wishes = four)) }.isFailure)
        AssetRules.validateBook(base.copy(wishes = four.mapIndexed { index, wish -> wish.copy(archived = index == 0) }))
    }

    @Test
    fun moneyMatchesBrowserFormatting() {
        assertEquals("¥1,234.5", AssetRules.money(123_450))
        assertEquals("−¥300", AssetRules.money(-30_000))
        assertEquals("+¥0.01", AssetRules.money(1, sign = true))
        assertEquals("1234.5", AssetRules.amountText(123_450))
    }
}
