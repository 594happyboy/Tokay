# AGENTS.md — Tokay 仓库说明书

> 面向 AI Agent 与初次接触本仓库的工程师。读完这一篇即建立正确的系统模型，知道一处改动会牵动
> 哪些链路、怎么验证、哪些地方不能想当然。
>
> **本文只描述当前系统。** 已剥离的文档预览插件体系（`plugin-mediator`、`pm.bridge`、运行时插件
> 下发）在本仓**没有任何残留**——源码、文档（连 `docs/archive/` 一起）都已删除，别去源码树里找
> 它们的痕迹。剥离的来龙去脉只在 `docs/reports/` 的两份历史报告里。该目录是本地工程证据，
> **不进公开仓**（`.gitignore` 里排除，`tools/tokay-export-clean.sh` 里也排除）。

---

## 0. 一句话模型

**把 GeckoView 内核从 APK 的 `lib/` 里拿出去**，改由一份独立的 `.7z` 引擎包提供：默认运行时用
签名 OTA 通道下发，也可以预置进 APK 的 assets（`KernelSource.Packaged` +
`-PpackageGeckoKernel=true`，可离线、信任根退化为 APK 签名）；对外提供一层与原生 `WebView`
尽量一致的门面（`loadUrl` / `evaluateJavascript` / `addJavascriptInterface`）。

两种来源在「本地有了包文件」之后**完全合流**，只有取字节那一步不同：

```
Application.onCreate
  └─ GeckoSdk.init(kernelSource = ...)      // Remote（清单地址+公钥，无默认值）或 Packaged
       └─ GeckoInstaller ─► InstallOrchestrator
            ├─ Remote   ：取 sdkconfig.json 原始字节 → 验 <configUrl>.sig → 校 so.<abi>.sha256 → 下载
            ├─ Packaged ：从 assets 取 gecko-kernel-<abi>.7z（无清单、无验签、不算 sha256）
            ├─ 解压到 filesDir/geckoview/versions/<v>/libs/{jni/<abi>, res/base.apk}
            └─ Success ─► PrewarmManager

HybridWebView.loadUrl(url)
  ├─ 按内核可用性选适配器（Gecko / System），失败降级到 System
  └─ 适配器直载（Gecko 走 http://127.0.0.1:<port>/...；System 走 WebViewAssetLoader）
```

**两条硬不变量**：
1. **「先装内核，再用 Gecko」**——`.so` 与 `assets/omni.ja` 都是**无条件**从 APK 剔除的
   （`GeckoSoExcluder` / `AssetsCleaner`），所以任何构建产物都必须先完成内核安装才可能起 Gecko。
2. **内核加载路径靠构建期 ASM 插桩改写**，不是运行时反射。见 §3。

---

## 1. 模块

| 路径 | 职责 |
|---|---|
| `tokay/` | 库主体（namespace `com.happyboy.tokay`）。本仓几乎全部价值在这里 |
| `tokay-asm/` | 本地 Gradle 插件 `com.happyboy.tokay.asm`（`includeBuild` 引入，**只在 `demo` 应用**）：so/assets 剔除、ASM 插桩、按 ABI 打包（进程内写固实 7z，不依赖外部 7z）、随包模式下把内核包注入 APK assets、两条构建期断言。**MPL-2.0**，见其 `LICENSE` |
| `demo/` | 演示应用（`com.happyboy.tokay.demo`）。compileSdk 36 / minSdk 26 / Java 17 |
| `tokay-archive/` | 纯 Kotlin 归档解压（commons-compress + xz），只被 `tokay` 用来解内核 7z。**不发布**，名字不叫 `sevenzip` 是因为它跟 7-Zip 无关 |
| `tools/` | 开发与验收工具，见 §5 |

`tokay` 分层：`engine/gecko`（Runtime/Session 复用）、`engine/server`（自研 HTTP +
`LocalFileServer`）、`engine/webview`（`HybridWebView` + 双适配器 + `bridge/` + `ime/` +
`interceptor/` + `lifecycle/`）、`core/`+`installer/`+`scheduler/`（安装编排与
下载解压校验）、`domain/`（`InstallState` / `SdkConfig` / `KernelSource` / 重试与安全策略）、
`infrastructure/`（常量、路径解析、状态存储、健康检查、`SdkLogger`）、`utils/`。

