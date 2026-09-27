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
CSV_HEADERS = ["日期", "分类", "开始时间", "结束时间", "时长（分钟）", "来源", "计时模式", "专注项", "目标时长（分钟）", "时长（秒）", "学习进度", "完成度（%）", "进度更新时间"]
PROJECT_TITLES = ("Spring Boot 实战", "个人 APP 开发", "算法与数据结构")
ALLTIME_IDS = ("alltime-duration", "alltime-calendar-average", "alltime-active-average")
MIN_TOUCH_TARGET = 44
MAX_TASK_STEPS = 8
TASK_SWIPE_WIDTH = 88
TASK_TOUCH_DRAG = 48
TOUCH_MOVE_STEPS = 12
TASK_FINISH_HOLD_MS = 680
TASK_FINISH_COLLAPSE_MS = 240
TASK_FINISH_MS = TASK_FINISH_HOLD_MS + TASK_FINISH_COLLAPSE_MS
TASK_FINISH_UNDO_MS = 500
TASK_FINISH_STAGGER_MS = 100
TASK_HISTORY_PAGE_SIZE = 20
LANDSCAPE_VIEWPORT = {"width": 667, "height": 375}


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


def assert_no_task_fields(value):
    if isinstance(value, dict):
        assert not {"taskId", "taskTitle", "taskStepId", "estimate"}.intersection(value), value
        for child in value.values():
            assert_no_task_fields(child)
    elif isinstance(value, list):
        for child in value:
            assert_no_task_fields(child)


def assert_no_task_focus_ui(page):
    expect(page.locator("#link-task, #linked-task, #linked-task-name, .linked-task, .task-start, .task-choice")).to_have_count(0)
    expect(page.get_by_role("button", name=re.compile("关联.*待办|专注于"))).to_have_count(0)


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
        assert_no_task_fields(payload)
        if "hourDistribution" in payload:
            assert len(payload["hourDistribution"]) == HOURS_PER_DAY
            assert abs(sum(bin["minutes"] for bin in payload["hourDistribution"]) - payload["summary"]["minutes"]) < 0.02
        return payload
    assert data.startswith(b"\xef\xbb\xbf")
    assert data.splitlines(keepends=True)[0].endswith(b"\r\n")
    rows = list(csv.reader(io.StringIO(data.decode("utf-8-sig"))))
    assert rows[0] == CSV_HEADERS
    assert all(len(row) == len(CSV_HEADERS) for row in rows)
    return [dict(zip(rows[0], row)) for row in rows[1:]]


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
    page.locator('#modal input[name="title"]').fill(title)
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
    page.locator('#modal input[name="title"]').fill("   ")
    page.get_by_role("button", name="添加项目", exact=True).click()
    expect(page.locator("#modal [role='alert']")).to_contain_text("专注项名称")
    page.locator('#modal input[name="title"]').fill(title)
    page.locator('select[name="category"]').select_option("工作")
    page.locator('select[name="timerMode"]').select_option("正计时")
    page.locator('input[name="durationMinutes"]').fill("1")
    expect(page.locator('#modal input[name="title"]')).to_have_attribute("maxlength", "80")
    assert page.locator("#modal").evaluate("el => el.scrollWidth <= el.clientWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-focus-item-editor.png"))
    page.get_by_role("button", name="添加项目", exact=True).click()
    assert_task_counts(page, pending=3, done=1)
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
    page.locator('#modal input[name="title"]').fill("修改后的专注项")
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
    assert_task_counts(page, pending=3, done=1)
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
        assert all(record["source"] == "session" for record in records)
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
        assert len(rows) == 2
        assert {row["计时模式"] for row in rows} == {"正计时"}
        assert {row["专注项"] for row in rows} == {title}
        assert {row["目标时长（分钟）"] for row in rows} == {"1"}
        assert sorted(int(row["时长（秒）"]) for row in rows) == [1, 90]
        assert abs(sum(float(row["时长（分钟）"]) for row in rows) - 91 / 60) < 0.000001
    page.close()
    print("PASS: independent items, 80-character mobile dialogs, countup targets, early/paused finish, immutable history and four-period CSV/JSON.", flush=True)


def assert_task_counts(page, pending, done, archived=0):
    for name, count in (("pending", pending), ("done", done), ("archived", archived)):
        expect(page.locator(f"#{name}-count")).to_have_text(str(count))
    expect(page.locator("#task-summary")).to_have_count(0)
    expect(page.locator(".task-tabs button")).to_have_count(3)


def task_card(page, title):
    return page.locator("article.task-card").filter(has=page.locator(".task-title", has_text=re.compile(f"^{re.escape(title)}$")))


def create_task(page, title, category="个人成长", steps=()):
    page.locator("#add-task").click()
    expect(page.locator("#todo-sheet")).to_be_visible()
    expect(page.locator("#modal")).not_to_be_visible()
    page.locator('#todo-form input[name="title"]').fill(title)
    page.locator("#todo-form button.category-choice").filter(has_text=category).click()
    if steps:
        page.locator("#todo-form .step-editor summary").click()
        for step in steps:
            page.locator("#todo-form .add-step").click()
            page.locator("#todo-form .step-editor-row input").last.fill(step)
    page.locator("#save-todo-sheet").click()
    expect(page.locator("#todo-sheet")).not_to_be_visible()
    return task_card(page, title)


def reopen_task(page, title, redo_indices=()):
    task_card(page, title).locator(".task-info").click()
    page.locator("#modal").get_by_role("button", name="重新打开", exact=True).click()
    for index in redo_indices:
        page.locator('#modal label input[type="checkbox"]').nth(index).check()
    page.locator("#modal").get_by_role("button", name="确认重新打开", exact=True).click()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator('[data-filter="pending"]')).to_have_attribute("aria-pressed", "true")


def group_labels(page):
    return page.locator(".task-group-label").all_inner_texts()


def group_titles(page, label):
    group = page.locator(".task-group").filter(has=page.locator(".task-group-label", has_text=label))
    return group.locator(".task-title").all_inner_texts()


def check_task_grouping(browser, errors):
    """重要的事置顶，工作与生活在查看时分开，空组不出现。"""
    page = browser.new_page(viewport=DESKTOP_VIEWPORT, reduced_motion="reduce", timezone_id="Asia/Shanghai")
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(BASE_URL)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    # 示例数据：重要的「梳理个人 APP 的想法」原属工作，置顶后工作组为空而不显示。
    assert group_labels(page) == ["重要", "个人成长", "生活"], group_labels(page)
    assert group_titles(page, "重要") == ["梳理个人 APP 的想法"]
    assert group_titles(page, "个人成长") == ["阅读《原子习惯》"]
    # 已完成的「整理书桌」仅在完成记录页出现。
    assert group_titles(page, "生活") == ["傍晚出去走一走"]
    assert_task_counts(page, pending=3, done=1)
    expect(page.locator('[data-filter="pending"]')).to_have_attribute("aria-pressed", "true")
    expect(page.locator('[data-filter="all"], #all-count')).to_have_count(0)
    expect(page.locator(".task-group").first.locator(".task-group-count")).to_have_text("1")

    # 新增一件工作的事，工作组出现在生活组之前；组标题已说明分类，卡片不再重复标签。
    create_task(page, "写周报", category="工作")
    assert group_labels(page) == ["重要", "个人成长", "工作", "生活"], group_labels(page)
    assert group_titles(page, "工作") == ["写周报"]
    added = page.locator(".task-card").filter(has_text="写周报")
    assert added.locator(".task-tag").count() == 0

    # 标为重要后移入置顶组，并显示原分类标签；取消重要则回到分类组。
    added.locator(".task-info").click()
    page.locator('#todo-form input[name="important"]').check()
    page.locator("#save-todo-sheet").click()
    assert group_titles(page, "重要") == ["梳理个人 APP 的想法", "写周报"]
    assert group_labels(page) == ["重要", "个人成长", "生活"], group_labels(page)
    expect(page.locator(".task-card").filter(has_text="写周报").locator(".task-tag")).to_have_text("工作")
    page.locator(".task-card").filter(has_text="写周报").locator(".task-info").click()
    page.locator('#todo-form input[name="important"]').uncheck()
    page.locator("#save-todo-sheet").click()
    assert group_titles(page, "工作") == ["写周报"]

    # 完成记录按时间分组，放下的事不留在待完成分类组里。
    page.locator('[data-filter="done"]').click()
    assert group_labels(page) == ["昨天"], group_labels(page)
    assert group_titles(page, "昨天") == ["整理书桌，清空杂念"]
    page.locator('[data-filter="archived"]').click()
    expect(page.locator(".empty-state")).to_be_visible()
    assert group_labels(page) == []
    page.locator('[data-filter="pending"]').click()
    assert group_labels(page) == ["重要", "个人成长", "工作", "生活"], group_labels(page)

    # 窄屏下分组不撑破手机宽度。
    page.set_viewport_size({"width": 380, "height": 760})
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "mobile-task-groups.png"))
    page.close()
    print("PASS: important tasks pinned on top, work and life shown as separate groups.", flush=True)


