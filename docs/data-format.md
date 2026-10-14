# EventLog 导出格式 4

恢复使用完整 ZIP；无图片时也支持单独的 JSON 文件。带图片的备份必须保留 ZIP 中的 `records.json` 和全部 `images/` 文件。恢复支持的 JSON 上限为 20 MB，CSV 用于分析，不用于恢复。

为兼容已有备份，JSON 的应用标识仍为 `app: "Daymark"`。EventLog 是当前英文名称，名称变化不改变字段含义或恢复规则。

格式 2（App 0.1.1 起）的 `feeling`、`worth`、JSON `expectedFeeling` / CSV `expected_feeling` 统一为：−2 非常不值、−1 不值、0 中性、1 值得、2 非常值得；`null` / CSV 空白表示未评分。0 参与统计，未评分不参与平均。评分偏差仍为“实际 − 预计”，范围为 −4 到 4。

兼容格式 1：导入时将旧评分 1–5 减 3 后再验证，包含已删除事件和全部预测版本。格式 2 保持原值，不做重复转换。资金金额、时长和概率的含义不变。

| 文件 | 内容 |
| --- | --- |
| records.json | 完整备份，含 schemaVersion、app、exportedAt、zone 和 data |
| plans.csv | 计划、日期、时段、开始分钟、预计耗时、关联实际记录、完成与删除状态 |
| plan_durations.csv | 已完成计划的预计耗时、实际耗时与实际减预计 |
| events.csv | 事件、实际时段、体验、费用是否已知、创建/更新时间、首次记录结果时间、删除标记 |
| cash.csv | 唯一资金流水来源，signed_cents 为有符号整数分，event_id 可为空 |
| sleep.csv | 起床日期、timed/none/unknown、入睡和起床时刻、备注 |
| forecasts.csv | 全部预估版本，包括预计用时、费用、体验、问题、概率、截止时间、依据和事前标记 |
| resolutions.csv | 概率结果判定，happened 为布尔值；更正结果会更新该行 |
| balance_anchors.csv | 带时刻的已知资金余额起点/核对点 |
| day_checks.csv | 已复核日期与复核时间 |
| prediction_errors.csv | 当前有效样本的实际减预计；空白表示缺少对应值 |
| daily_summary.csv | 有记录或被跨日记录覆盖的日期，以及已记录收支、实际耗时、未知计数和余额 |
| photos.csv | 图片编号、所属事情或独立收支、相对文件路径、格式、大小、SHA-256、添加时间 |
| images/ | 图片原文件；相同内容只存一份 |
| README.txt | 导出包内的简要计算说明 |

## 分析时注意

1. `null` 和 CSV 空白表示没有该项数值。日汇总中的 0 表示记录内合计为零，不能据此推断全天没有活动或开销。结合 `has_recorded_data`、未知计数、睡眠确认和原始记录判断完整性。
2. `events.actual_cost_cents` 是关联流水的便于阅读的副本。计算资金变化只汇总 `cash.signed_cents`，不要再加事件费用。
3. `expense` 的有符号金额非正，`income` 非负，`adjustment` 可正可负。`daily_summary` 将支出改为非负数，便于展示。
4. `deleted_at` 非空的事件不参与界面统计。其关联流水已经移除，历史费用不能从此导出重建；事件文本和预估版本仍保留。
5. 事前标记还须结合 `made_at <= 实际开始时刻` 及 `made_at < first_outcome_at`（若存在）验证，不能只依据 `prospective` 列。同一事件的多个版本不应当成独立样本。
6. 应用内每个事件使用首份有效预估计算用时/费用误差；首份有效且有概率的预测用于概率统计。概率以 0–100 整数存储，计算 Brier 前除以 100。
7. `wake_day` 表示睡眠确认所属日期，日汇总按自然日裁切时间区间。没有睡眠确认不等于零睡眠；“时间记不清”不生成虚构时段。
8. 初始余额是 `as_of` 时刻之前的余额。计算时先选最新的、不晚于目标时刻的起点，再加 `as_of <= occurred_at <= 目标时刻` 的流水。起点之前保持未知。
9. JSON 保存绝对时间和导出时区；界面和日汇总按手机当前时区分日。第一版没有固定账本时区，跨时区旅行后按日分组可能变化。
10. CSV 中以公式字符开头的文本会加前导单引号，原始文字在 JSON 中完整保存。金额、时长等数字列保留数值类型。

资金合计使用整数分，可避免二进制小数直接相加带来的误差。睡眠及旧活动区间分别去重；新记录的 actualMinutes 按日期直接求和，无法判断重叠。

## 版本 3 新增字段

`events.actualMinutes`（CSV `actual_minutes`）是可空整数分钟，包含 0 时不能当作未知。使用该字段时 `endAt` 必须为空；`startAt` 是日期及流水锚点，不表示实际开始时刻。CSV `duration_minutes` 优先取实际耗时，否则由旧区间计算。`daily_summary.activity_minutes` 为旧区间并集分钟数加当天新记录耗时，替代原 `activity_minutes_union` 列名。

`plans` 与事件分开。`day` 为可空 ISO 日期，`period` 为 any/morning/afternoon/evening；`startMinute` 为当天 0–1439 分钟，`expectedMinutes` 为可空预计耗时。没有开始时间的事项不画精确时间块；有开始时间但耗时未知时，界面显示“耗时待定”，最小显示高度不代表预计用时。

`eventId` 唯一关联自动生成的实际记录。`completedAt` 非空表示完成；撤销后该值置空，关联事件标记为已删除、流水移出统计，计划的 `savedCostCents` 保存待恢复的已填费用。再次完成复用原事件并恢复费用和文字；不会重复生成事件。`deletedAt` 非空表示计划被移除。

从格式 1、2 恢复时，为仍有效的 planned 事件生成 `legacy-原编号` 计划，取最新预估耗时。原事件、分类、两段文字、预测和判定不删改。旧取消事件保留在备份，不作为实际记录展示。

JSON 日期与时间仍以手机当前时区解释；跨时区使用可能改变按日分组。

## 版本 4 新增图片

`data.photos` 记录附件信息：`id`、`ownerType`（event/cash）、`ownerId`、`fileName`、`mimeType`、`bytes`、`sha256`、`createdAt`。所属记录必须存在，每条最多 12 张，单张最多 20 MiB。图片以内容校验值命名，在 ZIP 的 `images/` 中保存；恢复前核对路径、文件大小、内容校验值及关联关系。

格式 1–3 不含图片，恢复时按空附件列表处理。恢复前的自动备份也包含现有图片。移除附件在保存记录后生效，不删除相册原图；撤销计划完成仍保留关联事情的图片，以便再次完成时恢复。应用暂不自动清理未被记录引用的本地图片文件。

ZIP 解压内容总量上限为 1 GiB，JSON 与单个 CSV 上限为 20 MB。CSV 仅用于离线分析。
