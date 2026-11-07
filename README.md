# jenkins-atom-ftp-publish

把文件传到 FTP/FTPS，保持目录结构。

publish-over-ftp 与 ftppublisher 都在 Java 里自己实现 FTP 客户端；这里用 curl，构建机上都有，被动模式、TLS、建目录都不用我们写。

**密码不进进程列表**：写进一个 0600 的临时 netrc 文件让 curl 读，传完立刻删除。（把 `--user user:pass` 放命令行会出现在 `ps` 和构建日志里。）

- 每个文件一次 STOR，路径相对 `baseDir`（默认 workspace）
- `secure=true` 走 FTPS（`--ssl-reqd`，强制 TLS）
- 530/550/登录失败归 `USER`，其余归 `ENVIRONMENT`

```bash
mvn verify
```

`mvn verify` 打包 jar，再用 failsafe 在 `127.0.0.1:<port>` 起一个**真的 FTP 协议服务**（`tools/fake_ftp.py`，PASV/STOR/SIZE），传完回磁盘逐字节对账。
