# PadSSH

面向 Android 平板的轻量 SSH + SFTP 客户端（MVP）。

## 功能

- 主机增删改查（名称、地址、端口、用户名；密码或私钥文本）
- SSH 交互终端（PTY，简易 ANSI 剥离与回滚缓冲）
- 辅助键：Esc、Tab、Ctrl、方向键、Ctrl-C
- SFTP：列目录、下载到应用目录、系统文件选择器上传
- TOFU 主机密钥信任（首次连接确认，变更时告警）
- 中文界面；平板宽屏下列表明细分栏

## 系统要求

- Android 8.0+（minSdk 26）
- 面向 Android 16（API 36）编译与目标
- **说明**：当前 Android SDK 仓库尚无 `platforms;android-37`，因此 `compileSdk` / `targetSdk` 暂为 **36**。应用未锁定方向，兼容大屏与多窗口。

## 环境准备

```bash
# JDK 17+
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

# 安装 SDK 组件示例
sdkmanager "platform-tools" "build-tools;36.1.0" "platforms;android-36"
yes | sdkmanager --licenses
```

在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

## 构建

```bash
cd padssh-android
./gradlew assembleDebug
```

调试包输出：

```
app/build/outputs/apk/debug/app-debug.apk
```

便捷副本（构建脚本/本仓库约定路径）：

```
artifacts/PadSSH-debug.apk
```

安装到设备：

```bash
adb install -r artifacts/PadSSH-debug.apk
```

## 技术栈

- Kotlin、Jetpack Compose、Material 3
- Room 持久化主机与 TOFU 密钥
- [sshj](https://github.com/hierynomus/sshj) + BouncyCastle

## 已知限制

- 终端为简化实现：剥离 CSI，不做完整 VT100 渲染/颜色
- 私钥以明文存于本地 Room 数据库（MVP，未做加密密钥库）
- SFTP 下载写入应用外部文件目录，需自行用文件管理器查看
- API 37 平台包可用后可将 compile/target 升至 37

## 许可

本项目为最小可用示例，按需自行使用与修改。
