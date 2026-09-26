import csv
import io
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from tempfile import mkdtemp

from playwright.sync_api import expect, sync_playwright

BASE_URL = "http://127.0.0.1:5173"
SCREENSHOT_DIRECTORY = Path(mkdtemp(prefix="focus-prototype-"))
DESKTOP_VIEWPORT = {"width": 1440, "height": 1100}
MOBILE_WIDTHS = (320, 375, 390, 480)
MOBILE_HEIGHT = 844
PAGES = ("focus", "tasks", "usage", "profile")
ONE_MINUTE_MS = 60_000
HOURS_PER_DAY = 24
STAT_PERIODS = (("day", 24), ("week", 7), ("month", 30), ("year", 12))
CSV_HEADERS = ["日期", "任务", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"]
PROJECT_TITLES = ("Spring Boot 实战", "个人 APP 开发", "算法与数据结构")
ALLTIME_IDS = ("alltime-duration", "alltime-calendar-average", "alltime-active-average")
MIN_TOUCH_TARGET = 44


def assert_hour_distribution(page):
    bars = page.locator("#statistics-hours .hour-bar")
    expect(bars).to_have_count(HOURS_PER_DAY)
    expect(page.locator("#statistics-hours")).to_be_visible()
    for bar in bars.all():
        expect(bar).to_be_visible()
    minutes = bars.evaluate_all("bars => bars.map(bar => Number(bar.dataset.minutes))")
    assert all(value >= 0 for value in minutes), minutes
    assert abs(sum(minutes) - float(page.locator("#statistics-minutes").inner_text())) < 0.02, minutes
    return minutes


def assert_statistics_period(page, period, trend_count):
    page.locator(f'[data-stat-period="{period}"]').click()
    expect(page.locator("#statistics-trend .trend-bar")).to_have_count(trend_count)
    if period == "day":
        expect(page.locator("#statistics-trend-section")).not_to_be_visible()
    else:
        expect(page.locator("#statistics-trend-section")).to_be_visible()
    return assert_hour_distribution(page)


def download_statistics(page, file_format):
    page.locator("#export-statistics").click()
    page.locator('select[name="format"]').select_option("JSON（明细与汇总）" if file_format == "json" else "CSV（Excel 可打开）")
    with page.expect_download() as download_event:
        page.get_by_role("button", name="下载统计文件", exact=True).click()
    download = download_event.value
    assert download.suggested_filename.endswith(f".{file_format}")
    data = Path(download.path()).read_bytes()
    if file_format == "json":
        payload = json.loads(data.decode("utf-8"))
        if "hourDistribution" in payload:
            assert len(payload["hourDistribution"]) == HOURS_PER_DAY
            assert abs(sum(bin["minutes"] for bin in payload["hourDistribution"]) - payload["summary"]["minutes"]) < 0.02
        return payload
    assert data.startswith(b"\xef\xbb\xbf")
    assert data.splitlines(keepends=True)[0].endswith(b"\r\n")
    rows = list(csv.reader(io.StringIO(data.decode("utf-8-sig"))))
    assert rows[0] == CSV_HEADERS
    assert all(len(row) == len(CSV_HEADERS) for row in rows)
    return rows


def fresh_timer_page(browser, errors, clock_hour=8):
    page = browser.new_page(viewport=DESKTOP_VIEWPORT, reduced_motion="reduce", timezone_id="Asia/Shanghai")
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.clock.install(time=datetime(2026, 9, 26, clock_hour, 59, 39, tzinfo=timezone.utc))
    page.clock.pause_at(datetime(2026, 9, 26, clock_hour, 59, 40, tzinfo=timezone.utc))
    page.goto(BASE_URL)
    return page


def home(page):
    page.locator('.bottom-nav [data-page="focus"]').click()
    expect(page.locator("#page-focus")).to_be_visible()


def choose_focus_item(page, title):
    home(page)
    page.get_by_role("button", name=f"进入项目：{title}", exact=True).click()


def expand_project(page, title):
    button = page.get_by_role("button", name=f"展开项目详情：{title}", exact=True)
    if button.get_attribute("aria-expanded") != "true":
        button.click()
    return page.locator(".project-card").filter(has=page.get_by_role("button", name=f"进入项目：{title}", exact=True))


def skip_progress(page, return_to_timer=True, method="button"):
    expect(page.locator(".progress-form")).to_be_visible()
    if method == "escape":
        page.keyboard.press("Escape")
    elif method == "close":
        page.locator("#close-modal").click()
    else:
        page.get_by_role("button", name="稍后再写", exact=True).click()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator("#page-focus")).to_be_visible()
    if return_to_timer:
        choose_focus_item(page, page.locator("#focus-item-name").inner_text())


def create_project(page, title, mode="倒计时", minutes=1, category="个人成长"):
    home(page)
    page.locator("#add-focus-item").click()
    page.locator('input[name="title"]').fill(title)
    page.locator('select[name="category"]').select_option(category)
    page.locator('select[name="timerMode"]').select_option(mode)
    page.locator('input[name="durationMinutes"]').fill(str(minutes))
    page.get_by_role("button", name="添加项目", exact=True).click()
    expect(page.locator("#page-focus")).to_be_visible()
    expect(page.locator("#modal")).not_to_be_visible()


def one_minute_configuration(page):
    page.locator("#timer-settings").click()
    page.locator('input[name="durationMinutes"]').fill("1")
    page.get_by_role("button", name="保存专注项", exact=True).click()


def open_statistics(page):
    home(page)
    page.locator("#open-statistics").click()
    expect(page.locator("#page-statistics")).to_be_visible()


