"""SCENT V1 isolated Windows runtime. Never resets databases or adopts unknown processes."""

import argparse
import ctypes
import json
import os
import shutil
import secrets
import signal
import socket
import subprocess
import sys
import time
import zipfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT / "apps/api"
DB_PORT, API_PORT, DATABASE = 13310, 28049, "scent_v1"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "action",
    choices=[
        "init-db",
        "seed-catalog",
        "mcp-init",
        "build",
        "verify",
        "start",
        "restart-api",
        "rollback-api",
        "web-install",
        "web-build",
        "web-start",
        "web-stop",
        "preview-start",
        "preview-stop",
        "browser-check",
        "status",
        "stop",
    ],
)
parser.add_argument("--config", type=Path, default=PROJECT / "runtime/v1/config.json")
parser.add_argument(
    "--production",
    action="store_true",
    help="Disable development seeds/OpenAPI and use the static preview origin by default.",
)
parser.add_argument("--dry-run", action="store_true", help="Preview seed-catalog changes inside a rolled-back transaction.")
args = parser.parse_args()
if not args.config.exists():
    raise RuntimeError(
        "Run start.cmd, or copy deploy/v1.example.json to a private config and pass --config."
    )
config = json.loads(args.config.read_text(encoding="utf-8"))
DB_PORT = int(config.get("mysqlPort", DB_PORT))
API_PORT = int(config.get("apiPort", API_PORT))
WEB_PORT = int(config.get("previewPort", 15185))
if len({DB_PORT, API_PORT, WEB_PORT}) != 3 or any(
    port < 1024 or port > 65535 for port in (DB_PORT, API_PORT, WEB_PORT)
):
    raise RuntimeError("Use three distinct unprivileged local ports.")
RUNTIME = Path(config["runtimeDir"]).resolve()
if os.name != "nt" or RUNTIME in (
    PROJECT.resolve(),
    PROJECT.parent.resolve(),
    Path(config["mysqlDir"]).resolve(),
):
    raise RuntimeError("Use a dedicated Windows V1 runtime directory.")
for reference in ("t07", "t09"):
    previous = PROJECT / f"runtime/{reference}/config.json"
    if (
        previous.exists()
        and RUNTIME
        == Path(
            json.loads(previous.read_text(encoding="utf-8"))["runtimeDir"]
        ).resolve()
    ):
        raise RuntimeError("V1 cannot reuse a reference runtime directory.")
if RUNTIME.is_relative_to(PROJECT.resolve()) or len(RUNTIME.parts) < 4:
    raise RuntimeError(
        "Runtime data must be in a dedicated directory outside the repository."
    )
RUNTIME.mkdir(parents=True, exist_ok=True)
LOGS = RUNTIME / "logs"
LOGS.mkdir(exist_ok=True)
STATE = RUNTIME / "processes.json"
DATA = RUNTIME / "mysql-data"


def state():
    result = (
        json.loads(STATE.read_text())
        if STATE.exists()
        else {"project": str(ROOT), "services": {}}
    )
    if result["project"] != str(ROOT):
        raise RuntimeError("Runtime owner mismatch.")
    return result


def birth(pid):
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel.GetProcessTimes.argtypes = [ctypes.c_void_p] + [ctypes.c_void_p] * 4
    handle = kernel.OpenProcess(0x1000, False, int(pid))
    if not handle:
        return None
    times = [ctypes.c_ulonglong() for _ in range(4)]
    try:
        if not kernel.GetProcessTimes(handle, *[ctypes.byref(item) for item in times]):
            return None
        return times[0].value
    finally:
        kernel.CloseHandle(handle)


def listening(port):
    with socket.socket() as client:
        client.settimeout(0.3)
        return client.connect_ex(("127.0.0.1", port)) == 0