def check_task_guide(browser, errors):
    page = fresh_timer_page(browser, errors)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    modal = page.locator("#modal")
    guide = page.get_by_role("button", name="使用指南", exact=True)
    sections = [
        ("拆成小步", "点进度展开，每一步整行都能勾选。"),
        ("删除与撤销", "左滑后点删除，可在底部撤销；关闭撤销提示后失效。"),
        ("回看与重做", "已完成按月查看，搜索覆盖全部时间；重做请到详情中「重新打开」。"),
        ("暂时放下", "放下不是删除，需要时可以找回。"),
    ]
    for width in MOBILE_WIDTHS:
        page.set_viewport_size({"width": width, "height": MOBILE_HEIGHT})
        header_positions = []
        for tab in ("pending", "done", "archived"):
            page.locator(f'[data-filter="{tab}"]').click()
            assert_task_counts(page, pending=3, done=1)
            header = page.locator(".todo-page-header").bounding_box()
            title = page.locator("#tasks-heading").bounding_box()
            help_box = guide.bounding_box()
            add = page.locator("#add-task").bounding_box()
            tabs = page.locator(".task-tabs").bounding_box()
            header_positions.append((title["x"], title["y"], tabs["y"]))
            assert title["x"] == header["x"]
            assert title["x"] + title["width"] <= help_box["x"]
            assert help_box["x"] + help_box["width"] < add["x"]
            assert abs(add["x"] + add["width"] - header["x"] - header["width"]) <= 1
            assert help_box["height"] >= MIN_TOUCH_TARGET and help_box["width"] >= MIN_TOUCH_TARGET
            assert guide.evaluate("el => getComputedStyle(el).borderTopWidth === '0px'")
            expect(page.locator(".todo-page-header > button")).to_have_count(1)
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            assert page.locator("#phone-content").evaluate("el => el.scrollWidth <= el.clientWidth")
        assert header_positions.count(header_positions[0]) == len(header_positions)
        guide.click()
        expect(page.get_by_role("dialog", name="待办怎么用", exact=True)).to_be_visible()
        expect(modal).to_have_class("todo-guide-sheet")
        expect(modal.locator(".modal-header .eyebrow")).not_to_be_visible()
        expect(modal.locator(".todo-guide-content section")).to_have_count(len(sections))
        expect(modal.locator(".todo-guide-content h3")).to_have_text([title for title, _ in sections])
        expect(modal.locator(".todo-guide-content p")).to_have_text([text for _, text in sections])
        for technical_text in ("每次20条", "进程", "数据库", "草稿"):
            expect(modal).not_to_contain_text(technical_text)
        expect(modal.locator("#modal-content button")).to_have_count(1)
        expect(page.locator("#close-modal")).to_be_focused()
        assert modal.evaluate("el => el.scrollHeight <= el.clientHeight && el.scrollWidth <= el.clientWidth")
        box = modal.bounding_box()
        assert abs(box["y"] + box["height"] - MOBILE_HEIGHT) <= 1
        modal.get_by_role("button", name="知道了", exact=True).click()
        expect(modal).not_to_be_visible()
        expect(guide).to_be_focused()
        page.locator('[data-filter="done"]').click()
        page.locator("#task-month-picker").click()
        expect(modal).not_to_have_class(re.compile("todo-guide-sheet"))
        expect(modal.locator(".modal-header .eyebrow")).to_be_visible()
        expect(page.locator("#modal-title")).to_have_text("翻到哪一月？")
        page.locator("#close-modal").click()
    page.set_viewport_size(LANDSCAPE_VIEWPORT)
    guide.click()
    assert modal.evaluate("el => el.scrollHeight > el.clientHeight && el.scrollWidth <= el.clientWidth && el.scrollTop === 0")
    expect(page.locator("#close-modal")).to_be_focused()
    modal.get_by_role("button", name="知道了", exact=True).click()
    guide.click()
    page.keyboard.press("Escape")
    expect(modal).not_to_be_visible()
    expect(guide).to_be_focused()
    guide.click()
    page.locator("#close-modal").click()
    expect(modal).not_to_be_visible()
    page.close()
    print("PASS: stable counted tabs without summary; left text guide/right add at 320px; concise four-section bottom sheet, short-screen scrolling, focus return and modal variant reset.", flush=True)


def check_task_focus_independence(browser, errors):
    page = fresh_timer_page(browser, errors)
    assert_no_task_focus_ui(page)
    choose_focus_item(page, PROJECT_TITLES[1])
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(5_000)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    assert_no_task_focus_ui(page)
    task = page.locator(".task-card").filter(has_text="梳理个人 APP 的想法")
    task.locator(".step-expand").click()
    steps = task.locator("button.step-row")
    expect(steps).to_have_count(3)
    assert steps.evaluate_all("rows => rows.map(row => row.getAttribute('aria-checked'))") == ["true", "true", "false"]
    steps.last.click()
    expect(task).to_have_count(0)
    assert_task_counts(page, pending=2, done=2)
    page.locator('[data-filter="done"]').click()
    expect(task.locator("span.task-check-static")).to_be_visible()
    reopen_task(page, "梳理个人 APP 的想法", redo_indices=(0,))
    expect(steps.first).to_have_attribute("aria-checked", "false")
    task.locator(".task-checkbox").click()
    page.locator('[data-filter="done"]').click()
    task.locator(".task-info").click()
    expect(page.locator(".task-detail-steps li")).to_have_text([
        "已走过 · 写下想解决的问题", "已走过 · 画三张草图", "已走过 · 选一个先做",
    ])
    page.locator("#close-modal").click()
    reopen_task(page, "梳理个人 APP 的想法", redo_indices=(2,))
    expect(steps.last).to_have_attribute("aria-checked", "false")
    task.locator(".task-info").click()
    page.locator('#todo-form input[name="title"]').fill("独立待办改名")
    page.locator("#save-todo-sheet").click()
    expect(page.locator("#todo-sheet")).not_to_be_visible()
    expect(page.locator("#page-tasks")).to_be_visible()
    expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[1])
    expect(page.locator("#timer-time")).to_have_text("00:05")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.clock.fast_forward(5_000)
    choose_focus_item(page, PROJECT_TITLES[1])
    expect(page.locator("#timer-time")).to_have_text("00:10")
    page.locator("#timer-toggle").click()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    task = page.locator(".task-card").filter(has_text="独立待办改名")
    expect(task.locator(".task-checkbox")).to_have_attribute("aria-pressed", "false")
    expect(task.locator(".step-row").last).to_have_attribute("aria-checked", "false")
    task.locator(".task-menu").click()
    page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator("#task-undo")).to_be_visible()
    assert_task_counts(page, pending=2, done=1)
    page.clock.fast_forward(5_000)
    choose_focus_item(page, PROJECT_TITLES[1])
    expect(page.locator("#timer-time")).to_have_text("00:10")
    page.locator("#timer-finish").click()
    expect(page.locator("#focus-sessions")).to_have_text("4")
    skip_progress(page, return_to_timer=False)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    assert_task_counts(page, pending=2, done=1)
    assert_no_task_focus_ui(page)
    open_statistics(page)
    page.locator("#statistics-source").select_option("session")
    payload = download_statistics(page, "json")
    assert len(payload["records"]) == 1
    record = payload["records"][0]
    assert record["focusItemTitle"] == PROJECT_TITLES[1] and record["category"] == "工作"
    assert record["durationSeconds"] == 10 and record["timerMode"] == "countup" and record["targetMinutes"] == 50
    page.close()
    print("PASS: no task-focus UI, step/parent completion and rollback, task edits/deletion preserve running/paused focus, focus completion leaves todos unchanged.", flush=True)


def assert_sheet_save_reachable(page):
    expect(page.locator("#save-todo-sheet")).to_be_visible()
    assert page.locator("#todo-sheet").evaluate("el => el.scrollWidth <= el.clientWidth")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    assert page.locator("#save-todo-sheet").evaluate("""el => {
        const rect = el.getBoundingClientRect();
        const viewport = window.visualViewport;
        const top = viewport?.offsetTop || 0;
        const height = viewport?.height || innerHeight;
        const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        return rect.top >= top && rect.bottom <= top + height && rect.left >= 0 && rect.right <= innerWidth
            && rect.height >= 44 && (hit === el || el.contains(hit));
    }""")


