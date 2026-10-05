# UI 规范

本规范基于 [miuix](https://github.com/compose-miuix-ui/miuix)（Compose Multiplatform 组件库）。所有 UI 改动都必须遵守本文档。

---

## 1. 组件使用

- 所有 UI 组件使用 miuix（`Card`、`TopAppBar`、`NavigationBar`、`SmallTitle`、`TextButton` 等）。
- miuix 组件（`Button` / `IconButton` / `TextField` / `NavigationBar` / `Dialog` …）内部已用 squircle 渲染圆角，**直接用即可，无需处理 squircle modifier**。

## 2. 自定义形状元素用 squircle modifier

非 miuix 组件的手搓形状，**不要**再用 `clip(RoundedCornerShape(r))` / `background(shape = RoundedCornerShape(r))`，改用 `top.yukonga.miuix.kmp.squircle.*`，随 miuix-ui api 传递，无需单独加依赖（Android < API 33 / 无 runtime shader 自动回退 `RoundedCornerShape`）。

按性能选路径：

| 场景 | 用法 | 说明 |
| --- | --- | --- |
| 非点击纯色背景（徽章 / 占位块，内容不溢出） | `Modifier.squircleBackground(color, radius)` | 无 offscreen layer，最省，**不要 clip** |
| 图片 / 必须裁剪的内容（如组图标 Image） | `Modifier.squircleClip(radius)` | 一个 offscreen layer |
| 可点击元素（需把涟漪裁到圆角内） | `Modifier.squircleSurface(color, radius) + .clickable{}` | 一个 offscreen layer 同时填充 + 裁剪；条件可点击时按 `isSelectable` 分支退化为 `squircleBackground` |

- 3dp 小徽章保持 `clip(RoundedCornerShape(3.dp))`：该尺寸下 squircle 与圆角肉眼无差异，且元素多一个 GPU layer 不划算。

## 3. 图标

- **返回按钮**使用 `MiuixIcons.Back`。
- **底栏图标**：`Sidebar` / `Tune` / `UploadCloud` / `Settings`。

## 4. Badge

- `clip(RoundedCornerShape(3.dp))` + `9.sp` Bold Monospace。

## 5. 操作 IconButton

- `minHeight` / `minWidth` = `35.dp`，`backgroundColor = secondaryContainer`。

## 6. 页面骨架

`Scaffold` + `TopAppBar (scrollBehavior)` + `LazyColumn`。

- `LazyColumn` 必须加 `.scrollEndHaptic().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)`。
- `contentPadding = PaddingValues(top = innerPadding.calculateTopPadding())` —— **仅设 top，不设 bottom**。
- 首个 item 是 Card / 表单时用 `item { Spacer(Modifier.height(12.dp)) }` 顶部呼吸；**`SmallTitle` 或 `RestartRequiredHint` 开头不加**（`SmallTitle` 自带 InsideMargin 上边距，再加 Spacer 会比其他页多出一截）。
- 末尾 item 统一 `item { Spacer(Modifier.height(24.dp).navigationBarsPadding()) }` 吸收导航栏 + 留白。
- 二级页面（独立 NavDisplay entry）签名**禁止** `bottomPadding: Dp` 参数 —— 靠 Spacer (`navigationBarsPadding()`) 自适应即可。
- **4 个 Pager Tab 例外**（`HomeScreen` / `ProxyScreen` / `SubscriptionScreen` / `SettingsScreen`）：因外层 MainPage Scaffold 持有 bottomBar，必须接 `bottomPadding: Dp`，把 outer Scaffold 的 `innerPadding.calculateBottomPadding()` 透传给 LazyColumn contentPadding。

## 7. 顶栏 / 底栏毛玻璃

所有页面 Scaffold 必须用 `BlurredBar` 包裹 `TopAppBar` / `NavigationBar`；MainPage 外层 Scaffold + 每个二级页面各自一份 backdrop（嵌套 `layerBackdrop` 是 OK 的，layer 抓取相互独立）。

模式：

```kotlin
val backdrop = rememberBlurBackdrop()
val blurActive = backdrop != null
val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

topBar = { BlurredBar(backdrop, blurActive) { TopAppBar(... color = barColor ...) } }
bottomBar = { BlurredBar(backdrop, blurActive) { NavigationBar(color = barColor) {} } }
```

- 内容区 LazyColumn modifier 链中追加 `.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)`，让 backdrop 抓取内容 layer 给 TopAppBar / NavigationBar 的 textureBlur 用。
- 含搜索动画的页面（`AppProxyScreen` / `ConnectionScreen`）：在 `BlurredBar` 内套 `searchStatus.TopAppBarAnim(backgroundColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface) { TopAppBar (...) }`，让搜索切换时不挡住毛玻璃。

## 8. 宽屏适配

窗口宽度 ≥ 600dp（`WideScreenMinWidth`，`WindowSize.kt`）时用 `rememberIsWideScreen()`，**用缩放前 `LocalPlatformDensity` 量宽**，界面缩放不翻转外壳时：

### 8.1 导航

- MainPage 把底部 `NavigationBar` 换成侧边 `NavigationRail`（可展开收起 —— 传 `state = rememberNavigationRailState()`，默认收起，顶部有内置 `Sidebar` 展开钮，展开后 item 变 Icon+label 横排 pill）。
- 手机与宽屏共用同一 `pagerContent: (Modifier, Dp) -> Unit` lambda。
- Home Tab 图标用 `MiuixIcons.Home`（底栏与 rail 同步，**不用** `Sidebar`，避免与 rail 展开钮的 Sidebar 图标撞脸）。
- `inset.rail`（`defaultWindowInsetsPadding=true`）吸收起始侧 cutout/navBar，内容区 `consumeWindowInsets(...Start) + windowInsetsPadding(systemBarsDisplayCutout.only(End))` 处理末尾侧；底部经 `bottomPadding = navigationBars.asPaddingValues().calculateBottomPadding()` 透传给各 Tab。

### 8.2 顶栏

- 所有用 `TopAppBar` 的页面（19 个）改走 `AdaptiveTopAppBar` —— 宽屏用固定不折叠的 `SmallTopAppBar`（rail 取代底栏后纵向空间紧张），手机用大标题 `TopAppBar`，参数覆盖 `title` / `color` / `scrollBehavior` / `navigationIcon` / `actions` / `bottomContent`。
- `AboutScreen` 例外：其 hero 视差本就固定用 `SmallTopAppBar`，不套 Adaptive（否则手机端会多出与 hero 重复的大标题）。
- 搜索页（`AppProxy` / `Connection`）保留 `searchStatus.TopAppBarAnim { AdaptiveTopAppBar(...) }` 包裹；其搜索框动态 top padding 在宽屏（固定 SmallTopAppBar 永不折叠、`collapsedFraction` 恒 0）时恒为 0（`if (isWideScreen) 0.dp else 12.dp * (1 - collapsedFraction)`），仅手机可折叠大标题栏才随折叠动态收缩。

### 8.3 内容居中

- 4 个主 Tab 的 LazyColumn 用 `WideContentBox { sidePadding -> LazyColumn (...) }` 包裹（内部 LazyColumn 传入 `sidePadding`，按内容区实际宽度算出单侧留白）。
- LazyColumn 保持全宽（滚动手势覆盖整屏两侧无死区），仅把 `sidePadding` 加进内容居中到 `MaxContentWidth=800dp`（内容上限与 600dp 外壳阈值是两个独立常量）。
- 是否居中复用唯一权威 `isWideScreen()` 判断（外壳缩放后 `LocalDensity` 量宽）。若独立比较阈值，`densityScale≠1` 时会出现「手机外壳内内容内缩」或「rail 外壳不居中」，行为完全不变。
- **不要**去压缩 LazyColumn 节点宽度的 layout modifier —— 那会让 600 外侧区域无法接收滚动手势（死区）。

### 8.4 横屏屏幕缺口

- miuix `InsetPadding` 不自动 padding 内容；只经 `innerPadding` 提供 inset，二级页 contentPadding 又只吃 top，会横屏侧边刘海 / 挖孔 / 手势条下内容压到缺口里。
- 每个二级页内容根 LazyColumn 加 `Modifier.horizontalCutoutPadding()`（`WindowSize.kt`，只补水平 inset displayCutout，UI navigationBars 在 inset 竖屏 / 无侧边缺口为 0），紧跟在 `.fillMaxSize()` 后。顶栏由 TopAppBar 自身 `defaultWindowInsetsPadding=true` 处理，两者不重叠。
- `AboutScreen` 相反：其 `addInsetPadding=false`（hero 视差不吃顶部 inset），内容侧已用 `Scaffold.contentWindowInsets.only(Horizontal).calculateStart/EndPadding` 处理缺口，故只需给它的 `SmallTopAppBar` 加 `Modifier.horizontalCutoutPadding()` 补顶栏。
- 4 个主 tab 内容居中到 800dp，天然在缺口内侧，无需此项。

## 9. Card 间距

- 水平 12dp，每项统一 `padding(horizontal = 12.dp).padding(bottom = 12.dp)`，**不使用** `Arrangement.spacedBy`。

## 10. 多组件卡片独立 LazyItem（滚动性能）

`LazyColumn` 里**禁止** `lazyItem { Card { rowA(); rowB(); rowC() } }` 这种「单个 item 塞整卡多行」的反模式 —— 它让整卡内容一次性组合 / 重绘。改用多模式：`setItems` 整卡内容一次性组合；卡片 / 滚动 / 展开 / 开关改用 `GroupedCardItems`。

```kotlin
GroupedCardItems(keyPrefix, items = listOf(CardItem(…))) { item ->
    // 自动拆成独立的 miuix 风格 cardSegment，分角，拼每一张可视连续的 miuix 风格卡片；
    // 在 LazyColumn 只用组合可见段。
}
```

- **分角背景选路**：有圆角的首 / 末段用 `squircleSurface(fillClip, 一个 offscreen layer)` → 必须 clip，组内点击 `squircleSurface` 会溢出圆角，与 miuix Card 用 squircleSurface 同因；中间段无圆角纯 `background`（无 offscreen layer，最省）。
- **语义对齐** miuix `CardSurface`/`CardItem`：内容包 +16dp 圆角 + `insideMargin` 默认 0，preference 自带内边距，故段 `insidePadding=0`，按所替换 Card 的 `padding(bottom=…)` 传（6/12/0）。
- 条件行用 `buildList { if(...) add(CardItem(...)) }`。
- `GroupedCardItems` **不加** item 动画（保持原静态 Card 无动画行为，拆分为对用户不可见的纯性能优化），需要展开 / 收起动画的自行在 item 内用 `Modifier.animateItem(...)`。
- Settings 各屏幕、`DnsQueryScreen` 结果、`ProxyScreen` 节点网格均按此重构。
- `ProxyScreen` 额外把节点状态从 `item.statemember` 移出（节点行是顶层 lazy item、随展开动态增删，存于 item 内会随 item 销毁丢失）；组头段 `item {` 每行 ≤2 节点段拼一行，组头 / 段与行在 LazyColumn 内容 lambda 完成，组头段与 item 段用 `Modifier.animateItem()`（默认 fade + `placement.spring`）—— 展开时节点行淡入；下方各组平滑下滑；替代原 `verticalLayout`（节点多时一次性组合整组才上卡源，拆 lazy 后组合快、动画交 `animateItem`）。
- `placement` spec **不能设 null**，否则下方各组硬跳、展开无动画感。
- 组头段角角随展开在(300.dp) 经 `CardSegment.bottomCornerRadius` 覆写 `isFirst`/`isLast` 推导值；与 chevron 旋转 / 节点行淡入同步 —— 否则 `isLast` 随 rows 翻转，会让组头底角瞬间圆方突变。
- **不适用**：纯静态文本（`ExternalControl` 提示卡、`RootSettings` 警告卡）与带视差 + textureBlur 的 `AboutScreen` —— 保持单 lazyItem 结构。

## 11. 高度 Inset

- `Modifier.heightIn(min = lazyListState.layoutInfo.viewportSize.height.toDp())`，而非固定 `fillParentMaxHeight()` —— 后者会把 Group 的死区恰好一个视口高，横屏矮视口下超出 Group 的死区会被裁剪，滚动露出；`heightIn(min…)` 保证至少一屏，同时允许内容更高时增长。

## 12. TextField 表单

- **不包 Card**，直接 12dp `padding(horizontal = 12.dp).padding(bottom = 12.dp)`。

## 13. Edit Dialog 按钮顺序

- `not modified | Cancel | Confirm`（三按钮，weight `(1f)`，`background(shape)`，Confirm 用 `MiuixTheme.colorScheme.primary()`）。

## 14. WindowDialog

- 在手机上对 Content Column，**不设** `maxHeight(heightIn(max = Unspecified))`，内容过长会铺满屏幕把底部按钮顶出屏幕。