def check_independent_countup(browser, errors):
    page = fresh_timer_page(browser, errors)
    title = "独立专注" * 20
    page.set_viewport_size({"width": MOBILE_WIDTHS[0], "height": MOBILE_HEIGHT})
    page.locator("#add-focus-item").click()
    page.locator('input[name="title"]').fill("   ")
    page.get_by_role("button", name="添加项目", exact=True).click()
    expect(page.locator("#modal [role='alert']")).to_contain_text("专注项名称")
    page.locator('input[name="title"]').fill(title)
    page.locator('select[name="category"]').select_option("工作")
    page.locator('select[name="timerMode"]').select_option("正计时")
    page.locator('input[name="durationMinutes"]').fill("1")
    expect(page.locator('input[name="title"]')).to_have_attribute("maxlength", "80")
    assert page.locator("#modal").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-focus-item-editor.png"))
    page.get_by_role("button", name="添加项目", exact=True).click()
    expect(page.locator("#all-count")).to_have_text("4")
    expect(page.locator("#page-focus")).to_be_visible()
    choose_focus_item(page, title)
    expect(page.locator("#focus-item-name")).to_have_text(title)
    expect(page.locator("#timer-time")).to_have_text("00:00")
    page.locator("#select-focus-item").click()
    expect(page.get_by_role("button", name=f"进入项目：{title}", exact=True)).to_be_visible()
    expect(page.locator("#modal")).not_to_be_visible()
    card = expand_project(page, title)
    assert card.evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-project-expanded-long-title.png"))
    choose_focus_item(page, title)
    assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.set_viewport_size(DESKTOP_VIEWPORT)
    page.locator("#timer-toggle").click()
    expect(page.locator("#timer-finish")).not_to_be_visible()
    page.clock.fast_forward(20_000)
    expect(page.locator("#timer-time")).to_have_text("00:20")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#timer-time")).to_have_text("00:20")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(40_000)
    expect(page.locator("#timer-time")).to_have_text("01:00")
    expect(page.locator("#timer-phase")).to_contain_text("目标已达成")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.clock.fast_forward(30_000)
    expect(page.locator("#timer-time")).to_have_text("01:30")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.locator("#timer-finish").click()
    expect(page.locator("#focus-sessions")).to_have_text("4")
    skip_progress(page)
    expect(page.locator("#timer-finish")).not_to_be_visible()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("4")
    expect(page.locator("#timer-time")).to_have_text("01:30")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(500)
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#timer-finish")).to_be_disabled()
    page.clock.fast_forward(500)
    expect(page.locator("#timer-time")).to_have_text("00:01")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#timer-time")).to_have_text("00:01")
    page.locator("#timer-finish").click()
    expect(page.locator("#focus-sessions")).to_have_text("5")
    skip_progress(page)
    page.locator("#timer-settings").click()
    page.locator('input[name="title"]').fill("修改后的专注项")
    page.locator('select[name="category"]').select_option("生活")
    page.locator('select[name="timerMode"]').select_option("倒计时")
    page.locator('input[name="durationMinutes"]').fill("7")
    page.get_by_role("button", name="保存专注项", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("07:00")
    page.locator("#select-focus-item").click()
    expand_project(page, "修改后的专注项")
    page.get_by_role("button", name="编辑项目：修改后的专注项", exact=True).click()
    page.get_by_role("button", name="删除专注项", exact=True).click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#focus-item-name")).to_have_text("修改后的专注项")
    choose_focus_item(page, "修改后的专注项")
    page.locator("#timer-settings").click()
    page.get_by_role("button", name="删除专注项", exact=True).click()
    page.get_by_role("button", name="确认删除", exact=True).click()
    expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[0])
    expect(page.locator("#page-focus")).to_be_visible()
    expect(page.locator("#all-count")).to_have_text("4")
    expect(page.get_by_role("button", name="进入项目：修改后的专注项", exact=True)).to_have_count(0)
    page.locator("#open-statistics").click()
    page.locator("#statistics-source").select_option("session")
    expect(page.locator("#statistics-count")).to_have_text("2")
    expect(page.locator("#statistics-count + span")).to_have_text("完成专注")
    expect(page.locator("#statistics-records .record-heading strong")).to_have_text([title, title])
    expect(page.locator("#statistics-records")).to_contain_text(title)
    expected_snapshot = None
    for period, trend_count in STAT_PERIODS:
        bins = assert_statistics_period(page, period, trend_count)
        assert abs(bins[16] - 20 / 60) < 0.000001
        assert abs(bins[17] - 71 / 60) < 0.000001
        payload = download_statistics(page, "json")
        assert payload["period"] == period
        assert payload["includesSamples"] is False
        assert payload["summary"]["count"] == 2
        assert abs(payload["summary"]["minutes"] - 91 / 60) < 0.000001
        records = payload["records"]
        assert sorted(record["durationSeconds"] for record in records) == [1, 90]
        assert all(record["focusItemTitle"] == title and record["timerMode"] == "countup" and record["targetMinutes"] == 1 and record["category"] == "工作" for record in records)
        assert all(record["source"] == "session" and record["taskId"] is None and record["taskTitle"] == title for record in records)
        record = next(record for record in records if record["durationSeconds"] == 90)
        assert record["durationMinutes"] == 1.5
        assert len(record["segments"]) == 2
        segments = [(datetime.fromisoformat(segment["startedAt"]), datetime.fromisoformat(segment["endedAt"])) for segment in record["segments"]]
        assert [(end - start).total_seconds() for start, end in segments] == [20, 70]
        assert (segments[1][0] - segments[0][1]).total_seconds() == 60
        if expected_snapshot is None:
            expected_snapshot = records
        assert records == expected_snapshot
        if "hourDistribution" in payload:
            assert all(abs(bin["minutes"] - minutes) < 0.000001 for bin, minutes in zip(payload["hourDistribution"], bins))
        rows = download_statistics(page, "csv")
        assert len(rows) == 3
        assert {row[7] for row in rows[1:]} == {"正计时"}
        assert {row[8] for row in rows[1:]} == {title}
        assert {row[9] for row in rows[1:]} == {"1"}
        assert sorted(int(row[10]) for row in rows[1:]) == [1, 90]
        assert abs(sum(float(row[5]) for row in rows[1:]) - 91 / 60) < 0.000001
    page.close()
    print("PASS: independent items, 80-character mobile dialogs, countup targets, early/paused finish, immutable history and four-period CSV/JSON.", flush=True)