def check_task_sheet_and_steps(browser, errors):
    page = fresh_timer_page(browser, errors)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    page.locator("#add-task").click()
    expect(page.locator("#todo-sheet")).to_be_visible()
    expect(page.locator("#save-todo-sheet")).to_have_text("添加待办")
    expect(page.locator("#modal")).not_to_be_visible()
    assert not page.locator("#todo-form .step-editor").evaluate("el => el.open")
    expect(page.locator("#todo-form .add-step")).not_to_be_visible()
    title = "八步长标题回归" + "长" * 73
    unsafe_step = '<img src=x onerror="window.stepXss=1">'
    step_titles = [f"第{index + 1}步" + "长" * 37 for index in range(MAX_TASK_STEPS - 1)] + [unsafe_step]
    page.locator('#todo-form input[name="title"]').fill(title)
    expect(page.locator('#todo-form input[name="title"]')).to_have_attribute("maxlength", "80")
    page.locator("#todo-form .category-choice").filter(has_text="工作").click()
    page.locator('#todo-form input[name="important"]').check()
    page.locator("#todo-form .step-editor summary").click()
    for step_title in step_titles:
        page.locator("#todo-form .add-step").click()
        field = page.locator("#todo-form .step-editor-row input").last
        expect(field).to_be_focused()
        expect(field).to_have_attribute("maxlength", "40")
        field.fill(step_title)
    expect(page.locator("#todo-form .add-step")).to_be_disabled()
    page.locator("#todo-form .step-remove").last.click()
    expect(page.locator("#todo-form .step-editor-row")).to_have_count(MAX_TASK_STEPS - 1)
    expect(page.locator("#todo-form .add-step")).to_be_enabled()
    page.locator("#todo-form .add-step").click()
    page.locator("#save-todo-sheet").click()
    expect(page.locator('#todo-sheet [role="alert"]')).to_contain_text("小步名称")
    expect(page.locator("#todo-sheet")).to_be_visible()
    expect(page.locator("#todo-form .step-editor-row")).to_have_count(MAX_TASK_STEPS)
    page.locator("#todo-form .step-editor-row input").last.fill(unsafe_step)
    expect(page.locator('#todo-sheet [role="alert"]')).not_to_be_visible()
    page.locator("#todo-form .step-editor-row input").last.press("Enter")
    expect(page.locator("#todo-sheet")).to_be_visible()
    expect(page.locator("#todo-form .step-editor-row")).to_have_count(MAX_TASK_STEPS)
    page.locator("#close-todo-sheet").click()
    assert_task_counts(page, pending=3, done=1)
    expect(task_card(page, title)).to_have_count(0)

    reading = task_card(page, "阅读《原子习惯》")
    reading.locator(".task-info").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("阅读《原子习惯》")
    expect(page.locator("#todo-form .step-editor-row")).to_have_count(0)
    expect(page.locator('#todo-form input[name="important"]')).not_to_be_checked()
    page.locator('#todo-form input[name="title"]').fill("取消后保留的阅读草稿")
    page.keyboard.press("Escape")
    expect(reading).to_have_count(1)
    planning = task_card(page, "梳理个人 APP 的想法")
    planning.locator(".task-info").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("梳理个人 APP 的想法")
    page.locator("#todo-form .step-editor-row input").first.fill("取消后保留的小步草稿")
    page.mouse.click(4, 4)
    expect(page.locator("#todo-sheet")).not_to_be_visible()
    planning.locator(".step-expand").click()
    expect(planning.locator(".step-row").first).to_have_attribute("aria-label", "写下想解决的问题")
    reading.locator(".task-info").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("取消后保留的阅读草稿")
    page.locator("#save-todo-sheet").click()
    expect(task_card(page, "取消后保留的阅读草稿")).to_have_count(1)
    planning.locator(".task-info").click()
    expect(page.locator("#todo-form .step-editor-row input").first).to_have_value("取消后保留的小步草稿")
    page.locator("#save-todo-sheet").click()
    expect(planning.locator(".step-row").first).to_have_attribute("aria-checked", "true")

    page.locator("#add-task").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value(title)
    expect(page.locator('#todo-form input[name="important"]')).to_be_checked()
    expect(page.locator("#todo-form .category-choice").filter(has_text="工作")).to_have_attribute("aria-pressed", "true")
    assert page.locator("#todo-form .step-editor-row input").evaluate_all("inputs => inputs.map(input => input.value)") == step_titles
    for viewport in ({"width": 320, "height": MOBILE_HEIGHT}, LANDSCAPE_VIEWPORT, {"width": 320, "height": 360}):
        page.set_viewport_size(viewport)
        page.locator("#todo-form .step-editor-row input").last.focus()
        page.locator("#todo-form .step-editor-row input").last.scroll_into_view_if_needed()
        assert_sheet_save_reachable(page)
        page.screenshot(path=str(SCREENSHOT_DIRECTORY / f"todo-sheet-{viewport['width']}x{viewport['height']}.png"))
    page.locator("#save-todo-sheet").click()
    expect(page.locator("#todo-sheet")).not_to_be_visible()
    assert_task_counts(page, pending=4, done=1)
    page.set_viewport_size(DESKTOP_VIEWPORT)
    task = task_card(page, title)
    expect(task.locator(".step-expand")).to_have_attribute("aria-expanded", "false")
    expect(task.locator(".step-list")).not_to_be_visible()
    expect(task.locator("span.step-dot")).to_have_count(MAX_TASK_STEPS)
    expect(task.locator("button.step-dot")).to_have_count(0)
    task.locator(".step-expand").click()
    rows = task.locator('div.step-list button.step-row[role="checkbox"]')
    expect(rows).to_have_count(MAX_TASK_STEPS)
    expect(rows.locator(".step-label")).to_have_text(step_titles)
    assert page.evaluate("window.stepXss === undefined")
    expect(task.locator("img")).to_have_count(0)
    for index in range(MAX_TASK_STEPS):
        row = rows.nth(index)
        expect(row).to_have_attribute("aria-checked", "false")
        box = row.bounding_box()
        assert box and box["height"] >= MIN_TOUCH_TARGET and box["width"] > MIN_TOUCH_TARGET * 2, box
        row.click(position={"x": box["width"] - 8, "y": box["height"] / 2})
        if index < MAX_TASK_STEPS - 1:
            expect(rows.nth(index)).to_have_attribute("aria-checked", "true")
            expect(task.locator(".step-count")).to_have_text(f"小步 {index + 1} / {MAX_TASK_STEPS}")
            expect(page.locator("#todo-sheet")).not_to_be_visible()
        if index == 0:
            rows.first.focus()
            page.keyboard.press("Space")
            expect(rows.first).to_have_attribute("aria-checked", "false")
            expect(task.locator(".step-count")).to_have_text(f"小步 0 / {MAX_TASK_STEPS}")
            page.keyboard.press("Enter")
            expect(rows.first).to_have_attribute("aria-checked", "true")
    expect(task).to_have_count(0)
    expect(page.locator(".celebrating")).to_have_count(0)
    assert_task_counts(page, pending=3, done=2)
    page.locator('[data-filter="done"]').click()
    static_check = task.locator("span.task-check-static")
    expect(static_check).to_be_visible()
    static_check.click()
    expect(task).to_have_count(1)
    assert_task_counts(page, pending=3, done=2)
    expect(task.locator("button.task-checkbox, .step-row")).to_have_count(0)
    completion_label = task.locator(".task-age").inner_text()
    task.locator(".task-info").click()
    expect(page.locator(".task-detail-steps li")).to_have_count(MAX_TASK_STEPS)
    expect(page.locator(".task-detail-steps li").last).to_have_text(f"已走过 · {unsafe_step}")
    expect(page.locator("#modal img")).to_have_count(0)
    page.locator("#modal").get_by_role("button", name="编辑待办", exact=True).click()
    expect(page.locator("#todo-form .add-step, #todo-form .step-remove")).to_have_count(0)
    expect(page.locator("#todo-form .step-editor-row input")).to_have_count(MAX_TASK_STEPS)
    page.locator("#todo-form .step-editor-row input").first.fill("完成后只改名")
    page.locator("#save-todo-sheet").click()
    expect(task.locator("span.task-check-static")).to_be_visible()
    expect(task.locator(".task-age")).to_have_text(completion_label)
    task.locator(".task-info").click()
    page.locator("#modal").get_by_role("button", name="重新打开", exact=True).click()
    expect(page.locator('#modal label input[type="checkbox"]')).to_have_count(MAX_TASK_STEPS)
    page.locator("#modal").get_by_role("button", name="确认重新打开", exact=True).click()
    expect(page.locator('#modal [role="alert"]')).to_be_visible()
    assert_task_counts(page, pending=3, done=2)
    page.locator("#modal").get_by_role("button", name="取消", exact=True).click()
    page.locator("#close-modal").click()
    expect(task.locator("span.task-check-static")).to_be_visible()
    reopen_task(page, title, redo_indices=(0, MAX_TASK_STEPS - 1))
    assert rows.evaluate_all("els => els.map(el => el.getAttribute('aria-checked'))") == ["false"] + ["true"] * (MAX_TASK_STEPS - 2) + ["false"]
    expect(task.locator(".step-count")).to_have_text(f"小步 {MAX_TASK_STEPS - 2} / {MAX_TASK_STEPS}")
    assert_task_counts(page, pending=4, done=1)
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.locator("#add-task").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("")
    expect(page.locator("#todo-form .step-editor-row")).to_have_count(0)
    page.locator("#close-todo-sheet").click()
    page.reload()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    assert_task_counts(page, pending=3, done=1)
    page.locator("#add-task").click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("")
    page.close()
    print("PASS: independent sheet, isolated cancel/Escape/backdrop drafts, eight-step limit/full-row clicks, safe step text, completed read-only edit/partial reopen, 320px/landscape save reachability and refresh reset.", flush=True)


def drag_task(page, title, dx, dy=0):
    card = task_card(page, title)
    card.scroll_into_view_if_needed()
    box = card.bounding_box()
    assert box, title
    start_x = box["x"] + box["width"] / 2
    start_y = box["y"] + 5
    page.mouse.move(start_x, start_y)
    page.mouse.down()
    page.mouse.move(start_x + dx, start_y + dy, steps=12)
    page.mouse.up()