def environment():
    env = os.environ.copy()
    env["SCENT_BIND"] = "127.0.0.1"
    env["SCENT_PORT"] = str(API_PORT)
    env["SCENT_CORS_ORIGINS"] = config.get(
        "corsOrigins",
        f"http://127.0.0.1:{WEB_PORT},http://127.0.0.1:15183,http://127.0.0.1:15184",
    )
    env["JAVA_HOME"] = config["javaHome"]
    env["PATH"] = (
        str(Path(config["javaHome"]) / "bin") + os.pathsep + env.get("PATH", "")
    )
    env["MAVEN_USER_HOME"] = config.get("mavenHome", str(RUNTIME / "maven-home"))
    env["SCENT_DB_URL"] = (
        f"jdbc:mysql://127.0.0.1:{DB_PORT}/{DATABASE}?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true"
    )
    env["SCENT_DB_USER"] = DATABASE
    env["SCENT_DB_PASSWORD"] = config.get("dbPassword", "")
    env["SCENT_ADMIN_LOGIN"] = config.get("adminLogin", "")
    env["SCENT_ADMIN_PASSWORD"] = config.get("adminPassword", "")
    for key, variable in {
        "alipayAppId": "SCENT_ALIPAY_APP_ID",
        "alipaySellerId": "SCENT_ALIPAY_SELLER_ID",
        "alipayPrivateKeyFile": "SCENT_ALIPAY_PRIVATE_KEY_FILE",
        "alipayPublicKeyFile": "SCENT_ALIPAY_PUBLIC_KEY_FILE",
        "notifyOrigin": "SCENT_NOTIFY_ORIGIN",
        "bailianBaseUrl": "SCENT_BAILIAN_BASE_URL",
        "bailianAgentId": "SCENT_BAILIAN_AGENT_ID",
        "bailianEnvironmentId": "SCENT_BAILIAN_ENVIRONMENT_ID",
        "bailianAgentVersion": "SCENT_BAILIAN_AGENT_VERSION",
        "bailianMcpServerId": "SCENT_BAILIAN_MCP_SERVER_ID",
        "bailianKeyFile": "SCENT_BAILIAN_KEY_FILE",
        "mcpTokenFile": "SCENT_MCP_TOKEN_FILE",
    }.items():
        env[variable] = str(config.get(key, ""))
    env["SCENT_PUBLIC_ORIGIN"] = config.get(
        "publicOrigin",
        f"http://127.0.0.1:{WEB_PORT}" if args.production else "http://127.0.0.1:15183",
    )
    env["SCENT_MEDIA_DIRECTORY"] = str(RUNTIME / "uploads")
    env["SCENT_DOCS"] = "false" if args.production else "true"
    env["SPRING_PROFILES_ACTIVE"] = "prod" if args.production else "dev"
    return env