---

## 2. 内核 OTA 的信任链（改这块先读）

```
内置 P-256 公钥（裸 X/Y 十六进制常量，KernelSource.DEMO_P256_*）
   └─ 验 <configUrl>.sig        分离式签名，签名对象是清单**响应体的原始字节**
        └─ 覆盖 so.<abi>.sha256  强制非空
             └─ 校验 内核 .7z
                  └─ 解压后结构校验
```

每条都有代价，别反向改：

- **随包模式（`KernelSource.Packaged`）没有这条链**：它直接信任 APK 签名，不读清单、不验 `.sig`、
  也不算 `sha256`（`gecko-kernel-<abi>.7z` 就在已签名的 APK 里）。所以上面每条代价都只存在于
  Remote 模式。

- **不能用 Ed25519**。真机（Android 12 / API 31）实测 JCA 里没有它，系统自带的 stripped
  BouncyCastle 也没有；Conscrypt 到 API 33 才加。`minSdk 26` 用它 = Android 13 以下全线失败。
  原始输出见 `tools/tokay-sign/README.md`。
- **分离式签名而非内嵌 `signature` 字段**：内嵌就要求签发端（Python）与校验端（Kotlin）实现同一套
  规范化 JSON 序列化，一处空格/键序差异的症状是设备上「装不上内核」且无可用报错。
- **签名失败是 `NonRetryableFailure`**：确定性失败，重试 10 次（退避最长 60s）结果一样。
  `RetryPolicy` 识别后直接失败。注意它**报出来的 `phase` 是 `CHECKING`**（取配置阶段，
  此时还没发出任何下载状态）。
- **`InstallOrchestrator.install` 失败必须上抛异常**，不能 emit `Failed`——否则 `RetryPolicy`
  看不到异常，**重试永不发生**（历史上真出过这个 bug）。
- **`Failed.phase` 由 `orchestrator.determineFailurePhase(lastEmittedState)` 反推**。注意 sha256
  是在 `HttpDownloader` **内部**比对的，所以「包被篡改」报的是 `DOWNLOADING` 而非 `VALIDATING`。
- 公钥用**裸 X/Y** 而非 PEM：`ECPublicKeySpec` 可由裸坐标重建（真机验证过），库里不必塞 PEM 解析器。
- **`SDK_CONFIG_URL` 没有默认值**（`tokay/build.gradle`）。内核来源与验签公钥是接入方的
  信任决策，本库不替他选；空地址会在 `GeckoSdk.init` 立刻响亮失败。
- **同一设备 + 同一 serve 目录并发跑设备检查会伪造出「签名不配对」现场**（实测踩过：服务端字节、
  `.sig`、`sign.sh verify` 三方都正确，设备却报不匹配）。要让设备验收串行执行。

---

## 3. 构建期 ASM 插桩与**两层**断言（本仓最核心的工程判断）

让 GeckoView 从外部目录加载内核只有两条路：运行时反射（依赖 `dalvik.system` **隐藏 API**，
`findLibrary` 返回 null 时 `GeckoLoader` 直接抛 `Could not find mozglue path.`，**没有退路**）
或**构建期插桩**。本仓选后者：风险**有界**（绑定锁定的 GeckoView 版本），代价是接入方必须应用
Gradle 插件。但它有另一种坏法——**静默失效**：匹配靠精确的类名 + 方法名 + descriptor，
GeckoView 一改签名就匹配不上，构建照样绿、真机冷启动才炸
`dlopen failed: library "libmozglue.so" not found`。

两条断言分别堵两种失效：

| | 防什么 | 实现 | 盲区 |
|---|---|---|---|
| **第一层** | **GeckoView 改了签名** | `InstrumentationTargets` 一张签名表同时驱动 visitor 匹配与断言；断言读已解析 AAR 内的 `classes.jar`，缺失即 `GradleException` 并点名 | 不检查**本插件发射了什么** |
| **第二层** | **本仓改了包名/挪了类** | visitor 发射的类名收敛到 `InstrumentedLibraryNames`；断言核对其与 `tokay` 实际 namespace、以及手写类源文件路径是否一致 | 源码级，只证明「类还在那个包」 |

