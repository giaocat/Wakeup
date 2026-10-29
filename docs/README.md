# Wakeup 官网（docs/）

单文件静态站：`index.html`，无构建步骤、无外部依赖（图标是内联 SVG，界面预览是纯 CSS）。

## 本地预览

直接双击 `index.html` 即可；或起一个静态服务（推荐，行为与线上一致）：

```powershell
# 在仓库根目录
python -m http.server 8791 --bind 127.0.0.1 --directory docs
# 打开 http://127.0.0.1:8791/
```

## 部署到 Cloudflare Pages

1. Cloudflare Dashboard → **Workers & Pages** → **Create** → **Pages** → **Connect to Git**；
2. 选择仓库 `giaocat/Wakeup`，分支 `main`；
3. 构建设置：
   - **Framework preset**：`None`
   - **Build command**：留空（不构建）
   - **Build output directory**：`docs`
4. 保存并部署；之后每次推 `main` 会自动重新部署。

`docs/_headers` 会被 Cloudflare Pages 识别并生效（安全头 + HTML 不缓存），`robots.txt` 与 `404.html` 也一并发布。

自定义域名建议用 `wakeup.giaocat.com`（DNS 由 Cloudflare 托管时，Pages 里点一次即可绑定并自动签发证书）。

## 发版时要同步的地方

- `index.html` 里 4 个下载按钮指向 **fastgit 加速直链**，格式：

  ```
  https://fastgit.giaocat.com/https://github.com/giaocat/Wakeup/releases/download/v<版本>/wakeup-<phone|wear>-<版本>.apk
  ```

  发新版本后把其中的版本号与文件名改掉即可（`fastgit.giaocat.com` 实测可直接下到 APK，返回 `application/vnd.android.package-archive`）。
- 更新日志区块：把新版本挪到「最新发布」，旧版本加一行摘要。