def check_focus_configuration(browser, errors):
    page = fresh_timer_page(browser, errors)
    choose_focus_item(page, PROJECT_TITLES[1])
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#focus-item-description")).to_contain_text("目标 50 分钟")
    expect(page.locator('[data-timing-mode="countup"]')).to_have_attribute("aria-pressed", "true")
    choose_focus_item(page, PROJECT_TITLES[2])
    expect(page.locator("#timer-time")).to_have_text("15:00")
    expect(page.locator('[data-timing-mode="countdown"]')).to_have_attribute("aria-pressed", "true")
    choose_focus_item(page, PROJECT_TITLES[0])
    expect(page.locator("#timer-time")).to_have_text("25:00")
    page.locator('.bottom-nav [data-page="profile"]').click()
    page.locator("#preferences-button").click()
    expect(page.locator("#modal input")).to_have_count(2)
    expect(page.locator('#modal input[name="durationMinutes"]')).to_have_count(0)
    page.locator('input[name="short"]').fill("1")
    page.locator('input[name="long"]').fill("2")
    page.get_by_role("button", name="保存偏好", exact=True).click()
    choose_focus_item(page, PROJECT_TITLES[0])
    for mode, display in (("short", "01:00"), ("long", "02:00"), ("focus", "25:00")):
        page.locator(f'[data-mode="{mode}"]').click()
        expect(page.locator("#timer-time")).to_have_text(display)
    title = '=SUM("1",2)'
    home(page)
    page.locator("#add-focus-item").click()
    page.locator('input[name="title"]').fill(title)
    page.locator('select[name="category"]').select_option("生活")
    page.locator('select[name="timerMode"]').select_option("倒计时")
    page.locator('input[name="durationMinutes"]').fill("2")
    page.get_by_role("button", name="添加项目", exact=True).click()
    expect(page.locator("#all-count")).to_have_text("4")
    choose_focus_item(page, title)
    expect(page.locator("#timer-time")).to_have_text("02:00")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(20_000)
    page.locator("#timer-toggle").click()
    page.locator("#timer-reset").click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("01:40")
    choose_focus_item(page, PROJECT_TITLES[2])
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#focus-item-name")).to_have_text(title)
    expect(page.locator("#timer-time")).to_have_text("01:40")
    page.locator("#resume-session").click()
    page.locator("#timer-reset").click()
    page.get_by_role("button", name="放弃并继续", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("02:00")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(10_000)
    page.locator("#timer-toggle").click()
    choose_focus_item(page, PROJECT_TITLES[2])
    page.get_by_role("button", name="放弃并继续", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("15:00")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    choose_focus_item(page, title)
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(10_000)
    page.locator("#timer-toggle").click()
    page.locator('[data-timing-mode="countup"]').click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("01:50")
    expect(page.locator('[data-timing-mode="countdown"]')).to_have_attribute("aria-pressed", "true")
    page.locator('[data-timing-mode="countup"]').click()
    page.get_by_role("button", name="放弃并继续", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#focus-item-description")).to_contain_text("正计时 · 目标 2 分钟")
    choose_focus_item(page, PROJECT_TITLES[1])
    expect(page.locator("#focus-item-description")).to_contain_text("目标 50 分钟")
    choose_focus_item(page, title)
    expect(page.locator('[data-timing-mode="countup"]')).to_have_attribute("aria-pressed", "true")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(5_000)
    page.locator("#timer-toggle").click()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    task = page.locator(".task-card").filter(has_text="梳理个人 APP 的想法")
    task.locator(".task-start").click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("00:05")
    expect(page.locator("#linked-task-name")).to_have_text("不关联待办")
    task.locator(".task-start").click()
    page.get_by_role("button", name="放弃并继续", exact=True).click()
    expect(page.locator("#page-timer")).to_be_visible()
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#linked-task-name")).to_have_text("梳理个人 APP 的想法")
    expect(page.locator("#focus-item-name")).to_have_text(title)
    expect(page.locator("#focus-item-description")).to_contain_text("目标 2 分钟")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.locator('[data-timing-mode="countdown"]').click()
    expect(page.locator("#timer-time")).to_have_text("02:00")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(2 * ONE_MINUTE_MS)
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#focus-sessions")).to_have_text("4")
    skip_progress(page)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    task.locator(".task-info").click()
    page.locator('input[name="title"]').fill("修改后的关联待办")
    page.get_by_role("button", name="保存修改", exact=True).click()
    page.locator('.bottom-nav [data-page="focus"]').click()
    page.locator("#open-statistics").click()
    page.locator("#statistics-source").select_option("session")
    expect(page.locator("#statistics-count")).to_have_text("1")
    expect(page.locator("#statistics-minutes")).to_have_text("2")
    expect(page.locator("#statistics-records")).to_contain_text("关联待办：梳理个人 APP 的想法")
    assert_hour_distribution(page)
    payload = download_statistics(page, "json")
    record = payload["records"][0]
    assert len(payload["records"]) == 1
    assert record["focusItemTitle"] == title
    assert record["timerMode"] == "countdown" and record["targetMinutes"] == 2
    assert record["durationSeconds"] == 120 and record["durationMinutes"] == 2
    assert record["taskTitle"] == "梳理个人 APP 的想法" and record["category"] == "生活"
    rows = download_statistics(page, "csv")
    assert rows[1][8] == "'" + title
    assert rows[1][7] == "倒计时" and rows[1][9:11] == ["2", "120"]
    page.close()
    print("PASS: default/custom focus configuration, rest-only preferences, reset/item/mode/task cancellation, task snapshots and CSV escaping.", flush=True)


def check_cancelled_settings_drafts(browser, errors):
    page = fresh_timer_page(browser, errors)
    choose_focus_item(page, PROJECT_TITLES[0])
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(2_000)
    page.locator("#timer-settings").click()
    page.locator('input[name="title"]').fill("尚未保存的项目名称")
    page.locator('input[name="durationMinutes"]').fill("17")
    page.get_by_role("button", name="保存专注项", exact=True).click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator('input[name="title"]')).to_have_value("尚未保存的项目名称")
    expect(page.locator('input[name="durationMinutes"]')).to_have_value("17")
    page.keyboard.press("Escape")
    expect(page.locator("#timer-time")).to_have_text("24:58")
    expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[0])
    page.locator('.bottom-nav [data-page="profile"]').click()
    page.locator("#preferences-button").click()
    page.locator('input[name="short"]').fill("7")
    page.get_by_role("button", name="保存偏好", exact=True).click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator('input[name="short"]')).to_have_value("7")
    page.keyboard.press("Escape")
    expect(page.locator("#timer-time")).to_have_text("24:58")
    page.close()
    print("PASS: cancelled reset confirmations preserve project and rest form inputs.", flush=True)


def check_project_navigation(browser, errors):
    page = fresh_timer_page(browser, errors)
    expect(page.locator("#page-focus")).to_be_visible()
    expect(page.locator("#page-timer")).not_to_be_visible()
    expect(page.locator("#project-list .project-title")).to_have_text(list(PROJECT_TITLES))
    assert page.locator(".project-card").evaluate_all("cards => cards.map(card => card.dataset.projectId)") == ["focus-reading", "focus-coding", "focus-exercise"]
    initial_url = page.url
    expand = page.get_by_role("button", name=f"展开项目详情：{PROJECT_TITLES[0]}", exact=True)
    expand.focus()
    page.keyboard.press("Enter")
    expect(expand).to_have_attribute("aria-expanded", "true")
    expect(expand).to_be_focused()
    expect(page.locator('[data-project-id="focus-reading"] .project-details')).to_be_visible()
    assert page.url == initial_url
    page.clock.fast_forward(2_000)
    expect(page.locator("#timer-time")).to_have_text("25:00")
    expect(page.locator("#resume-session")).not_to_be_visible()
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "desktop-home-expanded.png"))
    page.keyboard.press("Space")
    expect(expand).to_have_attribute("aria-expanded", "false")
    choose_focus_item(page, PROJECT_TITLES[0])
    expect(page.locator("#page-timer")).to_be_visible()
    expect(page.locator("#linked-task-name")).to_have_text("不关联待办")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(10_000)
    page.locator("#timer-back").click()
    expect(page.locator("#page-focus")).to_be_visible()
    page.clock.fast_forward(5_000)
    page.locator("#resume-session").click()
    expect(page.locator("#timer-time")).to_have_text("24:45")
    page.locator("#timer-toggle").click()
    page.locator("#timer-back").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    page.locator("#resume-session").click()
    expect(page.locator("#timer-time")).to_have_text("24:45")
    page.locator("#timer-toggle").click()
    create_project(page, "计时期间新建", mode="正计时", minutes=3)
    expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[0])
    page.clock.fast_forward(5_000)
    page.locator("#resume-session").click()
    expect(page.locator("#timer-time")).to_have_text("24:40")
    page.locator("#timer-toggle").click()
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "desktop-timer.png"))
    page.locator("#select-focus-item").click()
    expect(page.locator("#page-focus")).to_be_visible()
    expect(page.locator("#modal")).not_to_be_visible()
    choose_focus_item(page, "计时期间新建")
    page.get_by_role("button", name="取消", exact=True).click()
    page.locator("#resume-session").click()
    expect(page.locator("#timer-time")).to_have_text("24:40")
    page.close()
    print("PASS: project home, keyboard expansion without navigation/start, back/resume, new project preserves running session.", flush=True)


