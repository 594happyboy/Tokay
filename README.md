# Tokay

Tokay 是一个 Android WebView 库：把 GeckoView 内核从 APK 里挪出去，运行时再装回来。
对外接口照着原生 `WebView` 的形状做，应用侧代码基本不用改。内核走签名 OTA 下发，也可以直接随 APK 发。

```kotlin
val web = HybridWebView(context)
web.addJavascriptInterface("Bridge", myBridge)
web.evaluateJavascript("1 + 1") { result -> /* ... */ }
web.loadUrl("https://example.com")
```

还在开发中，接入前先看[限制](#限制)。

## 为什么要挪出去

GeckoView 默认把内核打进 APK。实测值（GeckoView `147.0.20260212191108`、arm64-v8a）：

| 内容 | 体积 |
| --- | --- |
| `jni/arm64-v8a/*.so`（13 个，`Stored` 未压缩进包） | 162.3 MiB，`libxul.so` 占 143.4 MiB |
| `assets/omni.ja` | 13.2 MiB |
| 单 ABI APK | 约 198 MiB |
| 三个 ABI 的 APK | 约 505 MiB |

这个体积既卡下载转化，也容易撞应用市场的包体限制。挪出去之后：

| | 内核留在 `lib/` | Tokay（OTA） | Tokay（随包） |
| --- | --- | --- | --- |
| 单 ABI APK（arm64-v8a） | 约 198 MiB | 17.4 MiB | 74.9 MiB |
| 首次可用 | 装完即可 | 下载 57.4 MiB | 从 APK 自身 assets 解压（离线） |
| 信任根 | APK 签名 | 清单签名（ECDSA P-256）+ `sha256` | APK 签名 |
| `ndk.abiFilters` | 可选 | 可选 | 必需 |

## 工作方式

![Tokay 架构图](docs/assets/architecture.svg)

构建期，Gradle 插件把内核从 APK 剔除，并改写 GeckoView 的六个方法（字节码插桩），让 `GeckoLoader`
到我们指定的目录找库文件。运行期，内核被拉取、验签、解压到 `filesDir/geckoview/versions/<version>/libs/`
再加载。字节落到磁盘之后的流程两种方式完全一样，只有来源不同。

### 为什么用插桩，不用运行时反射

反射（`web-to-app` 的做法）要嫁接 `DexPathList.nativeLibraryPathElements`、伪造
`getPackageResourcePath()`，不用构建期插件，但依赖 `dalvik.system` 隐藏 API 且没有退路：`findLibrary`
返回 null，`GeckoLoader` 就抛 `Could not find mozglue path.`。库要发到未知机型上，这个风险我不想背。

插桩的风险有界，它绑定在你声明的 GeckoView 版本上。代价是匹配靠精确的类名、方法名和 descriptor，
GeckoView 改签名就会静默失效：构建照样绿，真机冷启动才报
`dlopen failed: library "libmozglue.so" not found`。两道构建期断言挡这个：

* 读已解析 AAR 里的 `classes.jar`，签名对不上就让构建失败并点名是哪个方法。
* 管我们自己：插桩发射的是 slash 形式的内部名（`com/happyboy/tokay/...`），改名时惯用的
  `grep 'com\.happyboy\.tokay'` 看不见它，所以专门核一遍常量、namespace 和源文件路径。

升级 GeckoView 就是改版本号再构建。`140.0.20250707120347` 和 `147.0.20260212191108` 均 6/6 通过，
改写后的路径在真机上确实被用到（Android 12 / arm64-v8a）：

```
W Gecko: type=1400 audit(0.0:51557): avc: granted { execute } for path=
  "/data/data/<pkg>/files/geckoview/versions/147.0.20260212191108/libs/jni/arm64-v8a/libmozglue.so"
D GeckoThread: State changed to MOZGLUE_READY
```

SELinux 的 `granted { execute }` 说明内核真的从这个目录 mmap 并执行了。同一次改写对 Gecko 的内容
子进程也生效，而反射在子进程里没有干净的插入点。

### 签名 OTA 的信任链

```
内置 P-256 公钥
   └─ 验  sdkconfig.json.sig        （对响应体原始字节的分离式签名）
        └─ 覆盖  so.<abi>.sha256
             └─ 校验  内核 .7z
                  └─ 解压后结构校验
```

签名单独放 `<configUrl>.sig`，不塞进 JSON 的 `signature` 字段：内嵌就要求签发端（Python）和校验端
（Kotlin）实现同一套规范化 JSON 序列化，差一个空格或键序，症状就是设备上装不上内核、还看不出原因。
低于已安装版本的清单会被拒绝，除非显式强制。

Ed25519 本来更合适，但 Android 13（API 33）之前系统里没有它，`minSdk 26` 用不起，所以这里用
ECDSA P-256 / SHA-256：API 26 起原生可用，零新增依赖（实测输出见 `tools/tokay-sign/README.md`）。
公钥以裸 X/Y 十六进制内嵌，运行时用 `ECPublicKeySpec` 重建，不必为此背一个 PEM 解析器。

## 用法

```groovy
// app/build.gradle
plugins {
    id 'com.happyboy.tokay.asm'
}
```

```kotlin
// Application.onCreate
GeckoSdk.initializePrewarm(this)                 // 可选：首屏之前先把内核预热起来
GeckoSdk.init(
    application = this,
    kernelSource = KernelSource(configUrl = "<你的清单地址>", publicKeyP256 = <裸 X||Y>),
)
GeckoSdk.installState.collect { /* Checking / Downloading / Extracting / Success / Failed */ }
```

清单地址没有默认值，留空会在 `init()` 当场失败。发什么、信谁，是接入方自己的决定。

```bash
# 自建密钥对。仓库里的 demo-p256.* 是公开的演示密钥（见 tools/tokay-sign/README.md），别上生产。
bash tools/tokay-sign/sign.sh keygen ~/tokay-keys/my-p256.key ~/tokay-keys/my-p256.pub
bash tools/tokay-sign/sign.sh pubxy  ~/tokay-keys/my-p256.pub   # 裸 X/Y → 替换内置公钥常量

# 签「你即将下发的那份」清单：App 取的是 <configUrl>.sig，签名要覆盖该 URL 返回的原始字节。
bash tools/tokay-sign/sign.sh sign ~/tokay-keys/my-p256.key serve/sdkconfig.json
```

### 内核随包（不需要服务端，也不需要签名私钥）

```kotlin
GeckoSdk.init(application, kernelSource = KernelSource.Packaged)
```

```bash
sh gradlew :demo:assembleDebug -PpackageGeckoKernel=true
```

同一个 `.7z` 随 APK 的 assets 一起发，首次运行从包里解压。这个模式要求声明 `ndk.abiFilters`，没有就
直接让构建失败：三个 ABI 的包都塞进去，APK 会比内核留在 `lib/` 里还大，那就白做了。ABI splits 同样
被拒。声明 `Packaged` 但包里没有内核包、或声明 `Remote` 却带着内核包，都会报错，不去猜。

## 构建与验证

```bash
sh gradlew :tokay:test :demo:assembleDebug                # 构建 + JVM 单元测试
sh gradlew :demo:verifyGeckoviewInstrumentationTargets    # 每次 preBuild 也会自动跑
sh gradlew -p tokay-asm test                              # tokay-asm 是 includeBuild，要单独跑

bash tools/tokay-compat-matrix.sh 140.0.20250707120347 147.0.20260212191108   # 多版本矩阵
bash tools/gecko-package/serve-local.sh && adb reverse tcp:28080 tcp:28080    # 本地「远程下发」联调

bash tools/tokay-device-check.sh            # 真机端到端验收：清数据 → 从零下载 → 断言外置加载
bash tools/tokay-device-check.sh --packaged # 不起发布服务、不建 adb reverse
```

内核外置的失效只在真机上暴露，构建永远绿的。所以改过构建链路一定要跑 `tokay-device-check.sh`，
退出码 0 才算过。

## 限制

* 接入方必须应用那个 Gradle 插件。没有预插桩的 AAR，考虑过，否决了。
* OTA 模式首次启动必须联网。随包模式不用联网，但代价换到 APK 体积上，首次运行仍要解压约 175 MiB，
  每次发版都得重新带上这个包。
* 运行时下载可执行代码可能与 Google Play 政策冲突，上架前请自行做评估。
* 不是 `android.webkit` 的 1:1 等价物。对话框、文件上传、权限询问、`data:` 导航、企业根证书这些
  `WebViewClient` / `WebChromeClient` 的对应能力还没做。门面会如实上报能力位，不会静默变成空操作。
* JS 桥不做来源门禁。调了 `addJavascriptInterface` 之后，当前页面和同源脚本就能调那个对象上任何
  `@JavascriptInterface` 公有方法，原生侧唯一的门禁是注解加参数类型白名单，不认 URL。跨域 iframe
  到不了桥，剩下的风险面是 XSS 和你导航到哪去。这个页面该不该挂桥，由宿主按 URL 自己判断。
* 系统 WebView 适配器只是兜底，让视图降级而不是直接失败，别当成等价实现。
* 构建期断言是源码级的。第二道只证明类还在那个包，不证明发射出去的引用一定解析得到，后者靠真机验收。

## 兼容性

| GeckoView | 插桩目标 | 验证方式 |
| --- | --- | --- |
| 140.0.20250707120347 | 6/6 | 构建期断言 |
| 147.0.20260212191108 | 6/6 | 构建期断言 + 真机 |

## 许可

库主体 Apache-2.0；ASM 插桩插件（`tokay-asm/`）MPL-2.0，因为它派生自 GeckoView 的集成代码。
见 `LICENSE` 和 `NOTICE`。