第二层为什么必须有：插桩发射的是 **slash 形式的 JVM 内部名**
（`com/happyboy/tokay/infrastructure/path/GeckoNativePathResolver`）。
改名时惯用的 `grep 'com\.zzy\.geckoview'`（点号形式）**看不到**它们，第一层断言也不覆盖——
漏改的后果同样是构建绿、真机 `NoClassDefFoundError`。

> **「别人改坏了」和「自己改坏了」是两种失效，后者更容易漏。**

- 改包名时**必须同步** `InstrumentedLibraryNames.LIBRARY_NAMESPACE`；
- 断言挂 `:demo:preBuild`；`sh gradlew :demo:verifyGeckoviewInstrumentationTargets` 可单独跑；
- 断言**刻意不读字节码产物**（会引入「preBuild 早于库编译」的任务顺序依赖），
  这层弱化由真机验收兜底。
- 两条断言都**不证明补丁真的发射进去了**，而 visitor 的 lifecycle 日志不落到控制台
  （`--rerun-tasks` 也不打印）。要确认就 `dexdump -d classes*.dex` 看方法体：
  只该有 `GeckoNativePathResolver.getMainProcessApkPath`，不该有 `getPackageResourcePath` / `sget-boolean`。

---

## 4. 视图层与生命周期

- `HybridWebView` 是唯一推荐入口，屏蔽 Gecko / System 差异，失败自动降级到 System WebView。
- **`GeckoSdk.init()` 必须先于 `HybridWebView` 构造**（要订阅 `installState`，未初始化时该
  getter 抛 `IllegalStateException`）。异常会落在协程体内，表现为日志报错 + 状态不更新。
- **订阅 `installState` 用 `Dispatchers.Main`，不要 `.immediate`**：`.immediate` 会让订阅者在
  `updateState(Success)` **内部**抢到执行权，「切 Gecko」抢在写 `meta/state.json` 之前 →
  路径解析回退到已被剔空的 `nativeLibraryDir` → `dlopen failed: library "libmozglue.so" not found`。
- **`onDetachedFromWindow` 即销毁**：从视图树移除会触发 `destroy()`，之后 adapter 为 null、
  所有 API 静默失效。不要「移除再复用」同一实例。
- **适配器切换会重放 URL**，且恢复加载**刻意直呼 `adapter.loadUri`**（走 `loadUrl` 会造成
  `performSwitch` 重入，而外层还没 `removeView`/`destroy`）。
- **给 `LoadListener` 这类接口加带默认体的方法**依赖 `-Xjvm-default=all`（已开）。护栏是
  `InterfaceDefaultMethodBinaryCompatTest`——谁去掉这个编译开关它立刻变红。
- **键盘避让**：`setImeResizeEnabled(true)` 用于边到边窗口（系统没有可裁剪的窗口空间，
  `SOFT_INPUT_ADJUST_RESIZE` 失效）；普通窗口交给系统 resize 即可，开着这开关也无副作用。边界见 §6。
- **`LocalFileServer` 是进程级单例**，靠 `RefCounter` 计数存活：适配器 `acquire`/`release`，最后一个
  持有者释放时整个服务停下并清空 `/dyn` 登记内容。所以一个进程里不要指望「自己那份」是独立的。
- **JS 桥对页面不做来源门禁**：`addJavascriptInterface` 注册的对象，**当前页面及其同源脚本**都能调
  它的任意 `@JavascriptInterface` 公有方法（唯一门禁是注解 + 参数类型白名单）。跨域 iframe 到不了
  桥（两个监听器都要求 `event.source === window`），所以剩下的风险面就是 XSS 与导航本身：
  宿主必须自己按 URL 决定「这个页面能不能挂桥」，库不替它判断。
- **`evaluateJavascript` 的回调形状**由 `BridgeSerialization.evaluateResultToJsonText` 决定：两个内核都是
  与 `WebView.evaluateJavascript` 一致的 JSON 文本（字符串带引号、对象/数组是其 JSON 文本、`null` 是
  四个字符的 `"null"`、`undefined` 与执行失败是 Kotlin `null`）。改 JS 侧 `serializeResult` 的包装类型时
  必须同步这里与 `BridgeSerializationEvaluateResultTest`——两侧词表一漂移，症状就是「同一个门面在两个
  内核上返回不同形状」（这正是历史上真出现过的 bug）。