def check_task_swipe_undo(browser, errors):
    page = fresh_timer_page(browser, errors)
    page.set_viewport_size({"width": 320, "height": MOBILE_HEIGHT})
    page.locator('.bottom-nav [data-page="tasks"]').click()
    first_title, second_title = "阅读《原子习惯》", "傍晚出去走一走"
    first = page.locator('.task-swipe[data-task-id="sample-reading"]')
    second = page.locator('.task-swipe[data-task-id="sample-walk"]')
    original_order = page.locator(".task-swipe").evaluate_all("els => els.map(el => el.dataset.taskId)")
    assert first.locator(".task-delete").evaluate("el => el.inert")
    drag_task(page, first_title, 0, MIN_TOUCH_TARGET)
    expect(page.locator(".swipe-open")).to_have_count(0)
    expect(page.locator("#todo-sheet")).not_to_be_visible()
    drag_task(page, first_title, -TASK_SWIPE_WIDTH)
    expect(first).to_have_class(re.compile(r"\bswipe-open\b"))
    expect(first.locator(".task-delete")).to_have_attribute("aria-hidden", "false")
    assert not first.locator(".task-delete").evaluate("el => el.inert")
    assert first.locator(".task-delete").bounding_box()["width"] == TASK_SWIPE_WIDTH
    assert first.locator(".task-card").evaluate("el => new DOMMatrix(getComputedStyle(el).transform).m41") == -TASK_SWIPE_WIDTH
    assert_task_counts(page, pending=3, done=1)
    expect(page.locator("#todo-sheet:visible, #modal:visible")).to_have_count(0)
    drag_task(page, first_title, TASK_SWIPE_WIDTH)
    expect(page.locator(".swipe-open")).to_have_count(0)
    assert_task_counts(page, pending=3, done=1)
    drag_task(page, first_title, -TASK_SWIPE_WIDTH)
    drag_task(page, second_title, -TASK_SWIPE_WIDTH)
    expect(page.locator(".swipe-open")).to_have_count(1)
    expect(second).to_have_class(re.compile(r"\bswipe-open\b"))
    assert first.locator(".task-delete").evaluate("el => el.inert")
    second.locator(".task-delete").focus()
    page.keyboard.press("Escape")
    expect(page.locator(".swipe-open")).to_have_count(0)
    expect(second.locator(".task-info")).to_be_focused()
    first.locator(".task-info").focus()
    page.keyboard.press("ArrowLeft")
    expect(first.locator(".task-delete")).to_be_focused()
    page.keyboard.press("ArrowRight")
    expect(page.locator(".swipe-open")).to_have_count(0)
    drag_task(page, first_title, -TASK_SWIPE_WIDTH)
    first.locator(".task-delete").click()
    expect(first).to_have_count(0)
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator("#task-undo")).to_be_visible()
    expect(page.locator("#undo-task")).to_be_focused()
    assert_task_counts(page, pending=2, done=1)
    second.locator(".task-info").focus()
    page.keyboard.press("ArrowLeft")
    page.keyboard.press("Enter")
    expect(second).to_have_count(0)
    assert_task_counts(page, pending=1, done=1)
    expect(page.locator("#task-undo-message")).to_contain_text("2 次")
    page.locator("#undo-task").click()
    expect(second).to_have_count(1)
    expect(first).to_have_count(0)
    assert_task_counts(page, pending=2, done=1)
    page.locator("#undo-task").click()
    expect(first).to_have_count(1)
    expect(page.locator("#task-undo")).not_to_be_visible()
    assert page.locator(".task-swipe").evaluate_all("els => els.map(el => el.dataset.taskId)") == original_order
    first.locator(".task-menu").click()
    page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
    expect(first).to_have_count(0)
    page.locator("#dismiss-task-undo").click()
    expect(page.locator("#task-undo")).not_to_be_visible()
    second.locator(".task-menu").click()
    page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
    page.locator("#undo-task").click()
    expect(second).to_have_count(1)
    expect(first).to_have_count(0)
    expect(page.locator("#task-undo")).not_to_be_visible()
    planning = task_card(page, "梳理个人 APP 的想法")
    planning.get_by_role("button", name="放下「梳理个人 APP 的想法」", exact=True).click()
    assert_task_counts(page, pending=1, done=1, archived=1)
    page.locator('[data-filter="archived"]').click()
    expect(page.locator("#task-summary")).to_have_count(0)
    expect(planning.locator("span.task-check-static")).to_be_visible()
    planning.locator(".task-info").click()
    page.locator("#modal").get_by_role("button", name="找回", exact=True).click()
    assert_task_counts(page, pending=2, done=1)
    expect(planning.locator(".task-age")).to_have_text("今天放进来的")
    expect(page.locator('[data-filter="pending"]')).to_have_attribute("aria-pressed", "true")
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    page.close()
    print("PASS: real mouse pointer swipe, vertical rejection, left/right non-destructive reveal, one-open policy, keyboard delete/Escape, immediate delete, multi-undo/order, dismiss clears stack, archive/restore.", flush=True)


def check_task_touch_delete(browser, errors):
    context = browser.new_context(viewport={"width": 390, "height": MOBILE_HEIGHT},
                                  is_mobile=True, has_touch=True, reduced_motion="reduce", timezone_id="Asia/Shanghai")
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    cdp = context.new_cdp_session(page)

    def touch_drag(x, y, dx=0, dy=0, cancel=False):
        cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x, "y": y, "id": 1}]})
        if dx or dy:
            for index in range(1, TOUCH_MOVE_STEPS + 1):
                cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [
                    {"x": x + dx * index / TOUCH_MOVE_STEPS, "y": y + dy * index / TOUCH_MOVE_STEPS, "id": 1},
                ]})
        cdp.send("Input.dispatchTouchEvent", {"type": "touchCancel" if cancel else "touchEnd", "touchPoints": []})

    try:
        page.goto(BASE_URL)
        page.locator('.bottom-nav [data-page="tasks"]').tap()
        assert_task_counts(page, pending=3, done=1)
        first = page.locator('.task-swipe[data-task-id="sample-planning"]')
        other_checks = page.locator('.task-swipe:not([data-task-id="sample-planning"]) .task-checkbox')
        expect(other_checks).to_have_count(2)
        first.locator(".step-expand").tap()
        expect(first.locator(".step-expand")).to_have_attribute("aria-expanded", "true")
        expected_steps = ["true", "true", "false"]
        assert first.locator(".step-row").evaluate_all("rows => rows.map(row => row.getAttribute('aria-checked'))") == expected_steps
        page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-touch-expanded.png"))
        box = first.locator(".task-card").bounding_box()
        assert box
        touch_drag(box["x"] + box["width"] * 0.8, box["y"] + 20, dx=-TASK_SWIPE_WIDTH * 1.25)
        expect(first).to_have_class(re.compile(r"\bswipe-open\b"))
        assert_task_counts(page, pending=3, done=1)
        first.locator(".task-delete").tap()
        expect(first).to_have_count(0)
        assert_task_counts(page, pending=2, done=1)
        expect(page.locator("#modal:visible, #todo-sheet:visible")).to_have_count(0)
        assert other_checks.evaluate_all("els => els.map(el => el.getAttribute('aria-pressed'))") == ["false", "false"]
        page.locator("#undo-task").tap()
        expect(first).to_have_count(1)
        assert_task_counts(page, pending=3, done=1)
        expect(page.locator("#task-undo")).not_to_be_visible()
        assert first.locator(".step-row").evaluate_all("rows => rows.map(row => row.getAttribute('aria-checked'))") == expected_steps

        first.locator(".task-card").scroll_into_view_if_needed()
        box = first.locator(".task-card").bounding_box()
        assert box
        touch_drag(box["x"] + box["width"] * 0.8, box["y"] + 20, dx=-TASK_SWIPE_WIDTH * 1.25)
        expect(first).to_have_class(re.compile(r"\bswipe-open\b"))
        expect(page.locator(".swipe-open")).to_have_count(1)
        assert first.locator(".task-delete").bounding_box()["width"] == TASK_SWIPE_WIDTH
        page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-touch-swipe.png"))
        for dy, cancel in ((TASK_TOUCH_DRAG, False), (-TASK_TOUCH_DRAG, False), (0, True)):
            remove = first.locator(".task-delete")
            remove.scroll_into_view_if_needed()
            box = remove.bounding_box()
            assert box
            touch_drag(box["x"] + box["width"] / 2, box["y"] + box["height"] / 2, dy=dy, cancel=cancel)
            expect(first).to_have_count(1)
            assert_task_counts(page, pending=3, done=1)
            expect(page.locator("#task-undo")).not_to_be_visible()
            expect(first).to_have_class(re.compile(r"\bswipe-open\b"))
        assert other_checks.evaluate_all("els => els.map(el => el.getAttribute('aria-pressed'))") == ["false", "false"]
        assert first.locator(".step-row").evaluate_all("rows => rows.map(row => row.getAttribute('aria-checked'))") == expected_steps
        page.locator("#add-task").tap()
        expect(page.locator("#todo-sheet")).to_be_visible()
        expect(page.locator('#todo-form input[name="title"]')).to_have_value("")
        assert not page.locator("#todo-form .step-editor").evaluate("el => el.open")
        assert_sheet_save_reachable(page)
        page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-touch-editor-initial.png"))
    finally:
        context.close()
    print("PASS: mobile CDP touch swipe immediately followed by delete tap, undo, vertical/cancel rejection and unchanged neighboring cards/steps.", flush=True)


