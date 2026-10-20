package com.focusassistant.app.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 负债也记正数，汇总时计为负值。与浏览器原型 ASSET_KINDS 同序同名。 */
enum class AssetKind(val label: String, val liability: Boolean = false) {
    CASH("现金"), BANK("储蓄卡"), FUND("理财 / 基金"), STOCK("股票"), PROPERTY("房产车辆"), OTHER("其他资产"), DEBT("负债", liability = true)
}

data class AssetAccount(val id: String, val name: String, val kind: AssetKind)
/** 某一天某个账户的余额快照。金额以分为单位，避免浮点误差；date 为本地日期 YYYY-MM-DD。 */
data class AssetSnapshot(val id: String, val accountId: String, val date: String, val amountCents: Long, val note: String = "")
/** 心愿的进度只看所关联资产账户的最新余额。deadline 为空表示不设截止日期。 */
data class Wish(val id: String, val name: String, val targetCents: Long, val deadline: String?, val accountId: String, val archived: Boolean = false)
data class AssetBook(
    val accounts: List<AssetAccount> = emptyList(),
    val snapshots: List<AssetSnapshot> = emptyList(),
    val wishes: List<Wish> = emptyList()
)

data class NetWorth(val assetsCents: Long, val debtsCents: Long, val known: Boolean) {
    val netCents: Long get() = assetsCents - debtsCents
}
/** monthChange 为空表示上月月末前还没有任何快照；monthRatio 另在上月净值为 0 时为空。 */
data class AssetSummary(val assetsCents: Long, val debtsCents: Long, val netCents: Long, val monthChangeCents: Long?, val monthRatio: Double?)
data class NetWorthPoint(val month: YearMonth, val netCents: Long, val known: Boolean, val notes: List<AssetSnapshot>)
data class WishProgress(
    val savedCents: Long, val ratio: Double, val remainingCents: Long, val reached: Boolean,
    val daysLeft: Long?, val overdue: Boolean, val monthlyNeededCents: Long?
)

/** 资产口径：与浏览器原型 assetSummary / netWorthSeries / wishProgress 一致，见 rules/profile.md。 */
object AssetRules {
    const val MAX_NAME = 16
    const val MAX_NOTE = 40
    const val MAX_ACCOUNTS = 20
    const val MAX_WISHES = 3
    const val MAX_SNAPSHOTS = 100_000
    const val TREND_MONTHS = 6
    /** 100 亿元。 */
    const val MAX_AMOUNT_CENTS = 1_000_000_000_000L
    private const val CENTS = 100L
    private const val DAYS_PER_MONTH = 30L
    private val AMOUNT = Regex("""^\d+(\.\d{1,2})?$""")
    private val DATE_KEY = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** 解析用户输入的金额（元），返回分。label 用于报错，如「余额」「目标金额」。 */
    fun parseAmount(text: String, label: String = "余额"): Result<Long> {
        val value = text.trim().replace(",", "")
        if (value.isEmpty() || !Regex("""^\d+(\.\d+)?$""").matches(value)) return Result.failure(IllegalArgumentException("${label}需为 0–100 亿之间的数字。"))
        if (!AMOUNT.matches(value)) return Result.failure(IllegalArgumentException("${label}最多保留两位小数。"))
        val cents = value.toBigDecimal().movePointRight(2).toLong().takeIf { value.substringBefore('.').length <= 12 }
        if (cents == null || cents > MAX_AMOUNT_CENTS) return Result.failure(IllegalArgumentException("${label}需为 0–100 亿之间的数字。"))
        return Result.success(cents)
    }

    fun validateAccount(account: AssetAccount) {
        require(account.id.isNotBlank() && account.id.length <= 128) { "账户标识无效" }
        require(account.name.isNotBlank() && account.name.length <= MAX_NAME) { "账户名称需为 1–$MAX_NAME 个字符。" }
    }

    fun validateSnapshot(snapshot: AssetSnapshot, today: LocalDate? = null) {
        require(snapshot.id.isNotBlank() && snapshot.id.length <= 128 && snapshot.accountId.isNotBlank()) { "余额记录标识无效" }
        require(snapshot.amountCents in 0..MAX_AMOUNT_CENTS) { "余额需为 0–100 亿之间的数字。" }
        val date = parseDate(snapshot.date) ?: throw IllegalArgumentException("请选择有效的日期。")
        require(today == null || date <= today) { "日期不能晚于今天。" }
        require(snapshot.note.length <= MAX_NOTE) { "备注最多 $MAX_NOTE 字。" }
    }

    fun validateWish(wish: Wish) {
        require(wish.id.isNotBlank() && wish.id.length <= 128) { "心愿标识无效" }
        require(wish.name.isNotBlank() && wish.name.length <= MAX_NAME) { "心愿名称需为 1–$MAX_NAME 个字符。" }
        require(wish.targetCents in 1..MAX_AMOUNT_CENTS) { "目标金额需大于 0。" }
        require(wish.deadline == null || parseDate(wish.deadline) != null) { "请选择有效的截止日期。" }
        require(wish.accountId.isNotBlank()) { "请选择用来攒钱的账户。" }
    }