def save_progress(page, note, percent="", automatic=False):
    page.locator('textarea[name="note"]').fill(note)
    page.locator('input[name="percent"]').fill(str(percent))
    label = "保存进度并返回项目" if automatic else "保存进度"
    page.get_by_role("button", name=label, exact=True).click()
    expect(page.locator("#modal")).not_to_be_visible()


def check_progress_workflow(browser, errors):
    page = fresh_timer_page(browser, errors)
    title = "进度测试" * 20
    create_project(page, title)
    choose_focus_item(page, title)
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("4")
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-1")
    page.locator('textarea[name="note"]').fill("跳过仍保留的草稿")
    skip_progress(page, return_to_timer=False)
    card = expand_project(page, title)
    expect(card.locator('.project-pending-button[data-record-id="session-1"]')).to_be_visible()
    card.locator('.project-pending-button[data-record-id="session-1"]').click()
    expect(page.locator('textarea[name="note"]')).to_have_value("跳过仍保留的草稿")
    page.keyboard.press("Escape")
    expect(page.locator("#modal")).not_to_be_visible()
    choose_focus_item(page, title)
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("5")
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-2")
    note = page.locator('textarea[name="note"]')
    percent = page.locator('input[name="percent"]')
    expect(note).to_have_attribute("maxlength", "2000")
    expect(note).to_have_attribute("required", "")
    assert not percent.evaluate("el => el.required")
    for invalid_note, invalid_percent in (("   ", "0"), ("合法说明", "-1"), ("合法说明", "101"), ("合法说明", "1.5")):
        note.fill(invalid_note)
        percent.fill(invalid_percent)
        page.get_by_role("button", name="保存进度并返回项目", exact=True).click()
        expect(page.locator(".progress-form")).to_be_visible()
        assert page.locator('#modal [role="alert"]:visible').count() or not page.locator(".progress-form").evaluate("form => form.checkValidity()")
    note.evaluate("el => { el.value = '长'.repeat(2001); el.dispatchEvent(new Event('input', {bubbles:true})); }")
    percent.fill("")
    page.locator(".progress-form").dispatch_event("submit")
    expect(page.locator('#modal [role="alert"]')).to_be_visible()
    long_note = "进度与下一步\n" + "长" * 1993
    assert len(long_note) == 2000
    page.set_viewport_size({"width": 320, "height": MOBILE_HEIGHT})
    note.fill(long_note)
    percent.fill("0")
    assert page.locator("#modal").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-progress-2000.png"))
    page.get_by_role("button", name="保存进度并返回项目", exact=True).click()
    expect(page.locator("#page-focus")).to_be_visible()
    card = expand_project(page, title)
    expect(card.locator(".project-progress-note")).to_have_text(long_note)
    expect(card.locator("progress")).to_have_attribute("value", "0")
    expect(card.get_by_role("button", name="进度历史（1）", exact=True)).to_be_visible()
    assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth")
    card.locator('.project-pending-button[data-record-id="session-1"]').click()
    save_progress(page, "较早场次的补写", 99)
    expect(card.locator(".project-progress-note")).to_have_text(long_note)
    card.get_by_role("button", name="编辑本次进度", exact=True).click()
    unsafe_note = '=SUM("1",2)\n<img src=x onerror="window.progressInjected=true">'
    save_progress(page, unsafe_note)
    expect(card.locator(".project-progress-note")).to_have_text(unsafe_note)
    expect(card.locator("progress")).to_have_count(0)
    assert page.evaluate("window.progressInjected === undefined")
    expect(card.locator("img")).to_have_count(0)
    card.get_by_role("button", name="进度历史（3）", exact=True).click()
    expect(page.locator(".project-history-entry")).to_have_count(3)
    expect(page.locator(".project-history-entry").first).to_contain_text(unsafe_note)
    expect(page.locator(".project-history-entry").nth(1)).to_contain_text(long_note)
    page.locator("#close-modal").click()
    page.set_viewport_size(DESKTOP_VIEWPORT)
    open_statistics(page)
    page.locator("#statistics-source").select_option("session")
    page.locator('.record-progress-button[data-record-id="session-2"]').click()
    expect(note).to_have_value(unsafe_note)
    page.keyboard.press("Escape")
    expect(page.locator("#page-statistics")).to_be_visible()
    payload = download_statistics(page, "json")
    assert payload["summary"]["count"] == 2
    assert len(payload["progressHistory"]) == 3
    revisions = [entry for entry in payload["progressHistory"] if entry["recordId"] == "session-2"]
    assert len(revisions) == 2 and revisions[0]["percent"] == 0 and revisions[1]["percent"] is None
    latest = next(record for record in payload["records"] if record["id"] == "session-2")
    assert latest["progress"]["note"] == unsafe_note and latest["progress"]["percent"] is None
    assert all(entry["recordId"] in {record["id"] for record in payload["records"]} for entry in payload["progressHistory"])
    rows = download_statistics(page, "csv")
    assert any(row[11] == "'" + unsafe_note and row[12] == "" and row[13] for row in rows[1:]), rows
    home(page)
    expand_project(page, title)
    page.get_by_role("button", name=f"编辑项目：{title}", exact=True).click()
    page.get_by_role("button", name="删除专注项", exact=True).click()
    page.get_by_role("button", name="确认删除", exact=True).click()
    expect(page.locator("#page-focus")).to_be_visible()
    open_statistics(page)
    after_delete = download_statistics(page, "json")
    assert after_delete["records"] == payload["records"]
    assert after_delete["progressHistory"] == payload["progressHistory"]
    page.close()
    print("PASS: countdown saved before progress, skip/draft/pending, 0%/blank validation, 2000 chars at 320px, older backfill, revisions, XSS, CSV/JSON, deletion history.", flush=True)