def check_task_history(browser, errors):
    page = browser.new_page(viewport=DESKTOP_VIEWPORT, reduced_motion="reduce", timezone_id="Asia/Shanghai")
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.clock.install(time=datetime(2026, 9, 26, 8, 59, 39, tzinfo=timezone.utc))
    page.clock.pause_at(datetime(2026, 9, 26, 8, 59, 40, tzinfo=timezone.utc))
    dated_count = TASK_HISTORY_PAGE_SIZE + 3
    older_day_count = 15
    # UTC September 25 at 16:00 belongs to local September 26, not yesterday.
    tasks = [{"id": f"history-{index}", "title": f"完成记录 {index:02d}",
              "category": "工作" if index % 2 == 0 else "生活", "important": False,
              "done": True, "archived": False, "createdAt": "2026-08-01",
              "completedAt": f"2026-09-24T08:00:{index:02d}Z" if index < older_day_count else f"2026-09-25T16:00:{index:02d}Z",
              "steps": [{"id": f"history-step-{index}", "title": f"回查小步 {index:02d}", "done": True}]}
             for index in range(dated_count)]
    unknown_titles = ["缺少完成时间", "非法完成时间", "未来完成时间"]
    for key, title, completed_at in (("yesterday", "昨天完成", "2026-09-25T15:59:59Z"),
                                      ("boundary", "月界完成", "2026-08-31T16:00:00Z"),
                                      ("month", "上月完成", "2026-08-18T08:00:00Z"),
                                      ("year", "去年完成", "2025-12-18T08:00:00Z"),
                                      ("unknown", unknown_titles[0], None),
                                      ("invalid", unknown_titles[1], "not-a-date"),
                                      ("future", unknown_titles[2], "2026-10-01T00:00:00Z")):
        tasks.append({"id": key, "title": title, "category": "个人成长", "important": True,
                      "done": True, "archived": False, "createdAt": "2026-08-01",
                      "completedAt": completed_at, "steps": []})
    # A legacy record really omits completedAt instead of receiving a made-up date.
    tasks[next(index for index, task in enumerate(tasks) if task["id"] == "unknown")].pop("completedAt")
    tasks.extend([
        {"id": "pending-fixture", "title": "工作中的待办", "category": "工作", "important": False,
         "done": False, "archived": False, "createdAt": "2026-09-26", "completedAt": None, "steps": []},
        {"id": "archived-fixture", "title": "放下的完成记录", "category": "工作", "important": False,
         "done": True, "archived": True, "createdAt": "2026-09-26", "completedAt": "2026-09-26T08:00:00Z", "steps": []},
    ])
    month_count = dated_count + 2
    done_count = month_count + 2 + len(unknown_titles)
    month_titles = ([f"完成记录 {index:02d}" for index in reversed(range(older_day_count, dated_count))]
                    + ["昨天完成"] + [f"完成记录 {index:02d}" for index in reversed(range(older_day_count))]
                    + ["月界完成"])

    def seed_history(route):
        response = route.fetch()
        prefix = '"use strict";\nwindow.FocusModel = {...window.FocusModel, initialTasks: () => ' + json.dumps(tasks, ensure_ascii=False) + '};\n'
        route.fulfill(response=response, body=prefix + response.text())

    def assert_page(shown, total):
        expect(page.locator("#task-list .task-card")).to_have_count(shown)
        expect(page.locator("#task-history-count")).to_have_text(f"已显示 {shown} / {total} 件")
        expect(page.locator("#task-list .history-more")).to_have_count(0)
        if shown < total:
            expect(page.locator("#task-history-more")).to_be_visible()
        else:
            expect(page.locator("#task-history-more")).not_to_be_visible()

    def assert_static_headings():
        assert page.locator(".history-heading").evaluate_all("""headings => headings.every(el =>
            el.tagName === 'DIV' && !el.hasAttribute('aria-expanded') && !el.hasAttribute('aria-controls') &&
            !el.hasAttribute('tabindex') && el.getAttribute('role') !== 'button' && !el.querySelector('button'))""")
        expect(page.locator("#task-list .step-expand, #task-list button.step-row, #task-list button.task-checkbox")).to_have_count(0)

    def set_position():
        position = page.locator("#phone-content").evaluate("""el => {
            el.scrollTop = Math.min(500, (el.scrollHeight - el.clientHeight) / 2);
            el.dispatchEvent(new Event('scroll'));
            return el.scrollTop;
        }""")
        assert position > 0
        return position

    def assert_position(position):
        page.wait_for_function("position => Math.abs(document.getElementById('phone-content').scrollTop - position) <= 1", arg=position)

    def search_without_scrolling(query):
        # Isolate application scroll restoration from Playwright's input auto-scroll.
        page.locator("#task-search").evaluate("""(el, query) => {
            el.value = query; el.dispatchEvent(new Event('input', {bubbles: true}));
        }""", query)

    page.route("**/app.js", seed_history)
    page.goto(BASE_URL)
    page.locator('.bottom-nav [data-page="tasks"]').click()
    assert_task_counts(page, pending=1, done=done_count, archived=1)
    page.locator('[data-filter="done"]').click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")
    expect(page.locator("#task-month-next")).to_be_disabled()
    expect(page.locator("#task-month-current")).not_to_be_visible()
    expect(page.locator("#task-history-summary")).to_have_text(f"2026年9月 · 完成 {month_count} 件")
    assert_page(TASK_HISTORY_PAGE_SIZE, month_count)
    expect(page.locator("#task-list .task-title")).to_have_text(month_titles[:TASK_HISTORY_PAGE_SIZE])
    assert group_labels(page) == ["今天", "昨天", "9月24日"]
    assert_static_headings()
    before = page.locator("#task-list .task-title").all_inner_texts()
    page.locator(".history-heading").first.click()
    assert page.locator("#task-list .task-title").all_inner_texts() == before
    expect(page.locator(".task-group-list[hidden]")).to_have_count(0)
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-history-month.png"))
    page.locator("#task-history-more").click()
    assert_page(month_count, month_count)
    expect(page.locator("#task-list .task-title")).to_have_text(month_titles)
    expect(page.locator("#task-history-summary")).to_have_text(f"2026年9月 · 完成 {month_count} 件")
    assert_task_counts(page, pending=1, done=done_count, archived=1)
    assert group_labels(page) == ["今天", "昨天", "9月24日", "9月1日"]
    # The second page extends the existing September 24 separator, never duplicates it.
    expect(page.locator("#todo-group-2026-09-24")).to_have_count(1)
    expect(page.locator("#todo-group-2026-09-24 .task-card")).to_have_count(older_day_count)
    expect(task_card(page, "月界完成").locator(".task-age")).to_have_text("2026-09-01 完成")

    position = set_position()
    visible_info = page.locator("#task-list .task-info").evaluate_all("""els => {
        const box = document.getElementById('phone-content').getBoundingClientRect();
        return els.findIndex(el => { const r = el.getBoundingClientRect(); return r.top >= box.top && r.bottom <= box.bottom; });
    }""")
    assert visible_info >= 0
    page.locator("#task-list .task-info").nth(visible_info).click()
    assert_position(position)
    page.locator("#close-modal").click()
    assert_position(position)
    # Offscreen tab activation must not introduce automation's own scroll-to-click.
    page.locator('[data-filter="pending"]').evaluate("el => el.click()")
    page.locator('[data-filter="done"]').evaluate("el => el.click()")
    assert_page(month_count, month_count)
    assert_position(position)
    page.locator('.bottom-nav [data-page="focus"]').click()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    assert_page(month_count, month_count)
    assert_position(position)

    search_without_scrolling("上月完成")
    expect(page.locator("#task-month-nav")).not_to_be_visible()
    expect(page.locator("#task-history-summary")).to_contain_text("全部时间")
    expect(page.locator("#task-list .task-title")).to_have_text(["上月完成"])
    search_without_scrolling("")
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")
    assert_page(month_count, month_count)
    assert_position(position)

    page.locator("#task-month-prev").click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 8 月")
    expect(page.locator("#task-history-summary")).to_have_text("2026年8月 · 完成 1 件")
    assert_task_counts(page, pending=1, done=done_count, archived=1)
    expect(page.locator("#task-month-next")).to_be_enabled()
    expect(page.locator("#task-list .task-title")).to_have_text(["上月完成"])
    page.locator("#task-month-next").click()
    assert_page(month_count, month_count)
    page.locator("#task-month-picker").click()
    expect(page.locator('#modal select[name="historyYear"]')).to_have_value("2026")
    expect(page.locator('#modal button.month-choice')).to_have_count(12)
    for month in ("2026-10", "2026-11", "2026-12"):
        expect(page.locator(f'#modal button.month-choice[data-month="{month}"]')).to_be_disabled()
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-history-month-picker.png"))
    page.locator('#modal select[name="historyYear"]').select_option("2025")
    page.locator('#modal button.month-choice[data-month="2025-12"]').click()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator("#task-month-label")).to_have_text("2025 年 12 月")
    expect(page.locator("#task-list .task-title")).to_have_text(["去年完成"])
    page.locator("#task-month-current").click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")
    expect(page.locator("#task-month-next")).to_be_disabled()
    assert_page(month_count, month_count)
    page.locator("#task-month-prev").click()
    page.locator("#task-month-prev").click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 7 月")
    expect(page.locator("#task-history-summary")).to_have_text("2026年7月 · 完成 0 件")
    assert_task_counts(page, pending=1, done=done_count, archived=1)
    expect(page.locator("#task-list .empty-state")).to_contain_text("这一月，还没有完成记录")
    expect(page.locator("#task-history-footer")).not_to_be_visible()
    page.locator("button.history-latest").click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")

    page.locator("#task-undated").click()
    expect(page.locator("#task-month-nav")).not_to_be_visible()
    expect(page.locator("#task-list .task-title")).to_have_text(unknown_titles)
    assert group_labels(page) == ["完成时间未记录"]
    for title in unknown_titles:
        unknown = task_card(page, title)
        expect(unknown.locator(".task-age")).to_have_text("完成时间未记录")
        unknown.locator(".task-info").click()
        expect(page.locator("#modal .task-detail")).to_contain_text("这条历史没有记录完成时间")
        expect(page.locator("#modal .task-detail")).not_to_contain_text("完成于")
        page.locator("#close-modal").click()
    page.locator("#task-known-history").click()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")
    assert_page(month_count, month_count)

    for keyword, expected_titles in (("完成记录 00", ["完成记录 00"]),
                                     ("回查小步 02", ["完成记录 02"]),
                                     ("工作", [f"完成记录 {index:02d}" for index in reversed(range(dated_count)) if index % 2 == 0]),
                                     ("去年完成", ["去年完成"]),
                                     ("完成时间", unknown_titles),
                                     ("不存在的完成记录", [])):
        page.locator("#task-search").fill(keyword)
        expect(page.locator("#task-list .task-title")).to_have_text(expected_titles)
        expect(page.locator("#task-month-nav")).not_to_be_visible()
        expect(page.locator("#task-history-summary")).to_have_text(f"全部时间 · 找到 {len(expected_titles)} 件")
        assert_static_headings()
        if not expected_titles:
            expect(page.locator("#task-list .empty-state")).to_contain_text("没有找到这件事")
        assert_task_counts(page, pending=1, done=done_count, archived=1)

    page.locator("#task-search").fill("")
    page.locator("#task-month-prev").click()
    page.locator("#task-search").fill("完成")
    assert_page(TASK_HISTORY_PAGE_SIZE, done_count)
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-history-all-time-search.png"))
    page.locator("#task-history-more").click()
    assert_page(done_count, done_count)
    expect(page.locator("#task-list .task-title")).to_have_text(month_titles + ["上月完成", "去年完成"] + unknown_titles)
    assert group_labels(page) == ["今天", "昨天", "2026年9月24日", "2026年9月1日", "2026年8月18日", "2025年12月18日", "完成时间未记录"]
    deleted = task_card(page, "完成记录 00")
    deleted.locator(".task-menu").click()
    page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
    expect(deleted).to_have_count(0)
    expect(page.locator("#task-search")).to_have_value("完成")
    expect(page.locator("#task-month-label")).to_have_text("2026 年 8 月")
    assert_page(done_count - 1, done_count - 1)
    assert_task_counts(page, pending=1, done=done_count - 1, archived=1)
    page.locator("#undo-task").click()
    expect(deleted.locator("span.task-check-static")).to_have_count(1)
    expect(page.locator("#task-search")).to_have_value("完成")
    expect(page.locator("#task-month-nav")).not_to_be_visible()
    expect(page.locator("#task-month-label")).to_have_text("2026 年 8 月")
    assert_page(done_count, done_count)
    assert_task_counts(page, pending=1, done=done_count, archived=1)
    search_without_scrolling("")
    expect(page.locator("#task-month-label")).to_have_text("2026 年 8 月")
    expect(page.locator("#task-list .task-title")).to_have_text(["上月完成"])
    page.locator("#task-month-current").click()
    assert_page(month_count, month_count)
    deleted.locator(".task-menu").click()
    page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
    assert_page(month_count - 1, month_count - 1)
    page.locator("#undo-task").click()
    expect(page.locator("#task-search")).to_have_value("")
    expect(page.locator("#task-month-label")).to_have_text("2026 年 9 月")
    assert_page(month_count, month_count)
    search_without_scrolling("回查小步")
    page.locator("#task-history-more").click()
    assert_page(dated_count, dated_count)
    position = set_position()
    visible_info = page.locator("#task-list .task-info").evaluate_all("""els => {
        const box = document.getElementById('phone-content').getBoundingClientRect();
        return els.findIndex(el => { const r = el.getBoundingClientRect(); return r.top >= box.top && r.bottom <= box.bottom; });
    }""")
    page.locator("#task-list .task-info").nth(visible_info).click()
    page.locator("#modal").get_by_role("button", name="重新打开", exact=True).click()
    page.locator('#modal label input[type="checkbox"]').first.check()
    page.locator("#modal").get_by_role("button", name="确认重新打开", exact=True).click()
    expect(page.locator('[data-filter="pending"]')).to_have_attribute("aria-pressed", "true")
    page.locator("#undo-task").click()
    expect(page.locator("#task-search")).to_have_value("回查小步")
    expect(page.locator("#task-month-nav")).not_to_be_visible()
    assert_page(dated_count, dated_count)
    assert_position(position)
    page.close()
    print("PASS: local-date monthly history, global 20-item pagination, static day separators, picker/future guard, empty/unknown history, all-time search, scroll/view restoration and deletion/reopen undo.", flush=True)