    /** 整本校验：标识唯一、引用存在、心愿只能关联资产账户。备份恢复与仓库写入共用。 */
    fun validateBook(book: AssetBook) {
        require(book.accounts.size <= MAX_ACCOUNTS) { "账户最多 $MAX_ACCOUNTS 个" }
        require(book.snapshots.size <= MAX_SNAPSHOTS) { "余额记录过多" }
        require(book.wishes.count { !it.archived } <= MAX_WISHES) { "进行中的心愿最多 $MAX_WISHES 个" }
        fun unique(ids: List<String>) = require(ids.toSet().size == ids.size) { "资产记录标识重复" }
        unique(book.accounts.map { it.id }); unique(book.snapshots.map { it.id }); unique(book.wishes.map { it.id })
        book.accounts.forEach(::validateAccount)
        val accounts = book.accounts.associateBy { it.id }
        book.snapshots.forEach { validateSnapshot(it); require(it.accountId in accounts) { "余额记录引用的账户不存在" } }
        book.wishes.forEach { wish ->
            validateWish(wish)
            val account = accounts[wish.accountId] ?: throw IllegalArgumentException("心愿关联的账户不存在")
            require(!account.kind.liability) { "心愿不能关联负债账户" }
        }
    }

    fun parseDate(key: String): LocalDate? = if (!DATE_KEY.matches(key)) null else runCatching { LocalDate.parse(key) }.getOrNull()

    /** 该日期及以前最近的一次快照；同一天有多条时取列表里靠后的那条。 */
    fun latest(snapshots: List<AssetSnapshot>, accountId: String, at: LocalDate? = null): AssetSnapshot? {
        val limit = at?.toString()
        var found: AssetSnapshot? = null
        snapshots.forEach { snapshot ->
            if (snapshot.accountId != accountId || (limit != null && snapshot.date > limit)) return@forEach
            if (found == null || snapshot.date >= found!!.date) found = snapshot
        }
        return found
    }

    fun balance(snapshots: List<AssetSnapshot>, accountId: String, at: LocalDate? = null): Long = latest(snapshots, accountId, at)?.amountCents ?: 0L

    fun netWorth(book: AssetBook, at: LocalDate): NetWorth {
        var assets = 0L
        var debts = 0L
        var known = false
        book.accounts.forEach { account ->
            val snapshot = latest(book.snapshots, account.id, at) ?: return@forEach
            known = true
            if (account.kind.liability) debts += snapshot.amountCents else assets += snapshot.amountCents
        }
        return NetWorth(assets, debts, known)
    }

    /** 本月变化对比上月月末净值。 */
    fun summary(book: AssetBook, today: LocalDate): AssetSummary {
        val current = netWorth(book, today)
        val previous = netWorth(book, YearMonth.from(today).minusMonths(1).atEndOfMonth())
        val change = if (previous.known) current.netCents - previous.netCents else null
        val ratio = if (change != null && previous.netCents != 0L) change.toDouble() / kotlin.math.abs(previous.netCents) else null
        return AssetSummary(current.assetsCents, current.debtsCents, current.netCents, change, ratio)
    }

    /** 近 months 个月：过去各月取月末，本月取今天；各账户沿用该时点前最近一次快照，附当月带备注的快照。 */
    fun series(book: AssetBook, today: LocalDate, months: Int = TREND_MONTHS): List<NetWorthPoint> {
        val current = YearMonth.from(today)
        return (months - 1 downTo 0).map { monthsAgo ->
            val month = current.minusMonths(monthsAgo.toLong())
            val end = if (monthsAgo == 0) today else month.atEndOfMonth()
            val point = netWorth(book, end)
            val prefix = month.toString()
            val notes = book.snapshots.filter { it.note.isNotBlank() && it.date.startsWith(prefix) && it.date <= today.toString() }.sortedBy { it.date }
            NetWorthPoint(month, point.netCents, point.known, notes)
        }
    }

    /** 每月还需 = 剩余 ÷ 剩余月数（不足 30 天按 1 个月），向上取整到元；过期或已达成时不计算。 */
    fun wishProgress(wish: Wish, snapshots: List<AssetSnapshot>, today: LocalDate): WishProgress {
        val saved = balance(snapshots, wish.accountId, today)
        val remaining = (wish.targetCents - saved).coerceAtLeast(0)
        val reached = remaining == 0L
        val daysLeft = wish.deadline?.let(::parseDate)?.let { ChronoUnit.DAYS.between(today, it) }
        val monthsLeft = daysLeft?.takeIf { it >= 0 }?.let { maxOf(1L, (it + DAYS_PER_MONTH - 1) / DAYS_PER_MONTH) }
        val monthly = if (reached || monthsLeft == null) null else (remaining + monthsLeft * CENTS - 1) / (monthsLeft * CENTS) * CENTS
        return WishProgress(saved, minOf(1.0, saved.toDouble() / wish.targetCents), remaining, reached, daysLeft,
            !reached && daysLeft != null && daysLeft < 0, monthly)
    }

    /** 与浏览器 Intl.NumberFormat("zh-CN") 一致：千分位、最多两位小数；负数用「−」。 */
    fun money(cents: Long, sign: Boolean = false): String {
        val format = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.ROOT))
        val prefix = when { cents < 0 -> "−"; sign && cents > 0 -> "+"; else -> "" }
        return "$prefix¥${format.format(kotlin.math.abs(cents) / CENTS.toDouble())}"
    }

    /** 输入框回填用：不带千分位与货币符号。 */
    fun amountText(cents: Long): String = DecimalFormat("0.##", DecimalFormatSymbols(Locale.ROOT)).format(cents / CENTS.toDouble())
}