def logged(name, command, env=None, cwd=ROOT):
    started = time.monotonic()
    with (LOGS / (name + ".log")).open("w", encoding="utf-8") as output:
        result = subprocess.Popen(
            command,
            cwd=cwd,
            env=env or environment(),
            stdout=output,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        try:
            while True:
                try:
                    result.wait(timeout=30)
                    break
                except subprocess.TimeoutExpired:
                    elapsed = int(time.monotonic() - started)
                    print(f"{name}: still running ({elapsed}s); log: {LOGS / (name + '.log')}", flush=True)
                    if elapsed >= 1800:
                        raise RuntimeError(f"{name} exceeded 30 minutes; inspect its log before retrying.")
        except BaseException:
            if result.poll() is None:
                subprocess.run(
                    ["taskkill", "/PID", str(result.pid), "/T", "/F"],
                    capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW,
                )
            raise
    if result.returncode:
        raise RuntimeError(
            f'{name} failed; inspect the private log: {LOGS / (name + ".log")}'
        )
    return round(time.monotonic() - started, 2)


def launch(name, command, port, cwd=ROOT, env=None):
    owned = state()
    previous = owned["services"].get(name)
    if listening(port):
        if (
            previous
            and previous["birth"] is not None
            and birth(previous["pid"]) == previous["birth"]
        ):
            return
        raise RuntimeError(
            f"Port {port} occupied; do not attach or stop unrelated services."
        )
    with (LOGS / (name + ".log")).open("a", encoding="utf-8") as output:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env or environment(),
            stdout=output,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    created = birth(process.pid)
    if created is None:
        process.terminate()
        raise RuntimeError("Cannot verify the new process creation time.")
    owned["services"][name] = {"pid": process.pid, "birth": created, "port": port}
    STATE.write_text(json.dumps(owned, indent=2), encoding="utf-8")
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError(f"{name} exited; inspect its private log.")
        if listening(port):
            return
        time.sleep(0.5)
    raise RuntimeError(f"{name} did not listen in 60 seconds.")


def mysql(sql, initial=False, raw=False):
    command = [
        str(Path(config["mysqlDir"]) / "bin/mysql.exe"),
        "--no-defaults",
        "--protocol=TCP",
        "--host=127.0.0.1",
        f"--port={DB_PORT}",
        "--user=root",
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
    ]
    env = environment()
    if raw:
        command.append("--raw")
    if not initial:
        env["MYSQL_PWD"] = config["rootPassword"]
    else:
        env.pop("MYSQL_PWD", None)
    result = subprocess.run(
        command,
        input=sql,
        text=True,
        encoding="utf-8",
        capture_output=True,
        env=env,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if result.returncode:
        (LOGS / "mysql-client-error.log").write_text(result.stderr, encoding="utf-8")
        raise RuntimeError("MySQL command failed; inspect the private client log.")
    return result.stdout.strip()


def start_database():
    marker = RUNTIME / "database-owner.json"
    if not marker.exists() or json.loads(marker.read_text()) != {
        "project": str(ROOT),
        "datadir": str(DATA),
    }:
        raise RuntimeError("Database not initialized for this project.")
    launch(
        "db",
        [
            str(Path(config["mysqlDir"]) / "bin/mysqld.exe"),
            "--no-defaults",
            "--basedir=" + config["mysqlDir"],
            "--datadir=" + str(DATA),
            f"--port={DB_PORT}",
            "--bind-address=127.0.0.1",
            "--mysqlx=OFF",
            "--skip-log-bin",
            "--innodb-buffer-pool-size=128M",
            "--innodb-redo-log-capacity=64M",
            "--max-connections=24",
            "--log-error=" + str(LOGS / "mysql-error.log"),
        ],
        DB_PORT,
    )
    actual = Path(mysql("select @@datadir;")).resolve()
    if actual != DATA.resolve():
        raise RuntimeError("Connected database datadir mismatch.")


def initialize():
    if DATA.exists() or listening(DB_PORT):
        raise RuntimeError(
            "Initialization requires an unused port and a fresh datadir; nothing was deleted."
        )
    version = subprocess.check_output(
        [str(Path(config["mysqlDir"]) / "bin/mysqld.exe"), "--version"],
        text=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if "8.4." not in version:
        raise RuntimeError("V1 local validation requires MySQL 8.4 LTS.")
    config["rootPassword"], config["dbPassword"] = secrets.token_hex(
        24
    ), secrets.token_hex(24)
    args.config.write_text(json.dumps(config, indent=2), encoding="utf-8")
    logged(
        "mysql-initialize",
        [
            str(Path(config["mysqlDir"]) / "bin/mysqld.exe"),
            "--no-defaults",
            "--initialize-insecure",
            "--basedir=" + config["mysqlDir"],
            "--datadir=" + str(DATA),
            "--console",
        ],
    )
    (RUNTIME / "database-owner.json").write_text(
        json.dumps({"project": str(ROOT), "datadir": str(DATA)})
    )
    launch(
        "db",
        [
            str(Path(config["mysqlDir"]) / "bin/mysqld.exe"),
            "--no-defaults",
            "--basedir=" + config["mysqlDir"],
            "--datadir=" + str(DATA),
            f"--port={DB_PORT}",
            "--bind-address=127.0.0.1",
            "--mysqlx=OFF",
            "--skip-log-bin",
            "--innodb-buffer-pool-size=128M",
            "--innodb-redo-log-capacity=64M",
            "--max-connections=24",
        ],
        DB_PORT,
    )
    mysql(
        "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + config["rootPassword"] + "';",
        initial=True,
    )
    mysql(
        "CREATE DATABASE scent_v1 CHARACTER SET utf8mb4; CREATE DATABASE scent_v1_it CHARACTER SET utf8mb4;"
    )
    mysql(
        "CREATE USER 'scent_v1'@'127.0.0.1' IDENTIFIED BY '"
        + config["dbPassword"]
        + "';"
        "GRANT ALL PRIVILEGES ON scent_v1.* TO 'scent_v1'@'127.0.0.1';"
        "GRANT ALL PRIVILEGES ON scent_v1_it.* TO 'scent_v1'@'127.0.0.1';"
    )
    print(
        f"Initialized empty dedicated scent_v1 schema on localhost:{DB_PORT}; Flyway creates tables at startup."
    )


def seed_catalog():
    """Explicit local demo import; preserve existing prices, stock, edits and non-demo rows."""
    start_database()
    columns = (
        "id,category_id,family_id,name,subtitle,spec,unit,price_cents,stock,active,"
        "image_path,description,usage_notes,material,power_info,contents"
    )
    snapshot = (
        "USE scent_v1; SELECT JSON_ARRAYAGG(JSON_OBJECT("
        + ",".join(f"'{name}',{name}" for name in columns.split(","))
        + ")) FROM product;"
    )
    before_text = mysql(snapshot, raw=True)
    before = json.loads(before_text) or []
    if not any(row["id"] == 1001 and row["name"] == "雾松" for row in before):
        raise RuntimeError("This explicit import only extends this project's existing fictional SCENT catalog.")
    backups = RUNTIME / "backups"
    backups.mkdir(exist_ok=True)
    backup = backups / ("catalog-before-" + time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3) + ".json")
    backup.write_text(before_text, encoding="utf-8")
    seed = (ROOT / "src/main/resources/db/demo/products.sql").read_text(encoding="utf-8")
    if seed.count("INSERT INTO product(") != 1 or seed.count("INSERT INTO product_compatibility(") != 1:
        raise RuntimeError("Unexpected demo source format; inspect instead of importing blindly.")
    stage = seed.replace("INSERT INTO product(", "INSERT INTO scent_seed_product(").replace(
        "INSERT INTO product_compatibility(", "INSERT INTO scent_seed_compatibility("
    )
    result = mysql(
        "USE scent_v1; CREATE TEMPORARY TABLE scent_seed_product LIKE product;"
        "CREATE TEMPORARY TABLE scent_seed_compatibility LIKE product_compatibility;"
        + stage
        + "CREATE TEMPORARY TABLE scent_seed_peer LIKE product; INSERT INTO scent_seed_peer SELECT * FROM scent_seed_product;"
        + "START TRANSACTION; UPDATE product p JOIN scent_seed_product d ON p.id=d.id "
        "AND p.name=d.name AND p.category_id=d.category_id AND p.family_id<=>d.family_id "
        "AND p.subtitle=d.subtitle AND p.spec=d.spec AND p.unit=d.unit "
        "SET p.description=d.description, p.usage_notes=CASE WHEN p.usage_notes IN "
        "('请按包装用途使用；香品远离火源、儿童及宠物，不作疗效承诺。',"
        "'使用前阅读说明并核对尺寸/供电与适配要求。') THEN d.usage_notes ELSE p.usage_notes END,"
        "p.version=p.version+1 WHERE p.description=CONCAT('SCENT虚构教学商品。',p.subtitle);"
        "SELECT ROW_COUNT(); INSERT INTO product(" + columns + ") SELECT "
        + ",".join("d." + name for name in columns.split(","))
        + " FROM scent_seed_product d WHERE NOT EXISTS(SELECT 1 FROM product p WHERE p.id=d.id OR p.name=d.name);"
        "SELECT ROW_COUNT(); INSERT INTO product_compatibility(product_id,related_product_id) "
        "SELECT r.product_id,r.related_product_id FROM scent_seed_compatibility r "
        "JOIN product a ON a.id=r.product_id JOIN scent_seed_product da ON da.id=a.id AND da.name=a.name "
        "JOIN product b ON b.id=r.related_product_id JOIN scent_seed_peer db ON db.id=b.id AND db.name=b.name "
        "WHERE NOT EXISTS(SELECT 1 FROM product_compatibility c WHERE c.product_id=r.product_id AND c.related_product_id=r.related_product_id);"
        "SELECT ROW_COUNT(); " + ("ROLLBACK;" if args.dry_run else "COMMIT;")
    )
    after = {row["id"]: row for row in json.loads(mysql(snapshot, raw=True))}
    if args.dry_run and after != {row["id"]: row for row in before}:
        raise RuntimeError("Catalog preview did not fully roll back; inspect the private snapshot.")
    for previous in before:
        current = after[previous["id"]]
        for field in columns.split(","):
            if field not in ("description", "usage_notes") and previous[field] != current[field]:
                raise RuntimeError("Existing catalog fact changed unexpectedly; inspect the private snapshot.")
    counts = result.splitlines()
    print(json.dumps({"descriptions_enriched": int(counts[0]), "products_added": int(counts[1]),
        "compatibility_added": int(counts[2]), "dry_run": args.dry_run,
        "total_rows": len(after), "backup": str(backup)}, ensure_ascii=False))


def initialize_mcp():
    """Create one local machine credential; never print the credential itself."""
    if config.get("mcpTokenFile"):
        print("MCP token file already configured; no credential was changed.")
        return
    private = RUNTIME / "keys"
    private.mkdir(exist_ok=True)
    token_file = private / "product-mcp.token"
    with token_file.open("x", encoding="utf-8") as output:
        output.write(secrets.token_urlsafe(32))
    config["mcpTokenFile"] = str(token_file)
    args.config.write_text(json.dumps(config, indent=2), encoding="utf-8")
    print("Configured private MCP token file: " + str(token_file))


def verify(test=True):
    if test:
        start_database()
    settings = RUNTIME / "maven-settings.xml"
    settings.write_text(
        "<settings><mirrors><mirror><id>t09-central</id>"
        "<url>https://repo.maven.apache.org/maven2</url><mirrorOf>*</mirrorOf>"
        "</mirror></mirrors></settings>",
        encoding="utf-8",
    )
    command = [
        str(config.get("mavenCommand", ROOT / "mvnw.cmd")),
        "-B",
        "--no-transfer-progress",
        "-s",
        str(settings),
        "-Dmaven.repo.local="
        + config.get("mavenRepository", str(RUNTIME / "maven-repository")),
        "verify" if test else "package",
    ]
    if not test:
        command.append("-DskipTests")
    else:
        coverage = RUNTIME / "coverage.exec"
        if not str(coverage).isascii():
            raise RuntimeError(
                "Use an ASCII private runtime path for Windows forked-JVM coverage output."
            )
        if coverage.exists():
            if not coverage.is_file() or coverage.is_symlink():
                raise RuntimeError("Unexpected coverage output target.")
            coverage.unlink()
        command.append("-Dscent.coverage.file=" + coverage.as_posix())
    env = environment()
    if test:
        env["SCENT_DB_URL"] = env["SCENT_DB_URL"].replace("/scent_v1?", "/scent_v1_it?")
        env["SPRING_PROFILES_ACTIVE"] = "test"
        for variable in (
            "SCENT_ALIPAY_APP_ID",
            "SCENT_ALIPAY_SELLER_ID",
            "SCENT_ALIPAY_PRIVATE_KEY_FILE",
            "SCENT_ALIPAY_PUBLIC_KEY_FILE",
            "SCENT_NOTIFY_ORIGIN",
            "SCENT_BAILIAN_BASE_URL",
            "SCENT_BAILIAN_AGENT_ID",
            "SCENT_BAILIAN_ENVIRONMENT_ID",
            "SCENT_BAILIAN_AGENT_VERSION",
            "SCENT_BAILIAN_MCP_SERVER_ID",
            "SCENT_BAILIAN_KEY_FILE",
            "SCENT_MCP_TOKEN_FILE",
        ):
            env[variable] = (
                ""  # Tests must never inherit a user's real external-provider credentials.
            )
    elapsed = logged("maven-verify" if test else "maven-build", command, env)
    (RUNTIME / "build-result.json").write_text(
        json.dumps({"seconds": elapsed, "exit_code": 0, "tests": test}),
        encoding="utf-8",
    )
    print(
        f'Maven {"verify" if test else "package"} passed in {elapsed}s; reports are in apps/api/target/.'
    )


def stop(names=("preview", "storefront", "admin", "api", "db")):
    owned = state()
    for name in names:
        item = owned["services"].get(name)
        if not item or item["birth"] is None or birth(item["pid"]) != item["birth"]:
            continue
        if name == "preview":
            prefix = RUNTIME / "preview"
            if int((prefix / "logs/nginx.pid").read_text().strip()) != item["pid"]:
                raise RuntimeError(
                    "Preview master PID does not match the recorded process."
                )
            subprocess.run(
                [
                    str(Path(config["nginxHome"]) / "nginx.exe"),
                    "-p",
                    prefix.as_posix() + "/",
                    "-c",
                    "nginx.conf",
                    "-s",
                    "quit",
                ],
                check=True,
                capture_output=True,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        elif name == "db":
            if Path(mysql("select @@datadir;")).resolve() != DATA.resolve():
                raise RuntimeError("Refusing to stop an unexpected database.")
            command = [
                str(Path(config["mysqlDir"]) / "bin/mysqladmin.exe"),
                "--no-defaults",
                "--protocol=TCP",
                "--host=127.0.0.1",
                f"--port={DB_PORT}",
                "--user=root",
                "shutdown",
            ]
            env = environment()
            env["MYSQL_PWD"] = config["rootPassword"]
            subprocess.run(
                command,
                check=True,
                env=env,
                capture_output=True,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        else:
            os.kill(item["pid"], signal.SIGTERM)
        for _ in range(60):
            if birth(item["pid"]) is None:
                break
            time.sleep(0.2)
        if listening(item["port"]):
            raise RuntimeError(f"{name} did not stop.")
        owned["services"].pop(name)
        STATE.write_text(json.dumps(owned, indent=2), encoding="utf-8")
    print("Stopped only the recorded V1 processes; database files retained.")


if args.action == "init-db":
    initialize()
elif args.action == "seed-catalog":
    seed_catalog()
elif args.action == "mcp-init":
    initialize_mcp()
elif args.action in ("verify", "build"):
    verify(args.action == "verify")
elif args.action in ("start", "restart-api", "rollback-api"):
    start_database()
    artifact = RUNTIME / "scent-api.jar"
    previous = RUNTIME / "scent-api.previous.jar"
    if not listening(API_PORT) or args.action in ("restart-api", "rollback-api"):
        source = (
            previous
            if args.action == "rollback-api"
            else ROOT / "target/scent-api-0.1.0-SNAPSHOT.jar"
        )
        with zipfile.ZipFile(source) as archive:
            if (
                "BOOT-INF/classes/io/scent/ScentApplication.class"
                not in archive.namelist()
            ):
                raise RuntimeError(
                    "Build artifact is not a complete executable Boot jar; run verify first."
                )
            if args.action == "rollback-api":
                prefix = "BOOT-INF/classes/db/migration/"
                before = {
                    name: archive.read(name)
                    for name in archive.namelist()
                    if name.startswith(prefix) and name.endswith(".sql")
                }
                with zipfile.ZipFile(artifact) as current:
                    after = {
                        name: current.read(name)
                        for name in current.namelist()
                        if name.startswith(prefix) and name.endswith(".sql")
                    }
                if before != after:
                    raise RuntimeError(
                        "Refusing binary rollback across database migration changes; use a reviewed forward repair."
                    )
        if args.action in ("restart-api", "rollback-api"):
            stop(("api",))
        if args.action == "restart-api" and artifact.is_file():
            shutil.copyfile(artifact, previous)
        shutil.copyfile(source, artifact)
    launch(
        "api",
        [str(Path(config["javaHome"]) / "bin/java.exe"), "-jar", str(artifact)],
        API_PORT,
    )
    print(
        f"SCENT API listening at http://127.0.0.1:{API_PORT}; validate health and API responses before claiming ready."
    )
elif args.action == "stop":
    stop()
elif args.action == "web-stop":
    stop(("storefront", "admin"))
elif args.action == "preview-stop":
    stop(("preview",))
elif args.action == "preview-start":
    prefix = RUNTIME / "preview"
    if listening(WEB_PORT):
        raise RuntimeError(
            "Preview port already occupied; stop only its recorded service before republishing."
        )
    binary = Path(config["nginxHome"])
    if not (binary / "nginx.exe").is_file():
        raise RuntimeError("Configure the verified isolated nginxHome first.")
    for app in ("storefront", "admin"):
        source = PROJECT / "apps" / app / "dist"
        if not (source / "index.html").is_file():
            raise RuntimeError(
                "Build both frontends before publishing the local preview."
            )
    (prefix / "logs").mkdir(parents=True, exist_ok=True)
    (prefix / "temp").mkdir(exist_ok=True)
    root = prefix / "www"
    shutil.copytree(PROJECT / "apps/storefront/dist", root, dirs_exist_ok=True)
    shutil.copytree(PROJECT / "apps/admin/dist", root / "admin", dirs_exist_ok=True)
    template = (PROJECT / "deploy/nginx.conf.template").read_text(encoding="utf-8")
    (prefix / "nginx.conf").write_text(
        template.replace("@@ROOT@@", root.as_posix()).replace(
            "@@MIME@@", (binary / "conf/mime.types").as_posix()
        ).replace("@@WEB_PORT@@", str(WEB_PORT)).replace("@@API_PORT@@", str(API_PORT)),
        encoding="utf-8",
    )
    command = [
        str(binary / "nginx.exe"),
        "-p",
        prefix.as_posix() + "/",
        "-c",
        "nginx.conf",
    ]
    subprocess.run(
        command + ["-t"],
        check=True,
        capture_output=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    launch("preview", command, WEB_PORT, cwd=prefix)
    print(
        f"Built static buyer/admin + same-origin API: http://127.0.0.1:{WEB_PORT} /admin/. Local preview, no public TLS deployment."
    )
elif args.action.startswith("web-") or args.action == "browser-check":
    node = Path(config["nodeHome"])
    env = environment()
    env["PATH"] = str(node) + os.pathsep + env["PATH"]
    version = subprocess.check_output(
        [str(node / "node.exe"), "--version"], text=True
    ).strip()
    if version != "v24.22.0":
        raise RuntimeError("Use the verified isolated Node v24.22.0 toolchain.")
    if args.action == "browser-check":
        env["SCENT_BROWSER_CHANNEL"] = config.get("browserChannel", "msedge")
        elapsed = logged(
            "browser-check",
            [str(node / "node.exe"), str(PROJECT / "tests/v1-browser.cjs")],
            env,
            PROJECT,
        )
        print(
            f"Real local buyer/admin browser checks passed in {elapsed}s; private evidence retained."
        )
    elif args.action == "web-start":
        for app, port in [("storefront", 15183), ("admin", 15184)]:
            launch(
                app,
                [
                    str(node / "node.exe"),
                    str(PROJECT / "node_modules/vite/bin/vite.js"),
                ],
                port,
                cwd=PROJECT / "apps" / app,
                env=env,
            )
        print(
            "Buyer http://127.0.0.1:15183 / Admin http://127.0.0.1:15184; API must be running separately."
        )
    else:
        if args.action == "web-install" and any(
            listening(port) for port in (15183, 15184)
        ):
            raise RuntimeError(
                "Run web-stop before npm ci: Windows locks the running Vite native module."
            )
        command = [
            str(node / "node.exe"),
            str(node / "node_modules/npm/bin/npm-cli.js"),
        ]
        command += (
            ["ci", "--no-fund"] if args.action == "web-install" else ["run", "build"]
        )
        elapsed = logged(args.action, command, env, PROJECT)
        print(f"{args.action} passed in {elapsed}s; private log under {LOGS}.")
else:
    print(
        json.dumps(
            {
                "services": state()["services"],
                "listeners": {
                    str(port): listening(port)
                    for port in (DB_PORT, API_PORT, 15183, 15184, WEB_PORT)
                },
            }
        )
    )
