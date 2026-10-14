# 测试说明

当前版本 0.3.0。2026-10-14 在一加 Ace 3 上完成本次验证：32 项 JVM 单元测试、12 项数据库与图片界面设备测试、2 项需要系统相册和相机交互的专项测试均通过。测试使用独立临时数据库和示例记录，不写入正式账本。

## 单元测试

```powershell
./gradlew.bat testDebugUnitTest
```

覆盖时间重叠与跨日拆分、按日耗时与未知/零、资金余额、历史预估与概率计算、−2 到 2 评分、旧备份转换、计划关联、JSON/ZIP 往返、CSV 转义以及重叠和跨日的日程时间块。

## 设备测试

连接 Android 手机或模拟器并允许调试后执行：

```powershell
./gradlew.bat connectedDebugAndroidTest
```

覆盖数据库持久化、恢复事务、观察者更新、历史预测保留、睡眠确认，以及真实版本 1、2、3 的数据库升级到版本 4。计划测试覆盖并发完成不重复、撤销和再次完成保留文字/耗时/费用、改期、删除、重启与恢复。界面测试覆盖默认支出、计划名称单独保存、可选时段和耗时、记录合并文字、评分、日程、日历、趋势和不打断操作的完成提示。

正式账本在覆盖安装前后各留一份校验通过的备份，旧表逐字段对比一致。此过程不卸载、不清空应用数据；升级前的旧版本安装包和源码提交均保留。数据库已升级到 4 后，旧 APK 不能直接读取新版数据库；回退应配合升级前备份。

`UiScreenshotTest` 会把示例界面截图写到应用的外部文件目录 `verification`，便于检查布局。个人数据、手机截图、诊断日志和本机备份不纳入仓库。

修改数据库或备份格式前，先备份现有数据；迁移测试应核对非空旧记录、空值、评分、关联关系和其他字段。

## 图片专项验证

新增测试覆盖图片文件独立保存、EXIF 旋转、重新打开数据库、附件关联、撤销与再次完成、导出恢复，以及缺失／损坏图片、超限附件和非法路径的拒绝。图片数据表升级后，正式账本原有 7 条事情、14 条收支及其他表的全部字段均与升级前一致，未留下测试记录。

`PhotoUiTest` 默认只运行自动界面测试；两项系统应用交互测试需显式传入 `-e externalPhotos true`，并在相册选择生成的 EventLog TEST 图片，或在相机完成拍摄和确认。首次相册专项测试发现测试脚本重复清理已删除的测试图片，修正后复测通过；应用的选图和保存流程通过。

示例运行：

```text
adb shell am instrument -w -e externalPhotos true -e class com.self.daymark.PhotoUiTest#realGalleryReturnsASelectedImageToCashDraft com.self.daymark.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e externalPhotos true -e class com.self.daymark.PhotoUiTest#realCameraReturnsAFullPhotoToEventDraft com.self.daymark.test/androidx.test.runner.AndroidJUnitRunner
```
