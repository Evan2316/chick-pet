# 🐥 小鸡桌宠 (ChickPet)

一只浮在手机桌面上的黄色像素小鸡。**没有服务器、没有云、不联网**，全程跑在本机。

## 功能

| 行为 | 说明 |
|---|---|
| 悬浮 | 透明背景，浮在所有应用之上，不挡手指操作 |
| 拖动 | 按住拖到屏幕任意位置 |
| 点击 | 点一下换下一个表情，并蹦一句话 |
| 自动游走 | 静置一会儿自己挪位置 |
| 冒话 | 隔一阵头顶弹一句台词，几秒收起 |
| 自启 | 开机后自己起来（BootReceiver） |

## 工程结构

```
chick-pet/
├─ .github/workflows/build.yml      # GitHub Actions：编译 + 自动生成并锁定签名 keystore
└─ android/
   ├─ settings.gradle
   ├─ build.gradle
   ├─ gradle.properties
   └─ app/
      ├─ build.gradle
      └─ src/main/
         ├─ AndroidManifest.xml
         ├─ java/com/chickpet/
         │   ├─ MainActivity.java            # 入口页（自动拉起服务）
         │   ├─ ChickService.java            # 前台服务 + 悬浮窗 + 拖动 + 游走
         │   ├─ TouchThroughWebView.java     # 不吃触摸的 WebView（关键）
         │   └─ BootReceiver.java            # 开机自启
         └─ assets/
             ├─ pet.html                     # 形象 + 台词 + 气泡（改形象只改这里）
             └─ config.json                  # 大小配置（改完重启服务即生效）
```

## 三个关键坑（照指南）

1. **前台服务权限**：`FOREGROUND_SERVICE` + `foregroundServiceType="specialUse"` 少一个就崩。
2. **WebView 吃触摸**：必须用 `TouchThroughWebView`，`onTouchEvent` 里 `super()` 之后 **`return false`**。
3. **签名固定**：Actions 首次构建会生成 `android/keystore.jks` 并提交回仓库，之后每次都用它签名 → 能直接覆盖安装。
   （备份好这个文件，丢了就只能卸载重装。）

## 改形象

只改 `android/app/src/main/assets/pet.html`：
- 在 `STATES` 里加一个 key → SVG 字符串（SVG 必须带 `xmlns` 和 `viewBox`）
- 把 key 加进 `ORDER`

## 改大小

改 `android/app/src/main/assets/config.json` 里的 `size`（单位 dp，40~400），重启服务生效。

## 编译

推送到 `main` 或手动触发 Actions，产物在 Artifacts 里的 `chickpet-apk`。

## 装到手机

```sh
# 1) 拷出来（sdcard 直接装会被 AVC 拦）
cp /sdcard/Download/chickpet.apk /data/local/tmp/a.apk
# 2) 装
pm install -r /data/local/tmp/a.apk
# 3) 补悬浮窗权限（卸载重装后必须补）
appops set com.chickpet SYSTEM_ALERT_WINDOW allow
# 4) 拉起
am start -n com.chickpet/.MainActivity
```
