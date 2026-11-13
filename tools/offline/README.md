# Fcitx5 离线语音个人版

面向 arm64 Android 手机（本次目标设备：iQOO Neo 7）。本版使用独立应用 ID `org.fcitx.fcitx5.android.offline`（debug 构建额外带 `.debug`），可与官方版本并存。

包含 Rime、雾凇拼音，以及 sherpa-onnx 本地中英双语流式识别。语音推理使用 CPU，不依赖 Google 服务。最终 APK 应不含 INTERNET 权限。

## 使用

1. 安装 APK，打开“Fcitx5 离线语音”，按引导启用并选择输入法。
2. 首次启动自动把随包的雾凇配置复制到本版的 Rime 用户目录，默认使用雾凇全拼。词库首次部署可能需要等待。
3. 轻点空格正常输入。按住约 300 毫秒，等空格显示“正在聆听”后说话；松手停止录音，最终文字写入开始录音的输入框。
4. 第一次使用会申请麦克风权限；授权后返回输入框重新按住空格。
5. 切换输入框、关闭键盘、系统取消触摸或输入其他文字会取消本次语音。单次最长 60 秒。密码框禁用语音。
6. 模型预置在 APK 中，安装后不需要下载。请在飞行模式下验证；中英混说、专名和缩写的准确率仍受模型影响。

本版使用自己的设置和词库目录，不会读取、覆盖或删除官方版的数据。原版里个人词库、主题和额外修改不会自动迁移。本版已经附带一份新的雾凇配置。录音只在内存中处理，不写音频文件。

## 打包输入

- Fcitx5 与 Rime：本仓库及其固定版本子模块，保留原项目许可证。
- sherpa-onnx Android SDK：v1.12.14；从官方 AAR 中只保留 arm64-v8a 的 JNI 库与公共类。
- 语音模型：`csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` 的 `encoder-epoch-99-avg-1.int8.onnx`、`decoder-epoch-99-avg-1.onnx`、`joiner-epoch-99-avg-1.int8.onnx`、`tokens.txt`。在 assets/offline-voice 中分别命名为 `encoder.int8.onnx`、`decoder.onnx`、`joiner.int8.onnx`、`tokens.txt`。
- 雾凇拼音：iDvel/rime-ice，提交 `927e1cf1d3eeb9bf4be85e3f684dba4dd485b6f6`。保留 LICENSE，随包安装 Lua、词库、OpenCC 和方案配置；默认启用 rime_ice。

上游来源：

- https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.14
- https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
- https://github.com/iDvel/rime-ice/tree/927e1cf1d3eeb9bf4be85e3f684dba4dd485b6f6

## 本地构建

使用项目所需的 JDK、Android SDK/NDK/CMake 与 gettext/ECM，初始化全部子模块。将 SDK AAR 放到 `app/libs/sherpa-onnx-1.12.14-arm64.aar`，准备上述四个模型文件，将固定提交的雾凇归档放到 `.local-build/rime-ice.zip`。

```sh
./gradlew :plugin:rime:assembleDebug -PbuildABI=arm64-v8a
python tools/offline/prepare_bundle.py --rime-apk <Rime插件APK路径>
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PbuildABI=arm64-v8a
```

prepare_bundle.py 将 Rime APK 的原生库和资源合入主程序，不需要在手机上安装这个中间插件 APK。模型、词库、SDK 和编译产物不提交到 Git。

项目原有的 269 条资源翻译、字符串格式及 tint 错误记录在 `app/lint-baseline.xml`，不包含新增语音功能的问题，也不屏蔽警告。语音提示提供英文和简体中文，其他界面语言回退到英文。构建同时修复了 Gradle 9 下 Lint 读取生成资源的任务依赖，并将旧版主题测试的预期更新为已有的 2.0→2.1 迁移行为；主题生产代码没有改动。

`validate_model.py` 使用上游四段 WAV 检查流式输入、松手后的尾部补齐和中英文识别结果；它在桌面 CPU 上执行，不能替代手机麦克风、触摸与耗时的实机验证。