def check_progress_queue(browser, errors):
    page = fresh_timer_page(browser, errors, clock_hour=0)
    assert page.evaluate("FocusModel.initialProgressEntries(FocusModel.initialFocusRecords()).some(entry => entry.focusItemId === 'focus-reading' && Date.parse(entry.recordEndedAt) > Date.now() + 60000)")
    choose_focus_item(page, PROJECT_TITLES[0])
    one_minute_configuration(page)
    page.locator("#timer-toggle").click()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    page.locator(".task-info").first.click()
    page.locator('input[name="title"]').fill("尚未保存的任务编辑")
    modal_title = page.locator("#modal-title").inner_text()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("4")
    expect(page.locator("#modal-title")).to_have_text(modal_title)
    expect(page.locator('input[name="title"]')).to_have_value("尚未保存的任务编辑")
    expect(page.locator(".progress-form")).to_have_count(0)
    page.locator("#close-modal").click()
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-1")
    page.locator('textarea[name="note"]').fill("关闭按钮保留的草稿")
    skip_progress(page, return_to_timer=False, method="close")
    card = expand_project(page, PROJECT_TITLES[0])
    card.locator('.project-pending-button[data-record-id="session-1"]').click()
    expect(page.locator('textarea[name="note"]')).to_have_value("关闭按钮保留的草稿")
    save_progress(page, "手动进度优先于未来示例", 0)
    expect(card.locator(".project-progress-note")).to_have_text("手动进度优先于未来示例")
    choose_focus_item(page, PROJECT_TITLES[0])
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    skip_progress(page, return_to_timer=False, method="escape")
    expect(page.locator("#focus-sessions")).to_have_text("5")
    card = expand_project(page, PROJECT_TITLES[0])
    expect(card.locator('.project-pending-button[data-record-id="session-2"]')).to_be_visible()
    choose_focus_item(page, PROJECT_TITLES[0])
    page.locator("#timer-toggle").click()
    page.evaluate("Object.defineProperty(document, 'hidden', {configurable:true, get: () => true})")
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("6")
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-2")
    page.evaluate("delete document.hidden; document.dispatchEvent(new Event('visibilitychange'))")
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-3")
    skip_progress(page, return_to_timer=False)
    page.close()
    print("PASS: completion queues behind task editor without replacing typed text; visibility pumps queue; global close/Escape preserve counts and drafts; manual beats future sample progress.", flush=True)


def alltime_snapshot(page):
    snapshot = {name: page.locator(f"#{name}").inner_text() for name in ALLTIME_IDS}
    page.locator("#alltime-help").click()
    expect(page.get_by_role("dialog", name="累计统计说明")).to_be_visible()
    snapshot["details"] = page.locator("#modal-content .modal-copy").inner_text()
    page.get_by_role("button", name="知道了", exact=True).click()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator("#alltime-help")).to_be_focused()
    return snapshot


def check_alltime_statistics(browser, errors):
    page = fresh_timer_page(browser, errors)
    open_statistics(page)
    overview = page.locator(".alltime-overview")
    expect(overview.locator("strong")).to_have_count(3)
    expect(overview.locator("p, small")).to_have_count(0)
    for explanation in ("分母", "统计范围", "次专注", "首次", "空白日", "不受", "刷新"):
        expect(overview).not_to_contain_text(explanation)
    assert overview.evaluate("el => { const css = getComputedStyle(el); return css.backgroundColor === 'rgb(255, 255, 255)' && css.borderTopWidth === '0px' && css.paddingTop === '0px'; }")
    help_button = page.get_by_role("button", name="累计统计说明", exact=True)
    expect(help_button).to_have_text("?")
    expect(help_button).to_have_attribute("title", "查看累计统计的范围与计算方式")
    target = help_button.bounding_box()
    assert target["width"] >= MIN_TOUCH_TARGET and target["height"] >= MIN_TOUCH_TARGET
    help_button.focus()
    page.keyboard.press("Enter")
    expect(page.get_by_role("dialog", name="累计统计说明")).to_be_visible()
    expect(page.get_by_role("button", name="知道了", exact=True)).to_be_focused()
    page.keyboard.press("Shift+Tab")
    expect(page.locator("#close-modal")).to_be_focused()
    page.keyboard.press("Tab")
    expect(page.get_by_role("button", name="知道了", exact=True)).to_be_focused()
    page.keyboard.press("Escape")
    expect(page.locator("#modal")).not_to_be_visible()
    expect(help_button).to_be_focused()
    page.keyboard.press("Space")
    expect(page.locator("#modal")).to_be_visible()
    page.keyboard.press("Enter")
    expect(page.locator("#modal")).not_to_be_visible()
    expect(help_button).to_be_focused()
    initial = alltime_snapshot(page)
    minutes = float(page.locator("#alltime-duration").get_attribute("data-minutes"))
    samples = page.evaluate("FocusModel.initialFocusRecords().map(record => ({seconds: record.durationSeconds, date: FocusModel.localDateKey(new Date(record.endedAt))}))")
    sample_dates = {datetime.fromisoformat(record["date"]).date() for record in samples}
    assert minutes == sum(record["seconds"] for record in samples) / 60
    assert minutes > float(page.locator("#statistics-minutes").inner_text())
    today = datetime.fromisoformat(page.evaluate("FocusModel.localDateKey(new Date())")).date()
    denominators = [int(value) for value in re.findall(r"分母：(\d+) 个(?:自然|活跃)日", initial["details"])]
    assert denominators == [(today - min(sample_dates)).days + 1, len(sample_dates)]
    assert f"{min(sample_dates)} 至 {today} · {len(samples)} 次专注" in initial["details"]
    for explanation in ("自然日均 = 累计专注分钟数 ÷ 自然日天数", "活跃日均 = 累计专注分钟数 ÷ 有专注记录的天数", "首尾均计入", "空白日", "按日历差", "四舍五入至两位小数", "示例 + 本次计时（含演示记录）", "仅驻留内存", "刷新恢复示例", "不代表真实设备", "不受下方日、周、月、年周期和所选日期影响"):
        assert explanation in initial["details"]
    assert denominators[0] > denominators[1] > 0
    for name, denominator in zip(("alltime-calendar-average", "alltime-active-average"), denominators):
        assert abs(float(initial[name]) - minutes / denominator) <= 0.005
    for period, trend_count in STAT_PERIODS:
        assert_statistics_period(page, period, trend_count)
        assert alltime_snapshot(page) == initial
    page.locator("#statistics-anchor").fill("2024-02-29")
    page.locator("#statistics-anchor").dispatch_event("change")
    expect(page.locator("#statistics-count")).to_have_text("0")
    assert alltime_snapshot(page) == initial
    page.locator("#statistics-source").select_option("session")
    expect(page.locator("#alltime-duration")).to_have_text("0 分钟")
    expect(page.locator("#alltime-calendar-average")).to_have_text("0")
    expect(page.locator("#alltime-active-average")).to_have_text("0")
    empty = alltime_snapshot(page)
    assert "分母：0 个自然日" in empty["details"]
    assert "分母：0 个活跃日" in empty["details"]
    assert f"截至 {today} 暂无记录 · 0 次专注" in empty["details"]
    assert "仅本次计时（不含示例）" in empty["details"]
    page.locator("#statistics-source").select_option("all")
    assert alltime_snapshot(page) == initial
    choose_focus_item(page, PROJECT_TITLES[1])
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(61_000)
    page.locator("#timer-finish").click()
    expect(page.locator("#focus-sessions")).to_have_text("4")
    skip_progress(page, return_to_timer=False)
    open_statistics(page)
    expect(page.locator("#statistics-count")).to_have_text("0")
    assert abs(float(page.locator("#alltime-duration").get_attribute("data-minutes")) - minutes - 61 / 60) < 0.000001
    completed = alltime_snapshot(page)
    assert f"{min(sample_dates)} 至 {today} · {len(samples) + 1} 次专注" in completed["details"]
    for name, denominator in zip(("alltime-calendar-average", "alltime-active-average"), denominators):
        assert abs(float(completed[name]) - (minutes + 61 / 60) / denominator) <= 0.005
    page.locator("#statistics-source").select_option("session")
    expect(page.locator("#alltime-duration")).to_have_text("1 分钟 1 秒")
    expect(page.locator("#alltime-calendar-average")).to_have_text("1.02")
    expect(page.locator("#alltime-active-average")).to_have_text("1.02")
    session = alltime_snapshot(page)
    assert f"{today} 至 {today} · 1 次专注" in session["details"]
    assert "分母：1 个自然日" in session["details"]
    assert "分母：1 个活跃日" in session["details"]
    assert "仅本次计时（不含示例）" in session["details"]
    page.locator("#statistics-today").click()
    page.locator('[data-stat-period="day"]').click()
    assert alltime_snapshot(page) == session
    page.locator("#statistics-source").select_option("all")
    assert alltime_snapshot(page) == completed
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "desktop-alltime-statistics.png"))
    page.set_viewport_size({"width": 320, "height": MOBILE_HEIGHT})
    assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-alltime-statistics.png"))
    help_button.click()
    expect(page.get_by_role("dialog", name="累计统计说明")).to_be_visible()
    assert page.locator("#modal").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-alltime-help.png"))
    page.locator("#close-modal").click()
    expect(help_button).to_be_focused()
    page.locator("#statistics-source").select_option("session")
    assert alltime_snapshot(page) == session
    payload = download_statistics(page, "json")
    assert abs(payload["allTimeSummary"]["minutes"] - 61 / 60) < 0.000001
    assert payload["allTimeSummary"]["count"] == 1
    assert payload["allTimeSummary"]["calendarDays"] == payload["allTimeSummary"]["activeDays"] == 1
    assert payload["progressHistory"] == []
    page.locator("#statistics-source").select_option("all")
    payload = download_statistics(page, "json")
    assert payload["allTimeSummary"]["minutes"] > payload["summary"]["minutes"]
    assert abs(payload["allTimeSummary"]["minutes"] - minutes - 61 / 60) < 0.000001
    page.close()
    page = fresh_timer_page(browser, errors)
    page.clock.fast_forward(HOURS_PER_DAY * 60 * ONE_MINUTE_MS)
    open_statistics(page)
    next_day = datetime.fromisoformat(page.evaluate("FocusModel.localDateKey(new Date())")).date()
    advanced = alltime_snapshot(page)
    assert advanced["alltime-duration"] == initial["alltime-duration"]
    assert advanced["alltime-active-average"] == initial["alltime-active-average"]
    assert f"{min(sample_dates)} 至 {next_day} · {len(samples)} 次专注" in advanced["details"]
    assert f"分母：{denominators[0] + 1} 个自然日" in advanced["details"]
    assert f"分母：{denominators[1]} 个活跃日" in advanced["details"]
    assert abs(float(advanced["alltime-calendar-average"]) - minutes / (denominators[0] + 1)) <= 0.005
    page.close()
    print("PASS: minimal white all-time overview, keyboard help/focus return, dynamic popup formulas/range/denominators, next-day refresh, period/date invariance, source filters, completion refresh and JSON scope.", flush=True)


