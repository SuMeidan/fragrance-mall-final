# SCENT API

Java 21 / Spring Boot / MyBatis / MySQL 8.4。完整启动见 [项目首页](../../README.md)，配置见 [运行说明](../../deploy/README.md)。

数据库结构在 `src/main/resources/db/migration`，共 V1—V5 五次迁移；开发示例在 `src/main/resources/db/demo/products.sql`。开发模式且数据库为空时导入 37 件示例商品，生产数据需要另行管理。

## 后端测试

准备一个与演示或生产环境完全分开的 MySQL 数据库，名称以 `_it` 结尾。设置该测试库的环境变量，然后在本目录执行 Maven Wrapper：

```powershell
$env:SCENT_DB_URL='jdbc:mysql://127.0.0.1:3306/scent_local_it?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true'
$env:SCENT_DB_USER='your_test_user'
$env:SCENT_DB_PASSWORD='your_test_password'
.\mvnw.cmd -B -ntp verify
```

测试会写入和清理测试数据。仅使用专用测试库，并清空真实支付宝、百炼和 MCP 凭据。结果位于 `target/surefire-reports`、`target/failsafe-reports`，覆盖率位于 `target/site/jacoco`。

接口配置入口是 [application.properties](src/main/resources/application.properties)。OpenAPI 默认关闭；本地需要时设置 `SCENT_DOCS=true`。不要把管理员账号、访问令牌或私钥提交进 Git。
