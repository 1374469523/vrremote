# 手柄遥控

把华为 VR 手柄（HUAWEI CF20）当成普通安卓手机的遥控器和鼠标用。

手柄走私有 BLE 协议，系统没有任何 profile 认领它，所以必须由 App 自己连 GATT、
自己解析报文，再把输入翻译成系统事件。翻译这一步走 UHID：借 Shizuku 拿到 shell
身份，打开 `/dev/uhid` 建一个虚拟键盘、一个消费者控制设备和一个虚拟鼠标。系统会
把它当成真正的外接键鼠，光标由系统自己绘制，不需要无障碍服务，也不需要 root。

## 三种模式

按一下手柄扳机键循环切换，切换时弹一条提示：

| 模式 | 触控板 | 体感 | 确认键 |
| --- | --- | --- | --- |
| 普通模式 | 上/下/左/右 → 方向键 19/20/21/22 | 不处理 | 回车 66 |
| 飞鼠1 | 相对位移驱动光标 | 不处理 | 鼠标左键 |
| 飞鼠2 | 不驱动光标 | 四元数增量驱动光标 | 鼠标左键 |

返回 4、主页 3、音量 24/25 在三种模式下都一样。

## 使用前提

手机上装了 [Shizuku](https://shizuku.rikka.app/) 并且已经启动，然后在 App 里授权。
没有 Shizuku 时手柄测试页仍然可用，但遥控和光标不会生效。

模式切换的提示条是 App 自己画的悬浮窗，需要的 `SYSTEM_ALERT_WINDOW` 权限由
Shizuku 自动授予（`appops set … allow`），不需要手动去设置里点。

### 为什么不用系统 Toast

Android 12 之后 Toast 由 SystemUI 渲染。测试机上装了一个 DeX 模块
（`com.flexunlock.dexlsp`）hook 了 `NotificationManagerService.enqueueToastForDex`，
经它转发的 Toast 文本第一个字会被改掉一个字节（「指尖鼠标」变成「挈尖鼠标」）。
App 里的字符串本身是好的，所以提示条改成自绘，绕开这条通道。

## 工程结构

| 模块 | 作用 |
| --- | --- |
| `:protocol` | 20 字节报文的解析，纯 Kotlin，带真机样本回归测试 |
| `:remote` | 三种模式的映射引擎，纯 Kotlin，可单测 |
| `:app` | 蓝牙连接、Shizuku + UHID 注入、前台服务、界面 |

## 构建

```powershell
$env:JAVA_HOME='D:\Code\华为VR\android-sdk\jdk17tmp\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\Code\华为VR\android-sdk'
gradle -p D:\Code\vrremote-kotlin :app:assembleDebug
gradle -p D:\Code\vrremote-kotlin :protocol:test :remote:test
```

工程真实位置是 `D:\Code\华为VR\vrremote-kotlin`；AGP 处理不了中文路径，所以构建
统一走 junction `D:\Code\vrremote-kotlin`。

## 协议

报文格式、特征值、体感解码和实测结论记在 [docs/controller-protocol.md](docs/controller-protocol.md)。

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。UHID 与 HID 描述符的写法参考了
[scrcpy](https://github.com/Genymobile/scrcpy)（Apache-2.0）和
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API)（Apache-2.0）。