def run_checks():
    print(f"Screenshots: {SCREENSHOT_DIRECTORY}", flush=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=True)
        page = browser.new_page(viewport=DESKTOP_VIEWPORT, device_scale_factor=1, reduced_motion="reduce", timezone_id="Asia/Shanghai")
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.clock.install(time=datetime(2026, 9, 26, 8, 0, tzinfo=timezone.utc))
        page.goto(BASE_URL)
        assert page.locator("body").evaluate("el => getComputedStyle(el).backgroundColor") == "rgb(255, 255, 255)"
        assert page.locator(".phone").evaluate("el => getComputedStyle(el).backgroundColor") == "rgb(255, 255, 255)"
        accent = page.locator("body").evaluate("el => getComputedStyle(el).getPropertyValue('--accent').trim()")
        assert accent.lower() == "#c44e22", accent
        expect(page.locator("#timer-time")).to_have_text("25:00")
        page.screenshot(path=str(SCREENSHOT_DIRECTORY / "desktop.png"), full_page=True)
        choose_focus_item(page, PROJECT_TITLES[0])

        page.locator("#timer-toggle").click()
        page.clock.fast_forward(2_000)
        expect(page.locator("#timer-time")).to_have_text("24:58")
        page.locator("#timer-toggle").click()
        page.clock.fast_forward(5_000)
        expect(page.locator("#timer-time")).to_have_text("24:58")
        page.locator('[data-mode="short"]').click()
        expect(page.locator("#modal")).to_be_visible()
        page.get_by_role("button", name="取消", exact=True).click()
        expect(page.locator("#timer-time")).to_have_text("24:58")
        page.locator("#timer-reset").click()
        page.get_by_role("button", name="放弃并继续", exact=True).click()
        expect(page.locator("#timer-time")).to_have_text("25:00")

        page.locator("#timer-settings").click()
        page.locator('input[name="durationMinutes"]').fill("1")
        page.get_by_role("button", name="保存专注项", exact=True).click()
        page.locator("#timer-toggle").click()
        page.clock.fast_forward(ONE_MINUTE_MS)
        expect(page.locator("#timer-time")).to_have_text("00:00")
        expect(page.locator("#focus-sessions")).to_have_text("4")
        expect(page.locator("#focus-minutes")).to_have_text("76")
        skip_progress(page)
        page.clock.fast_forward(ONE_MINUTE_MS)
        expect(page.locator("#focus-sessions")).to_have_text("4")

        page.locator('.bottom-nav [data-page="tasks"]').click()
        page.locator("#add-task").click()
        page.locator('input[name="title"]').fill("   ")
        page.get_by_role("button", name="添加待办", exact=True).click()
        expect(page.locator("#modal [role='alert']")).to_contain_text("任务名称")
        expect(page.locator("#modal")).to_be_visible()
        unsafe_title = '<img src=x onerror="window.injected=true">'
        page.locator('input[name="title"]').fill(unsafe_title)
        page.get_by_role("button", name="添加待办", exact=True).click()
        expect(page.locator("#all-count")).to_have_text("5")
        added = page.locator(".task-card").filter(has_text=unsafe_title)
        expect(added).to_have_count(1)
        assert page.evaluate("window.injected === undefined")
        assert added.locator("img").count() == 0
        added.locator(".task-checkbox").click()
        expect(page.locator("#task-summary")).to_have_text("今天已完成 2 / 5 件事")
        page.locator('[data-filter="done"]').click()
        expect(page.locator(".task-card")).to_have_count(2)
        added.locator(".task-info").click()
        page.locator('input[name="title"]').fill("自动测试待办")
        page.get_by_role("button", name="保存修改", exact=True).click()
        added = page.locator(".task-card").filter(has_text="自动测试待办")
        added.locator(".task-checkbox").click()
        added.locator(".task-start").click()
        expect(page.locator("#page-timer")).to_be_visible()
        expect(page.locator("#linked-task-name")).to_have_text("自动测试待办")
        page.locator("#timer-toggle").click()
        page.clock.fast_forward(ONE_MINUTE_MS)
        expect(page.locator("#focus-sessions")).to_have_text("5")
        skip_progress(page)
        page.locator('.bottom-nav [data-page="tasks"]').click()
        added.locator(".task-info").click()
        page.get_by_role("button", name="删除", exact=True).click()
        expect(page.get_by_role("button", name="取消", exact=True)).to_be_focused()
        page.get_by_role("button", name="确认删除", exact=True).click()
        expect(page.locator("#all-count")).to_have_text("4")
        expect(page.locator("#linked-task-name")).to_have_text("不关联待办")

        page.locator('.bottom-nav [data-page="focus"]').click()
        page.locator("#open-statistics").click()
        expect(page.locator("#statistics-minutes")).to_have_text("77")
        expect(page.locator("#statistics-count")).to_have_text("5")
        expect(page.locator("#statistics-records")).to_contain_text("自动测试待办")
        page.locator("#statistics-source").select_option("session")
        expect(page.locator("#statistics-count")).to_have_text("2")
        expect(page.locator("#statistics-minutes")).to_have_text("2")
        for period, count in STAT_PERIODS:
            assert_statistics_period(page, period, count)
            expect(page.locator("#statistics-minutes")).to_have_text("2")
        page.locator('[data-stat-period="day"]').click()
        page.locator("#export-statistics").click()
        page.locator('select[name="format"]').select_option("JSON（明细与汇总）")
        with page.expect_download() as download_event:
            page.get_by_role("button", name="下载统计文件", exact=True).click()
        download = download_event.value
        payload = json.loads(Path(download.path()).read_text(encoding="utf-8"))
        assert download.suggested_filename.endswith(".json")
        assert payload["includesSamples"] is False
        assert payload["summary"]["count"] == 2
        assert payload["summary"]["minutes"] == 2
        assert {record["taskTitle"] for record in payload["records"]} == {PROJECT_TITLES[0], "自动测试待办"}
        assert all(record["source"] == "session" for record in payload["records"])
        assert all(record["focusItemTitle"] == PROJECT_TITLES[0] and record["timerMode"] == "countdown" and record["targetMinutes"] == 1 and record["durationSeconds"] == 60 for record in payload["records"])
        assert all(len(record["segments"]) == 1 for record in payload["records"])
        page.locator("#statistics-source").select_option("all")
        page.locator("#export-statistics").click()
        with page.expect_download() as download_event:
            page.get_by_role("button", name="下载统计文件", exact=True).click()
        download = download_event.value
        csv_bytes = Path(download.path()).read_bytes()
        assert csv_bytes.startswith(b"\xef\xbb\xbf")
        csv_rows = list(csv.reader(io.StringIO(csv_bytes.decode("utf-8-sig"))))
        assert csv_rows[0] == CSV_HEADERS
        assert all(len(row) == len(CSV_HEADERS) for row in csv_rows)
        assert len(csv_rows) == 6
        assert sum(float(row[5]) for row in csv_rows[1:]) == 77
        assert {row[6] for row in csv_rows[1:]} == {"示例", "专注计时"}
        page.locator("#statistics-anchor").fill("2024-02-29")
        page.locator("#statistics-anchor").dispatch_event("change")
        expect(page.locator(".calendar-day")).to_have_count(29)
        expect(page.locator("#statistics-count")).to_have_text("0")
        expect(page.locator("#export-statistics")).to_be_disabled()
        page.locator('[data-stat-period="month"]').click()
        page.locator("#statistics-next").click()
        expect(page.locator("#statistics-anchor")).to_have_value("2024-03-01")
        page.locator("#statistics-today").click()
        page.locator(".calendar-day").filter(has_text="26").click()
        expect(page.locator('[data-stat-period="day"]')).to_have_attribute("aria-pressed", "true")
        expect(page.locator("#statistics-count")).to_have_text("5")
        page.locator('[data-stat-period="month"]').click()
        expected_records = int(page.locator("#statistics-count").inner_text())
        expect(page.locator("#statistics-records > li")).to_have_count(20)
        page.locator("#statistics-more").click()
        expect(page.locator("#statistics-records > li")).to_have_count(min(40, expected_records))
        page.locator("#export-statistics").click()
        with page.expect_download() as download_event:
            page.get_by_role("button", name="下载统计文件", exact=True).click()
        rows = list(csv.reader(io.StringIO(Path(download_event.value.path()).read_text(encoding="utf-8-sig"))))
        assert len(rows) - 1 == expected_records

        page.locator('.bottom-nav [data-page="usage"]').click()
        page.locator('[data-period="week"]').click()
        expect(page.locator("#usage-total-label")).to_have_text("本周屏幕使用")
        expect(page.locator(".chart-bar")).to_have_count(7)
        page.locator("#edit-goal").click()
        page.locator('input[name="goal"]').fill("120")
        page.get_by_role("button", name="保存目标", exact=True).click()
        expect(page.locator("#goal-summary")).to_contain_text("超出目标 1 小时 24 分钟")
        page.locator("#reminder-switch").click()
        expect(page.locator("#reminder-switch")).to_have_attribute("aria-checked", "true")

        page.locator('.bottom-nav [data-page="profile"]').click()
        for button in ("backup-button", "permissions-button", "automation-button"):
            page.locator(f"#{button}").click()
            expect(page.locator("#modal")).to_be_visible()
            page.keyboard.press("Escape")
            expect(page.locator("#modal")).not_to_be_visible()

        page.reload()
        expect(page.locator("#all-count")).to_have_text("4")
        expect(page.locator("#focus-sessions")).to_have_text("3")
        expect(page.locator("#timer-time")).to_have_text("25:00")
        for width in MOBILE_WIDTHS:
            page.set_viewport_size({"width": width, "height": MOBILE_HEIGHT})
            for name in PAGES:
                page.locator(f'.bottom-nav [data-page="{name}"]').click()
                expect(page.locator(f"#page-{name}")).to_be_visible()
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), (width, name, "document overflow")
                assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth"), (width, name, "phone overflow")
                if width == 390:
                    page.screenshot(path=str(SCREENSHOT_DIRECTORY / f"mobile-{name}.png"))
            page.locator('.bottom-nav [data-page="focus"]').click()
            page.locator("#open-statistics").click()
            for period, trend_count in STAT_PERIODS:
                assert_statistics_period(page, period, trend_count)
                assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth"), (width, period, "statistics overflow")
            if width == 390:
                page.locator('[data-stat-period="week"]').click()
                page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-statistics.png"))
                page.locator("#statistics-calendar").screenshot(path=str(SCREENSHOT_DIRECTORY / "calendar.png"))
            page.locator("#export-statistics").click()
            assert page.locator("#modal").evaluate("el => el.scrollWidth <= el.clientWidth"), (width, "export dialog overflow")
            page.keyboard.press("Escape")

        for width in (801, 850, 906, 920, 921, 1024):
            page.set_viewport_size({"width": width, "height": MOBILE_HEIGHT})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), (width, "tablet overflow")

        page.set_viewport_size({"width": 667, "height": 375})
        page.locator('.bottom-nav [data-page="tasks"]').click()
        page.locator("#add-task").click()
        page.locator('input[name="title"]').fill("横屏弹窗测试")
        page.get_by_role("button", name="添加待办", exact=True).click()
        expect(page.locator("#all-count")).to_have_text("5")
        expect(page.locator("#modal")).not_to_be_visible()

        print("PASS: core interactions, CSV/JSON content, filtering, pagination, responsive layouts.", flush=True)
        page.set_viewport_size(DESKTOP_VIEWPORT)
        page.goto(Path(__file__).resolve().with_name("index.html").as_uri())
        expect(page.locator("#timer-time")).to_have_text("25:00")
        page.locator('.bottom-nav [data-page="tasks"]').click()
        expect(page.locator(".task-card")).to_have_count(4)

        print("PASS: direct file opening. Starting midnight checks.", flush=True)
        midnight_page = browser.new_page(viewport=DESKTOP_VIEWPORT, reduced_motion="reduce", timezone_id="Asia/Shanghai")
        midnight_page.on("pageerror", lambda error: errors.append(str(error)))
        midnight_page.clock.install(time=datetime(2026, 9, 26, 15, 59, 30, tzinfo=timezone.utc))
        midnight_page.goto(BASE_URL)
        print("Midnight page ready.", flush=True)
        choose_focus_item(midnight_page, PROJECT_TITLES[0])
        midnight_page.locator("#timer-settings").click()
        midnight_page.locator('input[name="durationMinutes"]').fill("1")
        midnight_page.get_by_role("button", name="保存专注项", exact=True).click()
        midnight_page.locator("#timer-toggle").click()
        midnight_page.clock.fast_forward(20_000)
        midnight_page.locator("#timer-toggle").click()
        midnight_page.clock.fast_forward(ONE_MINUTE_MS)
        midnight_page.locator("#timer-toggle").click()
        open_statistics(midnight_page)
        midnight_page.locator("#statistics-today").click()
        midnight_page.locator("#statistics-source").select_option("session")
        expect(midnight_page.locator("#statistics-count")).to_have_text("0")
        print("Midnight pause/resume verified; advancing delayed completion.", flush=True)
        midnight_page.clock.fast_forward(10 * ONE_MINUTE_MS)
        expect(midnight_page.locator("#statistics-count")).to_have_text("1")
        expect(midnight_page.locator("#statistics-minutes")).to_have_text("1")
        expect(midnight_page.locator("#focus-sessions")).to_have_text("1")
        skip_progress(midnight_page, return_to_timer=False)
        open_statistics(midnight_page)
        bins = assert_hour_distribution(midnight_page)
        assert abs(bins[23] - 20 / 60) < 0.02
        assert abs(bins[0] - 40 / 60) < 0.02
        midnight_page.locator("#export-statistics").click()
        midnight_page.locator('select[name="format"]').select_option("JSON（明细与汇总）")
        with midnight_page.expect_download() as download_event:
            midnight_page.get_by_role("button", name="下载统计文件", exact=True).click()
        print("Midnight completion verified; reading export.", flush=True)
        payload = json.loads(Path(download_event.value.path()).read_text(encoding="utf-8"))
        record = payload["records"][0]
        started = datetime.fromisoformat(record["startedAt"].replace("Z", "+00:00"))
        ended = datetime.fromisoformat(record["endedAt"].replace("Z", "+00:00"))
        assert 119 <= (ended - started).total_seconds() <= 121
        assert record["durationMinutes"] == 1 and record["durationSeconds"] == 60
        assert len(record["segments"]) == 2
        midnight_page.close()
        print("PASS: paused duration, midnight grouping, delayed completion timestamp.", flush=True)

        boundary_page = browser.new_page(viewport=DESKTOP_VIEWPORT, reduced_motion="reduce", timezone_id="Asia/Shanghai")
        boundary_page.on("pageerror", lambda error: errors.append(str(error)))
        boundary_page.clock.install(time=datetime(2026, 9, 26, 8, 0, tzinfo=timezone.utc))
        boundary_page.goto(BASE_URL)
        choose_focus_item(boundary_page, PROJECT_TITLES[0])
        boundary_page.locator("#timer-settings").click()
        boundary_page.locator('input[name="durationMinutes"]').fill("1")
        boundary_page.get_by_role("button", name="保存专注项", exact=True).click()
        boundary_page.locator('[data-mode="short"]').click()
        boundary_page.locator("#timer-toggle").click()
        boundary_page.clock.fast_forward(5 * ONE_MINUTE_MS)
        expect(boundary_page.locator("#focus-sessions")).to_have_text("3")
        boundary_page.locator('[data-mode="focus"]').click()
        boundary_page.locator("#timer-toggle").click()
        open_statistics(boundary_page)
        boundary_page.locator("#export-statistics").click()
        boundary_page.locator('select[name="format"]').select_option("JSON（明细与汇总）")
        boundary_page.clock.fast_forward(ONE_MINUTE_MS)
        with boundary_page.expect_download() as download_event:
            boundary_page.get_by_role("button", name="下载统计文件", exact=True).click()
        payload = json.loads(Path(download_event.value.path()).read_text(encoding="utf-8"))
        assert payload["summary"]["count"] == 4, "export must include completions while dialog is open"
        assert payload["summary"]["minutes"] == 76
        skip_progress(boundary_page, return_to_timer=False)
        boundary_page.close()
        check_focus_configuration(browser, errors)
        check_independent_countup(browser, errors)
        check_project_navigation(browser, errors)
        check_cancelled_settings_drafts(browser, errors)
        check_progress_workflow(browser, errors)
        check_progress_queue(browser, errors)
        check_alltime_statistics(browser, errors)
        assert not errors, errors
        browser.close()
    print("PASS: project navigation, timer, tasks, progress drafts/revisions/queues, all-time summaries, safe rendering, CSV/JSON downloads, source/date filters, pagination, 16 main-page and 16 statistics layouts, midnight/pause accounting, live export, direct file open; no browser exceptions.")
    print(f"Screenshots: {SCREENSHOT_DIRECTORY}")


if __name__ == "__main__":
    run_checks()