- **页面侧桥接的公开面只有 `window.<接口名>.<方法>()`**，由原生 `createInterface` 建出来。历史上还有一批
  `window.__*__` 调试 API 与「页面自己声明接口」（`requestInterfaceInstall`）；后者是**已剥离的插件体系**的
  遗留（`git log -S "__declareBridgeInterfaces__"` 可追到 `6022395 基本完成下载插件`，
  handler 来自 `598036b …插件中间页…`），已整体删除。Kotlin 侧的 `handleInterfaceInstallRequest` /
  `InterfaceManager.hasInterface` / `BridgeMessages.REQUEST_INTERFACE_INSTALL` 与它是**同一个单元**，
  一起删才不留半死链路。要恢复这类能力属于**新功能**，两侧必须同时加。

---

## 5. 验证手段

```bash
# 构建 + 单元测试（纯 JVM，无 Node 任务）
sh gradlew :tokay:test :demo:assembleDebug

# 两条构建期断言（也随 preBuild 自动跑）
sh gradlew :demo:verifyGeckoviewInstrumentationTargets

# 内核包：进程内写固实 7z（xz 的 LZMA2），与操作系统无关；产物 demo/build/gecko-package/<abi>/*.7z
# 编码器堆占用与字典同阶（默认 64 MiB 字典约 1 GiB），守护进程堆不足会直接 Java heap space
sh gradlew :demo:packageGeckoArm64v8a

# tokay-asm 是 includeBuild 的复合构建：没有 :tokay-asm:test 这个任务，测试要单独起一个构建
sh gradlew -p tokay-asm test

# 用客户端同款解码器核对真实产物结构（Verify7z 输出 VERIFY_OK）
CP="$(find ~/.gradle/caches/modules-2/files-2.1 \( -name 'commons-compress-1.26.0.jar' -o -name 'xz-1.10.jar' \
      -o -name 'commons-io-2.15.1.jar' -o -name 'commons-lang3-3.14.0.jar' \) | paste -sd:)"
javac -cp "$CP" -d /tmp/verify7z tools/gecko-package/Verify7z.java
java -cp "$CP:/tmp/verify7z" Verify7z demo/build/gecko-package/arm64-v8a/geckoview-arm64-v8a-<版本>.7z arm64-v8a

# 多版本兼容矩阵（用 Gradle 缓存；未缓存版本每个约 231 MB）
bash tools/tokay-compat-matrix.sh 140.0.20250707120347 147.0.20260212191108

# 本地「远程下发」联调（改写 sdkconfig 指向本机 + **重新签名** + 起服务）
bash tools/gecko-package/serve-local.sh            # --prepare 只组装不起服务
adb reverse tcp:28080 tcp:28080

# 真机端到端验收：清数据 → 从零下载内核 → 断言 Gecko 从外置目录加载
bash tools/tokay-device-check.sh                   # 退出码即结论

# 随包模式的真机验收：不起发布服务、不建 adb reverse（通过即证明内核来自 APK 里那份）
bash tools/tokay-device-check.sh --packaged
```

**内核外置的失效只在真机上暴露。** 构建永远绿，真机才报 `dlopen failed: library "libmozglue.so"
not found`。所以**改动构建链路后必须跑 `tokay-device-check.sh`**，退出码 0 才算过。
`adb devices` 为空时如实标注「待真机」，不要声称已验证。

`tools/tokay-sign/` 是离线签发工具；`tools/tokay-export-clean.sh` 是干净仓导出脚本。

---

## 6. 已知陷阱（都踩过）

- **`const val` 编译期内联**：改 `const val` 之后，增量构建可能让下游测试读到**旧值**。
  测试行为反常时**先 `gradlew clean` 再判断**，不要先去改断言——否则可能把真 bug 改成
  「测试迁就实现」。（同类问题在接口默认方法上也出现过一次。）
- **Android 本地单测按 `android.jar` 的 boot classpath 编译**：`com.sun.*` / `sun.*` 不在其中。
  想测真实 HTTP 往返就放到真机验收里，**不要把 HTTP 服务器拖进 JVM 单测**
  （`ConfigRepositorySignatureTest` 用裸 `ServerSocket` 写桩是这个约束下的正解）。