def check_task_completion_motion(browser, errors):
    page = fresh_timer_page(browser, errors)
    page.emulate_media(reduced_motion="no-preference")
    page.locator('.bottom-nav [data-page="tasks"]').click()
    # Observe the real model result without replacing completion behavior or reaching into app state.
    page.evaluate("""() => {
        const model = window.FocusModel;
        const observe = method => (...args) => {
            const result = model[method](...args);
            window.__lastCompletedTask = JSON.parse(JSON.stringify(result));
            return result;
        };
        window.FocusModel = {...model, completeTask: observe('completeTask'), setTaskStep: observe('setTaskStep')};
    }""")
    planning = page.locator('.task-swipe[data-task-id="sample-planning"]')
    # During feedback .task-title is deliberately replaced, so use stable task IDs.
    task = planning.locator(".task-card")

    def assert_committed():
        result = page.evaluate("() => ({task: window.__lastCompletedTask, now: new Date().toISOString()})")
        assert result["task"]["done"] is True
        assert result["task"]["completedAt"] == result["now"], result
        assert all(step["done"] for step in result["task"]["steps"])
        expect(page.locator("#task-undo-message")).to_have_attribute("role", "status")
        expect(page.locator("#task-undo-message")).to_contain_text("已完成")
        expect(page.locator("#task-undo-message")).to_contain_text(result["task"]["title"])
        return result["task"]

    def assert_feedback(wrapper):
        expect(wrapper).to_have_class(re.compile(r"\bfinishing\b"))
        expect(wrapper).not_to_have_class(re.compile(r"\bfinishing-out\b"))
        card = wrapper.locator(".task-card")
        expect(card).to_have_class(re.compile(r"\bcelebrating\b"))
        note = card.locator('div.task-finish-note[role="status"]')
        expect(note).to_be_visible()
        expect(note.locator(".task-finish-mark svg.icon")).to_have_count(1)
        expect(note.locator("strong")).to_have_text("又走完一件事")
        expect(note.locator("small")).to_contain_text("已收进完成记录")
        assert wrapper.evaluate("el => el.inert") and card.evaluate("el => el.inert")
        animation = note.locator(".task-finish-mark svg.icon").evaluate("el => getComputedStyle(el).animationName")
        assert "todo-finish-check" in animation, animation
        assert wrapper.evaluate("el => getComputedStyle(el).transitionProperty.split(',').map(value => value.trim()).includes('height')")
        assert float(wrapper.evaluate("el => el.style.getPropertyValue('--finish-height')").removesuffix("px")) > 0

    task.locator(".step-expand").click()
    task.locator(".step-row").last.click()
    assert_task_counts(page, pending=2, done=2)
    committed = assert_committed()
    assert_feedback(planning)
    assert page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--todo-finish-hold').trim()") == f"{TASK_FINISH_HOLD_MS}ms"
    assert page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--todo-finish-collapse').trim()") == f"{TASK_FINISH_COLLAPSE_MS}ms"
    # Fake timers do not guarantee interpolated CSS frames; verify the collapse rule, not pixels.
    assert page.evaluate("""() => [...document.styleSheets].flatMap(sheet => [...sheet.cssRules]).some(rule =>
        rule.selectorText === '.task-swipe.finishing-out' && rule.style.height === '0px')""")
    page.locator('[data-filter="done"]').click()
    expect(task.locator("span.task-check-static")).to_be_visible()
    expect(planning).not_to_have_class(re.compile(r"\bfinishing\b"))
    expect(task).not_to_have_class(re.compile(r"\bcelebrating\b"))
    expect(task.locator(".task-finish-note, .step-path, .step-expand, button.step-row, button.task-checkbox")).to_have_count(0)
    expect(task.locator(".task-age")).to_have_text("2026-09-26 完成")
    task.locator(".task-info").click()
    expect(page.locator("#modal .task-detail")).to_contain_text("完成于")
    expect(page.locator("#modal .task-detail-steps li")).to_have_count(len(committed["steps"]))
    expect(page.locator("#modal .task-detail-steps button")).to_have_count(0)
    page.locator("#close-modal").click()
    page.locator('[data-filter="pending"]').click()
    assert_feedback(planning)
    # run_for paints the feedback screenshot; account for that time at the exact boundaries below.
    page.clock.run_for(TASK_FINISH_STAGGER_MS)
    page.screenshot(path=str(SCREENSHOT_DIRECTORY / "todo-completion-feedback.png"))
    page.clock.fast_forward(TASK_FINISH_HOLD_MS - TASK_FINISH_STAGGER_MS - 1)
    assert_feedback(planning)
    page.clock.fast_forward(1)
    expect(planning).to_have_class(re.compile(r"\bfinishing-out\b"))
    page.clock.fast_forward(TASK_FINISH_COLLAPSE_MS - 1)
    expect(planning).to_have_count(1)
    page.clock.fast_forward(1)
    expect(planning).to_have_count(0)
    page.locator("#undo-task").click()
    assert_task_counts(page, pending=3, done=1)
    expect(task.locator(".step-row").last).to_have_attribute("aria-checked", "false")

    # Complete -> undo at 500ms -> complete again. Neither old hold nor old exit may affect the new run.
    task.locator(".task-checkbox").click()
    assert_committed()
    page.clock.fast_forward(TASK_FINISH_UNDO_MS)
    page.locator("#undo-task").click()
    expect(planning).not_to_have_class(re.compile(r"\bfinishing\b"))
    expect(task.locator(".step-row").last).to_have_attribute("aria-checked", "false")
    task.locator(".task-checkbox").click()
    assert_committed()
    page.clock.fast_forward(TASK_FINISH_HOLD_MS - TASK_FINISH_UNDO_MS)
    assert_feedback(planning)
    page.clock.fast_forward(TASK_FINISH_COLLAPSE_MS)
    assert_feedback(planning)  # The original 920ms deadline has now passed.
    page.clock.fast_forward(TASK_FINISH_UNDO_MS - TASK_FINISH_COLLAPSE_MS - 1)
    assert_feedback(planning)
    page.clock.fast_forward(1)
    expect(planning).to_have_class(re.compile(r"\bfinishing-out\b"))
    page.clock.fast_forward(TASK_FINISH_COLLAPSE_MS - 1)
    expect(planning).to_have_count(1)
    page.clock.fast_forward(1)
    expect(planning).to_have_count(0)
    page.locator("#undo-task").click()
    assert_task_counts(page, pending=3, done=1)

    plain_wrappers = []
    for title in ("无小步反馈甲", "无小步反馈乙"):
        card = create_task(page, title)
        task_id = card.evaluate("el => el.closest('.task-swipe').dataset.taskId")
        plain_wrappers.append(page.locator(f'.task-swipe[data-task-id="{task_id}"]'))
    first, second = plain_wrappers
    first.locator("button.task-checkbox").click()
    assert_committed()
    assert_feedback(first)
    page.clock.fast_forward(TASK_FINISH_STAGGER_MS)
    second.locator("button.task-checkbox").click()
    assert_committed()
    assert_feedback(second)
    assert_task_counts(page, pending=3, done=3)
    expect(page.locator(".task-swipe.finishing")).to_have_count(2)
    page.clock.fast_forward(TASK_FINISH_HOLD_MS - TASK_FINISH_STAGGER_MS)
    expect(first).to_have_class(re.compile(r"\bfinishing-out\b"))
    assert_feedback(second)
    page.clock.fast_forward(TASK_FINISH_STAGGER_MS)
    expect(second).to_have_class(re.compile(r"\bfinishing-out\b"))
    page.clock.fast_forward(TASK_FINISH_COLLAPSE_MS - TASK_FINISH_STAGGER_MS)
    expect(first).to_have_count(0)
    expect(second).to_have_count(1)
    page.clock.fast_forward(TASK_FINISH_STAGGER_MS - 1)
    expect(second).to_have_count(1)
    page.clock.fast_forward(1)
    expect(second).to_have_count(0)
    page.locator("#undo-task").click()
    page.locator("#undo-task").click()
    assert_task_counts(page, pending=5, done=1)

    first.locator("button.task-checkbox").click()
    page.clock.fast_forward(TASK_FINISH_HOLD_MS)
    expect(first).to_have_class(re.compile(r"\bfinishing-out\b"))
    first.evaluate("el => { window.__collapsingTask = el; }")
    second.locator("button.task-checkbox").click()
    assert first.evaluate("el => el === window.__collapsingTask")
    expect(first).to_have_class(re.compile(r"\bfinishing-out\b"))
    page.clock.fast_forward(TASK_FINISH_MS)
    expect(first).to_have_count(0)
    expect(second).to_have_count(0)
    page.locator("#undo-task").click()
    page.locator("#undo-task").click()
    assert_task_counts(page, pending=5, done=1)

    # Switching the OS preference while one task collapses and another holds settles both immediately.
    first.locator("button.task-checkbox").click()
    page.clock.fast_forward(TASK_FINISH_STAGGER_MS)
    task.locator(".step-row").last.click()
    page.clock.fast_forward(TASK_FINISH_HOLD_MS - TASK_FINISH_STAGGER_MS)
    expect(first).to_have_class(re.compile(r"\bfinishing-out\b"))
    assert_feedback(planning)
    page.emulate_media(reduced_motion="reduce")
    expect(page.locator(".finishing, .finishing-out, .celebrating, .task-finish-note")).to_have_count(0)
    expect(first).to_have_count(0)
    expect(planning).to_have_count(0)
    assert_task_counts(page, pending=3, done=3)
    page.locator("#undo-task").click()
    page.locator("#undo-task").click()
    page.clock.fast_forward(TASK_FINISH_MS)
    expect(first.locator("button.task-checkbox")).to_have_count(1)
    expect(task.locator(".step-row").last).to_have_attribute("aria-checked", "false")
    assert_task_counts(page, pending=5, done=1)
    first.locator("button.task-checkbox").click()
    assert_committed()
    expect(first).to_have_count(0)
    task.locator(".step-row").last.click()
    assert_committed()
    expect(planning).to_have_count(0)
    expect(page.locator(".finishing, .celebrating, .task-finish-note")).to_have_count(0)
    assert_task_counts(page, pending=3, done=3)
    page.locator('[data-filter="done"]').click()
    expect(first.locator("span.task-check-static")).to_have_count(1)
    expect(task.locator("span.task-check-static")).to_have_count(1)
    expect(page.locator("#task-list .step-expand, #task-list button.step-row")).to_have_count(0)
    page.close()
    print("PASS: immediate completedAt and live undo status, 680ms feedback + 240ms collapse, compact history during animation, 500ms undo/re-complete isolation, independent tasks and dynamic reduced motion.", flush=True)


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
    page.locator('#modal input[name="title"]').fill(title)
    page.locator('select[name="category"]').select_option("生活")
    page.locator('select[name="timerMode"]').select_option("倒计时")
    page.locator('input[name="durationMinutes"]').fill("2")
    page.get_by_role("button", name="添加项目", exact=True).click()
    assert_task_counts(page, pending=3, done=1)
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
    choose_focus_item(page, PROJECT_TITLES[1])
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator("#timer-time")).to_have_text("00:05")
    expect(page.locator("#focus-item-name")).to_have_text(title)
    choose_focus_item(page, PROJECT_TITLES[1])
    page.get_by_role("button", name="放弃并继续", exact=True).click()
    expect(page.locator("#page-timer")).to_be_visible()
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[1])
    expect(page.locator("#focus-item-description")).to_contain_text("目标 50 分钟")
    choose_focus_item(page, title)
    expect(page.locator("#focus-item-description")).to_contain_text("目标 2 分钟")
    expect(page.locator("#focus-sessions")).to_have_text("3")
    page.locator('[data-timing-mode="countdown"]').click()
    expect(page.locator("#timer-time")).to_have_text("02:00")
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(2 * ONE_MINUTE_MS)
    expect(page.locator("#timer-time")).to_have_text("00:00")
    expect(page.locator("#focus-sessions")).to_have_text("4")
    skip_progress(page)
    open_statistics(page)
    page.locator("#statistics-source").select_option("session")
    expect(page.locator("#statistics-count")).to_have_text("1")
    expect(page.locator("#statistics-minutes")).to_have_text("2")
    expect(page.locator("#statistics-records .record-heading strong")).to_have_text([title])
    expect(page.locator("#statistics-ranking .rank-heading > span")).to_have_text([title])
    expect(page.locator("#statistics-records")).not_to_contain_text("关联待办")
    assert_hour_distribution(page)
    payload = download_statistics(page, "json")
    record = payload["records"][0]
    assert len(payload["records"]) == 1
    assert record["focusItemTitle"] == title
    assert record["timerMode"] == "countdown" and record["targetMinutes"] == 2
    assert record["durationSeconds"] == 120 and record["durationMinutes"] == 2
    assert record["category"] == "生活"
    rows = download_statistics(page, "csv")
    assert len(rows) == 1
    assert rows[0]["专注项"] == "'" + title
    assert rows[0]["计时模式"] == "倒计时"
    assert rows[0]["目标时长（分钟）"] == "2" and rows[0]["时长（秒）"] == "120"
    page.close()
    print("PASS: default/custom focus configuration, rest-only preferences, reset/item/mode cancellation and discard, independent focus records and CSV escaping.", flush=True)


