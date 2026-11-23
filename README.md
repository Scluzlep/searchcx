# searchcx

基于 Modern Xposed API 102 的只读网页 DOM 与屏幕框选 AI 悬浮助手。

## 功能

- **读 DOM**：基于 `io.github.libxposed:api:102.0.0` 与 `service:102.0.0`，通过 `META-INF/xposed/module.prop` 与 `java_init.list` 注册 `WebViewDomModule`，支持原生 `WebView` 与 TBS/X5 只读提取及热重载。按「选区 > 页面题目 > 正文兜底」优先级提取题干、选项、KaTeX/MathJax/MathML 公式为 LaTeX，支持独立内联 `<svg>` 图形捕获与公式图/题图多模态附带。
- **分片 IPC 与宿主鉴权抓图**：支持超大 DOM 快照广播分片重组（`DomChunkAssembler`），并在宿主 WebView 进程内携带 Cookie / Referer / User-Agent 预取需登录鉴权的图片并转码为白底内联数据（Cookie 不写入日志或广播）。
- **加密混淆字体兼容**：自动检测学习通 `font-cxsecret` 等混淆字体，尝试离屏 Canvas 渲染题干/选项视觉图兜底，并跳过失效文本查题、提供悬浮窗「截屏框选」快捷引导。
- **截屏框选**：通过 Android `MediaProjection` 单帧截图与自由框选裁剪图像，直接发送给多模态 AI，不回写或修改原网页。
- **悬浮窗与上下文预览**：收起样式可在主页固定为无文字圆形按钮或白色带阴影小点；支持贴边半隐、轻点展开、长按抓取提问、模型切换、高度切换、透明度调节与 Markdown/公式渲染。引用条支持展开「预览」卡片，自由切换抓取作用域（选区/按题/完整DOM）、多题筛选与单图剔除；支持截图与 DOM 附图混排索引提示，并在多轮对话时自动裁剪早期轮次附图以节省上下文。
- **题库检索（tikuAdapter）**：支持「内置聚合」与「远端服务」双模式。默认仅启用免费题库源（内置默认 `icodef`、`buguake`（不挂科），远端服务另可使用 `local`）；收费题库源（言溪、题库海、万能、爱点、柠檬）默认关闭，按需勾选并填写对应 Token 后使用 AndroidKeyStore 加密保存。查题前自动执行 LaTeX 公式扁平化规范化并去除图片占位符，同时提供本地持久化 LRU + TTL 命中缓存（24 小时 / 200 条）；悬浮窗支持独立「查题库」以及「提问时先查题库」和「全命中时跳过 AI」。
- **多服务商与 Codex OAuth**：支持 OpenAI Chat、OpenAI Responses、ChatGPT Codex OAuth PKCE 与自定义 OAuth PKCE；凭据仅保存在本进程 AndroidKeyStore。

## 本地构建

- 工作区：`D:\antigproj\searchcx`
- 环境：`compileSdk 37` / `targetSdk 35` / `minSdk 28`，`AGP 9.4.0`，`Gradle 9.7.0`，`JDK 21`

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.6"
E:\Gradle\gradle-9.7.0\bin\gradle.bat -p D:\antigproj\searchcx testDebugUnitTest assembleDebug --no-daemon
```

## CI/CD 与 SigHelper 签名发布

工作流位于 `.github/workflows/build-release.yml`：

1. 使用 `SigHelper` 生成或同步仓库签名密钥到 GitHub Actions Secrets：

```bash
sudo android-sig gen <owner>/<repo>
# 或迁移旧目录并同步
sudo android-sig sync <owner>/<repo>
```

写入目标仓库的 4 个 Secrets：
- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_PASSWORD`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

2. 触发构建与发布：
- `pull_request` / `push main`：运行单元测试与 Debug/Release 构建，校验 API 102 清单并上传构建产物；推送 `main` 时如未配置签名 Secrets 仅产出未签名包。
- 推送版本标签自动打包签名并发布 GitHub Release：

```bash
git tag v0.3.0 && git push origin v0.3.0
```

- 也可在 Actions 页面通过 `workflow_dispatch` 手动填入 `tag` 并勾选 `publish_release` 发布。

3. 校验下载的发布 APK 签名：

```bash
android-sig veri <owner>/<repo> --apk searchcx-v0.3.0.apk
```