- **`gradlew` 在源仓里没有执行位**（导出脚本会自动补）。用 `sh gradlew ...`。
- **内核打包任务的内存/字节约束**（它现在在进程内写固实 7z，与操作系统无关）：
  ① 编码器堆占用与字典同阶，默认 64 MiB 字典约 1 GiB，Gradle 守护进程堆不足会直接
  `Java heap space`；② 容器是手写的（commons-compress 的 `SevenZOutputFile` **每个文件一条独立
  压缩流，写不出固实块**，实测体积差约 2%），头部字段的字节序/位序/可变长整数都按 7z 格式写，
  **改 `SevenZWriter` 前先用 commons-compress 读回验证**。另外**仓库里没有任何内核包** ——
  它是运行时下发的产物。
- **插件类路径上不要引入 `commons-compress`**：宿主 AGP 自带的那份会按父优先遮蔽插件的版本，
  而它看不见插件独有的 `xz` → `NoClassDefFoundError: org/tukaani/xz/FilterOptions`。
  `SevenZWriter` 因此只依赖 `org.tukaani:xz`；需要 commons-compress 就放 `testImplementation`。
- **随包模式要求 `ndk.abiFilters`**：插件在为空时直接抛 `GradleException`，并给出要加的代码——
  「把所有 ABI 的包都塞进去」的兜底会让 APK 比「内核留在 `lib/`」还大。检测到 ABI splits 同样抛。
- **未开配置缓存**；断言任务会解析 `*RuntimeClasspath`，与配置缓存不兼容。
- **`tokay-archive` 的 4 条 commons-compress 废弃告警是刻意留着的**（`SevenZFileOptions`、
  `SevenZFile(File, SevenZFileOptions)`、`ZipFile(File, String)`）。迁到 `SevenZFile.builder()` /
  `ZipFile.builder()` 的收益只是消噪，代价是动**内核解压的关键路径**；而该模块**没有测试源集**，
  本机也没有 `7z` 命令行，改完没法验证。真要迁，先给它补一个「能打开真实 7z + zip」的 JVM 测试。
  ⚠️ **增量构建看不到这些告警**，只有 `gradlew clean` 或全新 worktree 才会暴露——所以"构建没告警"
  这句话必须说清是在哪种构建下得到的。
- **内核「装好了」只有一个判定**：`GeckoKernelLayout.isComplete`（`.unzip_success` 在 + `res/base.apk`
  是文件 + `jni/<abi>` 是目录，ABI 取自 `DeviceUtil`）。解压器的复用短路、编排器的复用短路与收尾校验、
  启动健康检查三处都必须走它。历史教训：前两处曾用「标志在 + 目录非空」——那是**恒真**的（标志正是
  解压器自己刚落下的，且它本身就让目录非空），而归档层对单条目失败是"计数不抛"（磁盘满 / Zip Slip /
  IO 错都只 `failures++` 后继续），于是缺文件的目录被宣布成装好、又被短路反复复用；健康检查即使判出
  不健康，也会被短路翻回去 —— 设备上再也修不回来。配套的两条护栏：解压器在 `failureCount > 0` 时
  **拒绝写标志并报 Failure**（`SevenZipExtractorIncompleteExtractionTest`），以及结构判定与健康判定同源。
- **键盘适配靠引擎，不靠页面脚本**：库只做一件事——边到边窗口下把 IME 高度作为**底部 padding 施加在
  HybridWebView 自己身上**（`setImeResizeEnabled`）。View 变矮 = 引擎收到一次 resize，而 Gecko /
  Chromium 在输入法打开时本来就会把聚焦的可编辑元素滚进可视区（Gecko 侧即 `nsDOMWindowUtils.ZoomToFocusedInput`，
  见 Mozilla Bug 1544660 的调用链）。历史教训：这里曾有一套「原生用 PopupWindow 探针算遮挡几何 + 注入
  1241 行脚本替引擎滚动」的实现（约 2500 行、10 个公开开关），那是在重写引擎已有的行为，还引出一个
  「重载后静默失效」的 bug；已整体删除。两条必须记住的补充：① 引擎的滚动发生在**键盘刚弹出时**
  （那时视口还没缩），所以 padding 缩完之后要补一次 `scrollIntoView` 兜底
  （`NUDGE_FOCUSED_ELEMENT_SCRIPT`，一次性调用、不注入常驻脚本），而这一补必须**先判断元素是否真的
  在视口外**，否则会和引擎自滚叠加成「滚两次」；② `position: fixed` 的底部输入框在 resizes-visual 下
  **不会**被键盘推动（Mozilla 自己的测试断言过），这是本方案已知缺口。