def check_cancelled_settings_drafts(browser, errors):
    page = fresh_timer_page(browser, errors)
    choose_focus_item(page, PROJECT_TITLES[0])
    page.locator("#timer-toggle").click()
    page.clock.fast_forward(2_000)
    page.locator("#timer-settings").click()
    page.locator('#modal input[name="title"]').fill("尚未保存的项目名称")
    page.locator('input[name="durationMinutes"]').fill("17")
    page.get_by_role("button", name="保存专注项", exact=True).click()
    page.get_by_role("button", name="取消", exact=True).click()
    expect(page.locator('#modal input[name="title"]')).to_have_value("尚未保存的项目名称")
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
    assert_no_task_focus_ui(page)
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
    assert any(row["学习进度"] == "'" + unsafe_note and row["完成度（%）"] == "" and row["进度更新时间"] for row in rows), rows
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
    page.locator('#todo-form input[name="title"]').fill("尚未保存的任务编辑")
    sheet_title = page.locator("#todo-sheet-title").inner_text()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("4")
    expect(page.locator("#todo-sheet-title")).to_have_text(sheet_title)
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("尚未保存的任务编辑")
    expect(page.locator("#todo-sheet")).to_be_visible()
    expect(page.locator("#modal")).not_to_be_visible()
    expect(page.locator(".progress-form")).to_have_count(0)
    page.locator("#close-todo-sheet").click()
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-1")
    page.locator('textarea[name="note"]').fill("关闭按钮保留的草稿")
    skip_progress(page, return_to_timer=False, method="close")
    card = expand_project(page, PROJECT_TITLES[0])
    card.locator('.project-pending-button[data-record-id="session-1"]').click()
    expect(page.locator('textarea[name="note"]')).to_have_value("关闭按钮保留的草稿")
    save_progress(page, "手动进度优先于未来示例", 0)
    expect(card.locator(".project-progress-note")).to_have_text("手动进度优先于未来示例")
    page.locator('.bottom-nav [data-page="tasks"]').click()
    page.locator(".task-info").first.click()
    expect(page.locator('#todo-form input[name="title"]')).to_have_value("尚未保存的任务编辑")
    page.keyboard.press("Escape")
    expect(page.locator("#todo-sheet")).not_to_be_visible()
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
    choose_focus_item(page, PROJECT_TITLES[0])
    page.locator("#timer-toggle").click()
    page.locator('.bottom-nav [data-page="tasks"]').click()
    page.locator("#task-help").click()
    page.clock.fast_forward(ONE_MINUTE_MS)
    expect(page.locator("#focus-sessions")).to_have_text("7")
    expect(page.locator("#modal-title")).to_have_text("待办怎么用")
    expect(page.locator("#modal")).to_have_class("todo-guide-sheet")
    expect(page.locator(".todo-guide-content section")).to_have_count(4)
    expect(page.locator(".progress-form")).to_have_count(0)
    page.get_by_role("button", name="知道了", exact=True).click()
    expect(page.locator(".progress-form")).to_have_attribute("data-record-id", "session-4")
    expect(page.locator("#modal")).not_to_have_class(re.compile("todo-guide-sheet"))
    expect(page.locator("#modal .modal-header .eyebrow")).to_be_visible()
    skip_progress(page, return_to_timer=False)
    page.close()
    print("PASS: completion queues behind task editor and guide without replacing content; visibility pumps queue; global close/Escape preserve counts and drafts; guide variant resets before progress; manual beats future sample progress.", flush=True)


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
        page.locator('#todo-form input[name="title"]').fill("   ")
        page.locator("#save-todo-sheet").click()
        expect(page.locator("#todo-sheet [role='alert']")).to_contain_text("任务名称")
        expect(page.locator("#todo-sheet")).to_be_visible()
        unsafe_title = '<img src=x onerror="window.injected=true">'
        page.locator('#todo-form input[name="title"]').fill(unsafe_title)
        page.locator("#save-todo-sheet").click()
        assert_task_counts(page, pending=4, done=1)
        added = task_card(page, unsafe_title)
        expect(added).to_have_count(1)
        assert page.evaluate("window.injected === undefined")
        assert added.locator("img").count() == 0
        added.locator(".task-checkbox").click()
        assert_task_counts(page, pending=3, done=2)
        page.locator('[data-filter="done"]').click()
        expect(page.locator(".task-card")).to_have_count(2)
        added.locator(".task-info").click()
        page.locator("#modal").get_by_role("button", name="编辑待办", exact=True).click()
        page.locator('#todo-form input[name="title"]').fill("自动测试待办")
        page.locator("#save-todo-sheet").click()
        added = task_card(page, "自动测试待办")
        expect(added.locator("span.task-check-static")).to_be_visible()
        reopen_task(page, "自动测试待办")
        expect(added.locator(".task-checkbox")).to_have_attribute("aria-pressed", "false")
        expect(page.locator("#focus-item-name")).to_have_text(PROJECT_TITLES[0])
        expect(page.locator("#focus-sessions")).to_have_text("4")
        assert_no_task_focus_ui(page)
        choose_focus_item(page, PROJECT_TITLES[0])
        expect(page.locator("#page-timer")).to_be_visible()
        page.locator("#timer-toggle").click()
        page.clock.fast_forward(ONE_MINUTE_MS)
        expect(page.locator("#focus-sessions")).to_have_text("5")
        skip_progress(page)
        page.locator('.bottom-nav [data-page="tasks"]').click()
        expect(added.locator(".task-checkbox")).to_have_attribute("aria-pressed", "false")
        assert_task_counts(page, pending=4, done=1)
        added.locator(".task-menu").click()
        page.locator("#modal").get_by_role("button", name="删除待办", exact=True).click()
        expect(page.locator("#modal")).not_to_be_visible()
        expect(page.locator("#undo-task")).to_be_focused()
        assert_task_counts(page, pending=3, done=1)
        assert_no_task_focus_ui(page)

        page.locator('.bottom-nav [data-page="focus"]').click()
        page.locator("#open-statistics").click()
        expect(page.locator("#statistics-minutes")).to_have_text("77")
        expect(page.locator("#statistics-count")).to_have_text("5")
        expect(page.locator("#statistics-records")).not_to_contain_text("自动测试待办")
        expect(page.locator("#statistics-records")).not_to_contain_text("关联待办")
        page.locator("#statistics-source").select_option("session")
        expect(page.locator("#statistics-count")).to_have_text("2")
        expect(page.locator("#statistics-minutes")).to_have_text("2")
        for period, count in STAT_PERIODS:
            assert_statistics_period(page, period, count)
            expect(page.locator("#statistics-minutes")).to_have_text("2")
        page.locator('[data-stat-period="day"]').click()
        payload = download_statistics(page, "json")
        assert payload["includesSamples"] is False
        assert payload["summary"]["count"] == 2
        assert payload["summary"]["minutes"] == 2
        assert all(record["source"] == "session" for record in payload["records"])
        assert all(record["focusItemTitle"] == PROJECT_TITLES[0] and record["timerMode"] == "countdown" and record["targetMinutes"] == 1 and record["durationSeconds"] == 60 for record in payload["records"])
        assert all(len(record["segments"]) == 1 for record in payload["records"])
        page.locator("#statistics-source").select_option("all")
        csv_rows = download_statistics(page, "csv")
        assert len(csv_rows) == 5
        assert sum(float(row["时长（分钟）"]) for row in csv_rows) == 77
        assert {row["来源"] for row in csv_rows} == {"示例", "专注计时"}
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
        rows = download_statistics(page, "csv")
        assert len(rows) == expected_records

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
        assert_task_counts(page, pending=3, done=1)
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
        create_task(page, "横屏弹窗测试")
        assert_task_counts(page, pending=4, done=1)
        expect(page.locator("#todo-sheet")).not_to_be_visible()

        print("PASS: core interactions, CSV/JSON content, filtering, pagination, responsive layouts.", flush=True)
        page.set_viewport_size(DESKTOP_VIEWPORT)
        page.goto(Path(__file__).resolve().with_name("index.html").as_uri())
        expect(page.locator("#timer-time")).to_have_text("25:00")
        page.locator('.bottom-nav [data-page="tasks"]').click()
        expect(page.locator(".task-card")).to_have_count(3)
        assert_task_counts(page, pending=3, done=1)

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
        assert_no_task_fields(payload)
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
        assert_no_task_fields(payload)
        assert payload["summary"]["count"] == 4, "export must include completions while dialog is open"
        assert payload["summary"]["minutes"] == 76
        skip_progress(boundary_page, return_to_timer=False)
        boundary_page.close()
        check_task_grouping(browser, errors)
        check_task_guide(browser, errors)
        check_task_focus_independence(browser, errors)
        check_task_sheet_and_steps(browser, errors)
        check_task_swipe_undo(browser, errors)
        check_task_touch_delete(browser, errors)
        check_task_history(browser, errors)
        check_task_completion_motion(browser, errors)
        check_focus_configuration(browser, errors)
        check_independent_countup(browser, errors)
        check_project_navigation(browser, errors)
        check_cancelled_settings_drafts(browser, errors)
        check_progress_workflow(browser, errors)
        check_progress_queue(browser, errors)
        check_alltime_statistics(browser, errors)
        assert not errors, errors
        browser.close()
    print("PASS: project navigation, timer, tasks, important-first grouping by category, progress drafts/revisions/queues, all-time summaries, safe rendering, CSV/JSON downloads, source/date filters, pagination, 16 main-page and 16 statistics layouts, midnight/pause accounting, live export, direct file open; no browser exceptions.")
    print(f"Screenshots: {SCREENSHOT_DIRECTORY}")


if __name__ == "__main__":
    run_checks()
