# Fcitx5 for Android · 离线语音版

基于上游 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的个人修改版：加入**纯端侧的中英双语语音输入**，并内置 [雾凇拼音](https://github.com/iDvel/rime-ice)，开箱即用。

**按住空格说话，松手或停顿后出字，全程不联网。**

## 特性

| 功能 | 说明 |
|---|---|
| 长按空格语音 | 轻点空格照常输入空格；按住约 300ms 进入聆听，松手出字 |
| 完全离线 | 模型随包内置，运行时不需要网络，应用未声明 `INTERNET` 权限 |
| 中英混说 | 流式 Zipformer 双语模型，边说边解码，松手后只补尾部音频 |
| 静音自动结束 | 说完停顿自动收尾，时长可在 300-2000ms 间调整 |
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
- APK 体积约 285 MB，其中语音模型约 190 MB。

## 使用

1. 安装 APK，打开“Fcitx5 离线语音”，按引导启用并切换到该输入法。
2. 首次启动会部署雾凇词库，等待部署完成。
3. 首次长按空格会申请麦克风权限；允许后返回输入框，再次按住空格。
4. 看到“正在聆听”后说话，松手或停顿即可上屏。
5. 可以在飞行模式下验证完整流程。

## 构建

依赖与上游一致：JDK 21、Android SDK 36、NDK 28、CMake 3.31.6，以及 `gettext`、`extra-cmake-modules`。

模型、词库与 sherpa-onnx 的 AAR **不入库**，需要按 [`tools/offline/README.md`](tools/offline/README.md) 准备好：

- `app/libs/sherpa-onnx-1.12.14-arm64.aar`
- `app/src/main/assets/offline-voice/`（encoder/decoder/joiner/tokens）
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
