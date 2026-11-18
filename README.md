# Fcitx5 for Android · 离线语音版

基于上游 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的个人修改版：加入**纯端侧的中英双语语音输入**，并内置 [雾凇拼音](https://github.com/iDvel/rime-ice)，开箱即用。

**按住空格说话，松手或停顿后出字，全程不联网。**

## 特性

| 功能 | 说明 |
|---|---|
| 长按空格语音 | 轻点空格照常输入空格；按住约 300ms 进入聆听，松手出字 |
| 语音全程离线 | 识别、解码都在本机完成；只有「语音模型 / 词库」页面点下载时才联网（APK 声明了 `INTERNET`） |
| 可联网下载 | 「语音模型」「词库与方案」页可从远程 catalog 下载模型与词库，支持进度与 SHA-256 校验 |
| 词库管理 | 列出 Rime 目录里的全部方案，一键切换（改 `schema_list`）并重新部署；下载的词库可卸载 |
| 中英混说 | 流式 Zipformer 双语模型，边说边解码，松手后只补尾部音频 |
| 静音自动结束 | 说完停顿自动收尾，时长可在 300-2000ms 间调整 |
| 可切换的语音模型 | “设置 → 语音模型”里可在中英双语、中文轻量、导入/下载的模型之间切换 |
| 卸载 / 导入模型 | 导入的模型可以卸载腾出空间；也支持从本地 `.zip` 导入自己的 sherpa-onnx 模型 |
| 候选栏横向滑动 | 候选多时左右滑动候选栏继续找字；下拉候选窗照常可用（默认开启） |
| 边打边算 | 输入 `3+3` 时候选区出现 `3+3=6`；不点它输入框保持原样，点了才替换成 `3+3=6` |
| 上滑取消 | 长按中向上滑出即可放弃这次录音 |
| 重说 | 结果未被修改时再按住空格，会撤回上次文字重新录音 |
| 中文数字规范化 | `二零一一年` → `2011年`、`十五米` → `15米`、`百分之五十` → `50%` |
| 内置雾凇拼音 | 首次启动自动部署，默认全拼方案，词库包含 Lua、OpenCC 与英文词库 |
| 隐私 | 录音只在内存中处理，不写音频文件；密码框禁用语音 |

## 与官方版的差异

这些都是刻意的改动，请不要当成上游缺陷：

- 应用 ID 为 `org.fcitx.fcitx5.android.offline`，可与官方版**并存**，使用独立的配置与词库目录。原版数据不会被读取或覆盖。
- 长按空格的默认行为改为语音输入。如需原行为，在“设置 → 键盘 → 空格键长按行为”中改回。
- 内置一份雾凇拼音；原版的个人词库、主题与自定义配置**不会自动迁移**。
- 只打包 `arm64-v8a`，只为减小体积，不支持 32 位设备。
- APK 体积约 285 MB 起，其中中英双语语音模型约 190 MB；可选的中文轻量模型另加约 20 MB。
- 候选栏支持左右滑动并按批加载更多候选，与原有下拉候选窗并存（“设置 → 键盘 → 候选栏横向滑动”）。
- 语音模型默认使用随包的中英双语模型，可在“设置 → 语音模型”切换或卸载导入的模型。

## 使用

1. 安装 APK，打开“Fcitx5 离线语音”，按引导启用并切换到该输入法。
2. 首次启动会部署雾凇词库，等待部署完成。
3. 首次长按空格会申请麦克风权限；允许后返回输入框，再次按住空格。
4. 看到“正在聆听”后说话，松手或停顿即可上屏。
5. 可以在飞行模式下验证完整流程。

## 边打边算（计算器候选）

在输入框里打出简单的四则运算（`+ - * /`，也认 `× ÷` 和全角数字），候选区会出现一条
`表达式=结果` 的候选，例如 `3+3` → `3+3=6`：

- **不点它**：输入框里仍然是你打的 `3+3`，一个字符都不会被改。
- **点它**：把光标前那段表达式删掉，替换为 `3+3=6`。
- 只在引擎自己没有候选时出现，不会顶掉拼音候选；超过 6 个数、超过 24 个字符、
  除以 0、日期（`2024-01-01`）都不会出提示。
- 小数用 `BigDecimal` 计算，`0.1+0.2` 显示 `0.3`；除不尽保留 10 位后截断。

实现位置：`input/calculator/ArithmeticSuggestion.kt`（纯逻辑，有单测）、
`InputView.showArithmeticSuggestion()`（把提示塞进候选流）、
`FcitxInputMethodService.refreshArithmeticSuggestion()/applyArithmeticSuggestion()`（触发与替换）。

## 让常用的字词优先出现

完全交给输入法引擎，应用侧不做任何重排。

- 雾凇拼音用的是 librime 的 `script_translator`，`translator/enable_user_dict` 取 librime 默认值
  `true`，`db_class` 默认 `userdb`，所以上屏内容会写进 `data/rime/rime_ice.userdb/`，下次输入时
  由引擎自己参与排序。
- 引擎的规则是“编码更贴合的优先、同长度时用户词优先”，不是按使用次数排序；词库里的字词权重
  是词频量级（例如 `的 de` 是 `76938354`），用户词权重只有个位到十几，两者不在一个量级。
- 想让个人常用词更靠前，改 Rime 配置或词库，而不是改应用：

  ```yaml
  # data/rime/rime_ice.custom.yaml
  patch:
    translator/enable_user_dict: true   # 确认没有被关掉
    translator/initial_quality: 1.2     # 与英文词库的相对权重
  ```

  固定置顶的词直接写进 `custom_phrase.txt`（雾凇给这个翻译器的权重是 99，永远排在前）。
  清空个人习惯就是删掉 `data/rime/rime_ice.userdb/` 后重新部署。

## 联网下载（语音模型与词库）

两个设置页都能从一份 JSON 目录里拉取可选内容，下载只在**你点具体条目时**才发生，不会后台偷跑：

- 「语音模型 → 可下载的语音模型」：下载到 `files/voice-models/<id>/`，完成后自动切换。
- 「词库与方案 → 可下载的词库」：下载到 `data/rime/`，写一份清单便于卸载，然后调 `FcitxDaemon.restartFcitx()`
  触发 librime 重新部署（也可以点该页的「重新部署 Rime」）。
- 「刷新下载列表」拉取 `RemoteCatalog.CATALOG_URL`，缓存在 `files/catalog.json`；离线时继续用缓存或
  APK 内置的 `assets/catalog.json`。

**目录格式**（改 `RemoteCatalog.CATALOG_URL` 或在设备上放一份到
`/sdcard/Android/data/org.fcitx.fcitx5.android.offline/files/catalog.json` 即可覆盖）：

```json
{
  "version": 1,
  "entries": [
    {
      "id": "zh-14m-zipformer", "kind": "voice", "name": "中文轻量 Zipformer（14M）",
      "note": "说明文字", "backend": "ZipformerTransducer",
      "files": [
        { "url": "https://…/encoder-epoch-99-avg-1.int8.onnx", "name": "encoder.int8.onnx",
          "sha256": "可选，建议填", "size": 0 }
      ]
    },
    {
      "id": "rime-flypy", "kind": "dict", "name": "雾凇·小鹤双拼方案",
      "schema": "double_pinyin_flypy",
      "files": [
        { "url": "https://…/double_pinyin_flypy.schema.yaml", "name": "double_pinyin_flypy.schema.yaml" }
      ]
    }
  ]
}
```

- `kind=voice`：`name` 是模型目录内的文件名，必须包含 encoder / decoder / tokens（+ 可选 joiner）。
- `kind=dict`：`name` 是相对 Rime 目录的路径，`schema` 是要启用的方案 id（会写进 `default.custom.yaml`
  的 `schema_list` 并置顶）。
- `size`/`sha256` 可留空；填了就会在下载后校验，不匹配则丢弃。

## 构建

依赖与上游一致：JDK 21、Android SDK 36、NDK 28、CMake 3.31.6，以及 `gettext`、`extra-cmake-modules`。

模型、词库与 sherpa-onnx 的 AAR **不入库**，需要按 [`tools/offline/README.md`](tools/offline/README.md) 准备好：

- `app/libs/sherpa-onnx-1.12.14-arm64.aar`
- `app/src/main/assets/offline-voice/`（encoder/decoder/joiner/tokens）
- `app/src/main/assets/offline-voice/zh-14m/`（可选的中文轻量模型，缺失时该模型不显示）
- `.local-build/rime-ice.zip`（固定提交）

然后：

```sh
git submodule update --init --recursive

# 1. 构建 Rime 插件，取出原生库与资源并合入主程序
./gradlew :plugin:rime:assembleDebug -PbuildABI=arm64-v8a
python tools/offline/prepare_bundle.py --rime-apk plugin/rime/build/outputs/apk/debug/*.apk

# 2. 构建主程序
./gradlew :app:assembleRelease :app:testDebugUnitTest -PbuildABI=arm64-v8a
python tools/offline/verify_apk.py app/build/outputs/apk/release/*.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.1.0" \
  --readelf "$ANDROID_HOME/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  --output dist/apk-verification.json
```

`tools/offline/` 下另有 `validate_model.py`（用上游 WAV 校验模型与上屏文本）和构建说明。

## 持续集成与签名

[`.github/workflows/build.yml`](.github/workflows/build.yml) 在每次推送和手动触发时自动下载模型与依赖、编译、跑单元测试、签名并上传 APK。

签名材料**不进仓库**，通过仓库 Secret 注入（构建脚本读取环境变量）：

| Secret | 内容 |
|---|---|
| `SIGN_KEY_BASE64` | PKCS12/JKS 密钥库的 base64 |
| `SIGN_KEY_PWD` | 密钥库密码 |
| `SIGN_KEY_ALIAS` | 密钥别名 |

没有配置这些 Secret 时，构建仍会产出**未签名**的 APK 供检查，但无法直接安装升级。

> 同一个包名必须始终用同一份密钥签名，否则只能卸载重装、数据丢失。请自行备份密钥库。

## 数据与许可

本项目是上游项目的修改版，遵循 **LGPL-2.1-or-later**，修改部分的版权归本仓库贡献者；上游版权归 fcitx5-android 贡献者。

- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) — 主程序与插件框架（LGPL-2.1-or-later）
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) v1.12.14 — 端侧推理运行时（Apache-2.0）
- [onnxruntime](https://github.com/microsoft/onnxruntime) — 推理后端（MIT）
- [sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20) — 中英双语流式模型（Apache-2.0）
- [rime-ice 雾凇拼音](https://github.com/iDvel/rime-ice) — 输入方案与词库（Apache-2.0）
- 许可证全文随包放在 `app/src/main/assets/offline-notices/`

## 已知限制

- 语音模型的数字、专名和缩写仍有错误率；识别结果不带标点，暂未接标点恢复模型。
- 中文数字规范化刻意保守：`一点二亿` 这类“小数 + 万亿”保持原样，`一点小事` 不会被改写。
- 静音阈值基于信号能量，个别机型麦克风增益偏大时需要调 `EnergyVad` 的参数。
- 尚未在真机上完成麦克风、手势与耗时验证。