- **键盘避让默认关闭，对宿主布局有要求**：宿主已自己处理键盘时（Compose `imePadding()` 等）会消费掉
  IME insets，本库拿到 0、不会叠加；但宿主只加 padding 而**没有消费**时两份会叠加，所以必须显式开启。
  另外宿主必须给 HybridWebView 一个**确定高度**（match_parent / 固定 dp / weight），`wrap_content`
  下加 padding 只会让它变高。**Compose 的 `AndroidView` 子树收不到 window insets 的常规分发**
  （`setOnApplyWindowInsetsListener` 只在挂载时被调用一次，键盘弹出时不再回调），好在**动画回调仍会到达**
  ——`ImeInsetResizer` 因此以动画回调为主、监听器为辅。
- **换设备测键盘前先看 WebView 版本**：官方文档说 Chromium **M139 起 WebView 会自己把 IME 缩进 visual
  viewport**（`ImeAdapterImpl` 只在「视口真变小 + 宽度不变」时才自滚）。本仓验证机是 Huawei WebView
  114.0.5.302（Chromium 114），**没有 ≥M139 的设备可验**，那代设备上本库的 padding 是否变成「缩两次」
  尚未验证。
- **Gecko 侧拦截规则是「JS 正则」，不是 Java 正则**：`toGeckoJson` 把 `hybrid://asset|file` 前缀换成
  本地服务真实 URL 之后，整串交给 `background.js` 的 `new RegExp` 编译，所以拼进去的字面量必须按
  **JS** 规则转义。用 `Regex.escape` 会产出 Java 专有的 `\Q…\E`，而 JS 只把 `\Q`/`\E` 当成字面量
  Q/E —— 症状是宿主按 `hybrid://asset/…` 写的规则在 Gecko 上**静默永不命中**（System 适配器却正常，
  因为它是映射回 `hybrid://` 之后才匹配）。护栏是 `RequestInterceptionGeckoRegexTest`。
- **运行时的 bridge JS 来自内核包，不是 APK assets**：`resource://android/assets/...` 解析到 `-greomni`
  根，也就是包内 `res/base.apk`，而打包任务会把 `tokay/src/main/assets/runtime_extensions` 合并进去。
  所以改了 `bridge.page.js` / `bridge.plugin.js` / `manifest.json` 之后**必须重新打包内核**：只重装 APK
  在设备上没有效果（实测踩过——APK 里是新的，设备上跑的是包里那份旧的）。另外 GeckoView 按
  `id + version` 决定要不要重装内置扩展，所以 JS 改动还要**同时 bump `manifest.json` 的 `version`**，
  否则即便换了包，装过该扩展的设备也可能继续用旧脚本。APK 里那份 runtime_extensions 只是打包输入。
- **内核打包任务的输入必须包含 `runtime_extensions`**：漏了它，Gradle 判定包没变（UP-TO-DATE），
  于是「重新打包」也拿不到新 JS —— 这是上面那个坑的第二层。任务里的
  `@InputDirectory runtimeExtensionsDir` 就是为此存在，别删；它与执行时实际读取的目录是同一个来源。
- **历史报告里的旧包名/旧路径是证据，不要改**。`docs/reports/` 的原始输出一旦被"顺手更新"
  就等于篡改证据（改名批次踩过一次，已回退）。该目录不进公开仓，但本地留存的原件同样不能改。

---

## 7. 许可

库主体 Apache-2.0（`LICENSE`）；`tokay-asm/` MPL-2.0（其 `LICENSE`）。
`NOTICE` §1.2 是 MPL-2.0 要求的「修改源码可得性」声明——它指向 `tokay-asm/` 作为首选修改形式，
**改插桩后请同步该节**。
