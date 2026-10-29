# Wakeup 官网（docs/）

单文件静态站：`index.html`，无构建步骤、无外部依赖（图标是内联 SVG，界面预览是纯 CSS）。

## 本地预览

直接双击 `index.html` 即可；或起一个静态服务（推荐，行为与线上一致）：

```powershell
# 在仓库根目录
python -m http.server 8791 --bind 127.0.0.1 --directory docs
# 打开 http://127.0.0.1:8791/
```

## 部署到 Cloudflare

站点是纯静态文件，两种接法都行。仓库现在按 **Workers（静态资源）** 配好，见根目录 `wrangler.jsonc`。

### 方式 A：Workers（当前配置）

`wrangler.jsonc` 里已声明 `assets.directory = "./docs"`、`not_found_handling = "404-page"`，所以不需要构建：

1. Cloudflare → **Workers & Pages** → 选中 `wakeup` 这个 Worker → **Settings → Build**；
2. **Build command** 留空，**Deploy command** 用 `npx wrangler deploy`；
3. 推 `main` 即自动部署，PR 会跑预览部署。

### 方式 B：Pages

1. **Create → Pages → Connect to Git**，选 `giaocat/Wakeup`、分支 `main`；
2. Framework preset `None`，**Build command 留空**，**Build output directory 填 `docs`**；
3. 保存并部署。

`docs/_headers` 在这两种方式下都会生效（安全头 + HTML 不缓存），`robots.txt`、`404.html` 一并发布。

自定义域名建议用 `wakeup.giaocat.com`（DNS 在 Cloudflare 时点一次即可绑定并自动签发证书）。

## 发版时要同步的地方

- `index.html` 里 4 个下载按钮指向 **fastgit 加速直链**，格式：

  ```
  https://fastgit.giaocat.com/https://github.com/giaocat/Wakeup/releases/download/v<版本>/wakeup-<phone|wear>-<版本>.apk
  ```

  发新版本后把其中的版本号与文件名改掉即可（`fastgit.giaocat.com` 实测可直接下到 APK，返回 `application/vnd.android.package-archive`）。
- 更新日志区块：把新版本挪到「最新发布」，旧版本加一行摘要。
