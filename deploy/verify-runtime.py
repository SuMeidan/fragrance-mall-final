"""Bounded local backup/restore and read benchmark. Never restores over an existing database."""

import argparse
import concurrent.futures
import hashlib
import http.client
import json
import math
import os
import re
import shutil
import socket
import subprocess
import time
from threading import Event
import uuid
from datetime import datetime, timezone
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--config", type=Path, default=PROJECT / "runtime/v1/config.json")
parser.add_argument("--seconds", type=int, default=60)
parser.add_argument("--concurrency", type=int, default=10)
parser.add_argument("--mysql-port", type=int, default=13310)
parser.add_argument("--api-port", type=int, default=28059)
parser.add_argument("--source-db", default="scent_v1")
args = parser.parse_args()
if not 1 <= args.seconds <= 60 or not 1 <= args.concurrency <= 10:
    raise RuntimeError("Bounded at 10 concurrent clients / 60 seconds.")
if (not 1 <= args.mysql_port <= 65535 or not 1 <= args.api_port <= 65535
        or args.mysql_port == args.api_port
        or not re.fullmatch(r"scent_[a-z0-9_]{1,48}", args.source_db)):
    raise RuntimeError("Invalid isolated port or SCENT source database name.")
config = json.loads(args.config.read_text(encoding="utf-8"))
runtime = Path(config["runtimeDir"]).resolve()
if (
    os.name != "nt"
    or runtime.is_relative_to(PROJECT.resolve())
    or len(runtime.parts) < 4
):
    raise RuntimeError("Use the existing private Windows V1 runtime.")
owner = json.loads((runtime / "database-owner.json").read_text())
datadir = Path(config.get("mysqlDataDir", str(runtime / "mysql-data"))).resolve()
if (
    Path(owner.get("project", "")).resolve() != (PROJECT / "apps/api").resolve()
    or Path(owner.get("datadir", "")).resolve() != datadir
):
    raise RuntimeError("Database runtime owner mismatch.")
PORT = args.api_port
with socket.socket() as probe:
    if probe.connect_ex(("127.0.0.1", PORT)) == 0:
        raise RuntimeError(
            "Verification API port occupied; do not adopt unknown services."
        )
stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + uuid.uuid4().hex[:6]
database = "scent_verify_" + stamp + "_restore"
if not re.fullmatch(r"scent_verify_[0-9]{14}_[a-f0-9]{6}_restore", database):
    raise RuntimeError("Unsafe target database name.")
folder = runtime / "backups" / stamp
folder.mkdir(parents=True, exist_ok=False)
binpath = Path(config["mysqlDir"]) / "bin"
env = os.environ.copy()
env["MYSQL_PWD"] = config["rootPassword"]
flags = [
    "--no-defaults",
    "--protocol=TCP",
    "--host=127.0.0.1",
    "--port=" + str(args.mysql_port),
    "--user=root",
    "--default-character-set=utf8mb4",
]


def sql(statement, db=None):
    command = [str(binpath / "mysql.exe"), *flags, "--batch", "--skip-column-names"] + (
        [db] if db else []
    )
    result = subprocess.run(
        command,
        input=statement.encode(),
        env=env,
        capture_output=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if result.returncode:
        (folder / "mysql-error.log").write_bytes(result.stderr)
        raise RuntimeError(
            "Database verification command failed; private log retained."
        )
    return result.stdout.decode("utf-8").strip()


def dump(db, target, data_only=False):
    options = [
        "--single-transaction",
        "--set-gtid-purged=OFF",
        "--no-tablespaces",
        "--skip-comments",
        "--skip-dump-date",
        "--skip-extended-insert",
        "--order-by-primary",
        "--hex-blob",
        "--skip-add-drop-table",
        "--skip-add-locks",
        "--skip-lock-tables",
    ]
    if data_only:
        options += ["--no-create-info"]
    result = subprocess.run(
        [
            str(binpath / "mysqldump.exe"),
            *flags,
            *options,
            "--result-file=" + str(target),
            db,
        ],
        env=env,
        capture_output=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
    if result.returncode:
        (folder / "dump-error.log").write_bytes(result.stderr)
        raise RuntimeError("Backup failed; source database unchanged.")


def fingerprints(path):
    tables = {}
    for line in path.read_bytes().splitlines(keepends=True):
        match = re.match(rb"INSERT INTO `([a-z_]+)` VALUES ", line)
        if match:
            name = match[1].decode()
            entry = tables.setdefault(name, {"rows": 0, "hash": hashlib.sha256()})
            entry["rows"] += 1
            entry["hash"].update(line)
    return {
        name: {"rows": v["rows"], "sha256": v["hash"].hexdigest()}
        for name, v in tables.items()
    }


def media_manifest(path):
    return (
        {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in path.glob("*")
            if p.is_file()
        }
        if path.exists()
        else {}
    )


print("Step 1/5: consistent read-only backup of " + args.source_db + "; no overwrite.", flush=True)
if Path(sql("SELECT @@datadir;")).resolve() != datadir:
    raise RuntimeError(
        "Connected database directory differs from the recorded runtime."
    )
backup = folder / "database.sql"
dump(args.source_db, backup)
original = fingerprints(backup)
if (
    sql(
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"
        + database
        + "'"
    )
    != "0"
):
    raise RuntimeError("Restore database already exists.")
sql("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4;")
with backup.open("rb") as source:
    result = subprocess.run(
        [str(binpath / "mysql.exe"), *flags, database],
        stdin=source,
        env=env,
        capture_output=True,
        creationflags=subprocess.CREATE_NO_WINDOW,
    )
if result.returncode:
    (folder / "restore-error.log").write_bytes(result.stderr)
    raise RuntimeError("Restore failed; source unchanged and new target retained.")
restored = folder / "restored-data.sql"
dump(database, restored, True)
if original != fingerprints(restored):
    raise RuntimeError("Per-table backup/restore row fingerprints differ.")
tables = sql("SHOW TABLES;", database).splitlines()
if len(tables) != 16:
    raise RuntimeError("Expected 15 business tables plus Flyway history.")
counts = {
    name: int(sql("SELECT COUNT(*) FROM `" + name + "`;", database)) for name in tables
}
if any(counts[name] != original.get(name, {"rows": 0})["rows"] for name in tables):
    raise RuntimeError("Restored counts differ.")
media = folder / "media"
media.mkdir()
if (runtime / "uploads").exists():
    shutil.copytree(runtime / "uploads", media, dirs_exist_ok=True)
if media_manifest(runtime / "uploads") != media_manifest(media):
    raise RuntimeError("Uploaded image copy differs.")
for reference in sql(
    "SELECT image_path FROM product WHERE image_path LIKE '/media/%' UNION SELECT image_path FROM order_item WHERE image_path LIKE '/media/%';",
    database,
).splitlines():
    name = reference.removeprefix("/media/")
    if (
        not re.fullmatch(r"[a-f0-9]{32}\.(png|jpg)", name)
        or not (media / name).is_file()
    ):
        raise RuntimeError("Snapshot image missing from backup.")
print(
    "Step 2/5: restored 16 tables into a new database; per-table contents and media hashes match.",
    flush=True,
)

username = "sv_" + uuid.uuid4().hex[:16]
password = uuid.uuid4().hex
sql(
    "CREATE USER '"
    + username
    + "'@'127.0.0.1' IDENTIFIED BY '"
    + password
    + "'; GRANT SELECT,INSERT,UPDATE,DELETE ON `"
    + database
    + "`.* TO '"
    + username
    + "'@'127.0.0.1';"
)
app_env = {k: v for k, v in os.environ.items()
           if not k.startswith(("SCENT_ALIPAY_", "SCENT_ADVISOR_", "SCENT_MCP_", "SCENT_BAILIAN_"))}
app_env.update(
    {
        "SCENT_DB_URL": "jdbc:mysql://127.0.0.1:" + str(args.mysql_port) + "/"
        + database
        + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true",
        "SCENT_DB_USER": username,
        "SCENT_DB_PASSWORD": password,
        "SCENT_PORT": str(PORT),
        "SCENT_BIND": "127.0.0.1",
        "SCENT_DOCS": "false",
        "SPRING_PROFILES_ACTIVE": "prod",
        "SCENT_ADMIN_LOGIN": "",
        "SCENT_ADMIN_PASSWORD": "",
        "SCENT_MEDIA_DIRECTORY": str(media),
    }
)
for name in (
    "SCENT_ALIPAY_APP_ID",
    "SCENT_ALIPAY_SELLER_ID",
    "SCENT_ALIPAY_PRIVATE_KEY_FILE",
    "SCENT_ALIPAY_PUBLIC_KEY_FILE",
    "SCENT_NOTIFY_ORIGIN",
    "SCENT_LLM_ENDPOINT",
    "SCENT_LLM_MODEL",
    "SCENT_LLM_KEY_FILE",
):
    app_env[name] = ""


def request(path, token=None, body=None):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=5)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        conn.request(
            "POST" if body is not None else "GET",
            path,
            json.dumps(body) if body is not None else None,
            headers,
        )
        response = conn.getresponse()
        data = json.loads(response.read())
        return response.status, data
    finally:
        conn.close()


def benchmark(clients, seconds, limit, token):
    started = time.perf_counter()
    end = started + seconds
    aborted = Event()
    paths = [
        "/api/v1/catalog/products?page=1&pageSize=20",
        "/api/v1/catalog/products?page=25&pageSize=20",
        "/api/v1/catalog/products?keyword=perf-fixture&pageSize=20",
        "/api/v1/admin/orders?page=1&pageSize=20",
    ]

    def worker(index):
        values = []
        errors = 0
        consecutive = 0
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=5)
        try:
            for number in range(
                limit // clients + (1 if index < limit % clients else 0)
            ):
                tick = time.perf_counter()
                if tick >= end or aborted.is_set():
                    break
                path = paths[(number + index) % len(paths)]
                headers = (
                    {"Authorization": "Bearer " + token} if "/admin/" in path else {}
                )
                try:
                    conn.request("GET", path, headers=headers)
                    response = conn.getresponse()
                    payload = json.loads(response.read())
                    okay = response.status == 200 and payload.get("resultCode") == 200
                except (OSError, ValueError, http.client.HTTPException):
                    okay = False
                    conn.close()
                    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=5)
                values.append((time.perf_counter() - tick) * 1000)
                if not okay:
                    errors += 1
                    consecutive += 1
                else:
                    consecutive = 0
                if consecutive >= 3:
                    aborted.set()
                    raise RuntimeError(
                        "Stopped benchmark after three consecutive unexpected responses."
                    )
                time.sleep(max(0, 0.1 - (time.perf_counter() - tick)))
        finally:
            conn.close()
        return values, errors

    with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as pool:
        results = list(pool.map(worker, range(clients)))
    times = sorted(t for values, _ in results for t in values)
    elapsed = time.perf_counter() - started
    failures = sum(v for _, v in results)
    if not times:
        raise RuntimeError("No benchmark requests completed.")

    def percentile(p):
        return round(times[max(0, math.ceil(len(times) * p) - 1)], 2)

    return {
        "concurrency": clients,
        "durationSeconds": round(elapsed, 2),
        "requests": len(times),
        "qps": round(len(times) / elapsed, 2),
        "p50Ms": percentile(0.50),
        "p95Ms": percentile(0.95),
        "p99Ms": percentile(0.99),
        "errors": failures,
        "errorRate": failures / len(times),
        "rateLimitPerClientPerSecond": 10,
        "scope": "bounded read test, not saturation capacity",
    }


log = (folder / "api.log").open("w", encoding="utf-8")
process = subprocess.Popen(
    [
        str(Path(config["javaHome"]) / "bin/java.exe"),
        "-jar",
        str(runtime / "scent-api.jar"),
        "--scent.order.auto-close=false",
        "--scent.payment.auto-reconcile=false",
    ],
    env=app_env,
    stdout=log,
    stderr=subprocess.STDOUT,
    creationflags=subprocess.CREATE_NO_WINDOW,
)
try:
    for attempt in range(100):
        if process.poll() is not None:
            raise RuntimeError(
                "Restored production-profile API exited; private log retained."
            )
        try:
            if request("/actuator/health")[0] == 200:
                break
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    else:
        raise RuntimeError("Restored API did not become healthy.")
    status, login = request(
        "/api/v1/auth/admin-login",
        body={"loginName": config["adminLogin"], "password": config["adminPassword"]},
    )
    if status != 200:
        raise RuntimeError("Restored account login failed.")
    token = login["data"]["token"]
    if request("/v3/api-docs")[0] == 200:
        raise RuntimeError("Production documentation UI unexpectedly enabled.")
    status, catalog = request("/api/v1/catalog/products")
    if status != 200:
        raise RuntimeError("Restored catalogue could not be read.")
    print(
        "Step 3/5: production-profile API boots recovered DB; account login/catalogue work, docs disabled.",
        flush=True,
    )
    products = counts["product"]
    orders = counts["mall_order"]
    if products > 1000 or orders > 500:
        raise RuntimeError("Source snapshot exceeds bounded fixture targets.")
    first = int(
        sql("SELECT id FROM product WHERE active=1 ORDER BY id LIMIT 1;", database)
    )
    buyer = sql(
        "SELECT id FROM account WHERE role='BUYER' ORDER BY id LIMIT 1;", database
    )
    if not buyer:
        raise RuntimeError(
            "Restore fixture requires one fictional buyer from the development snapshot."
        )
    sqls = ["START TRANSACTION;"]
    for i in range(1000 - products):
        sqls.append(
            "INSERT INTO product(category_id,family_id,name,subtitle,spec,unit,price_cents,stock,active,image_path,description,usage_notes,material,power_info,contents,version) SELECT category_id,family_id,'perf-fixture-"
            + str(i)
            + "',subtitle,spec,unit,price_cents,20,1,image_path,description,usage_notes,material,power_info,contents,0 FROM product WHERE id="
            + str(first)
            + ";"
        )
    for i in range(500 - orders):
        key = uuid.uuid4().hex
        sqls.append(
            "INSERT INTO mall_order(order_no,account_id,checkout_key,checkout_address_id,status,total_cents,receiver,phone,region,address_detail,expires_at,closed_at,close_reason) SELECT '"
            + key
            + "',"
            + buyer
            + ",'perf-"
            + key
            + "',1,-1,price_cents,'虚构压测','18800000001','虚构地区','隔离恢复副本，不履约',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 20 MINUTE),UTC_TIMESTAMP(6),'性能夹具' FROM product WHERE id="
            + str(first)
            + ";"
        )
        sqls.append(
            "INSERT INTO order_item(order_id,product_id,name,spec,unit,image_path,price_cents,quantity) SELECT LAST_INSERT_ID(),id,name,spec,unit,image_path,price_cents,1 FROM product WHERE id="
            + str(first)
            + ";"
        )
    sqls.append("COMMIT;")
    sql("\n".join(sqls), database)
    dataset = {
        "products": int(sql("SELECT COUNT(*) FROM product;", database)),
        "orders": int(sql("SELECT COUNT(*) FROM mall_order;", database)),
    }
    if dataset != {"products": 1000, "orders": 500}:
        raise RuntimeError("Fixture cardinalities differ.")
    print(
        "Step 4/5: isolated 1000-product / 500-order fixture; 10-request smoke and 100-request limited batch.",
        flush=True,
    )
    smoke = benchmark(1, 5, 10, token)
    limited = benchmark(5, 10, 100, token)
    if smoke["errors"] or limited["errors"]:
        raise RuntimeError("Small read checks failed; full benchmark skipped.")
    print(
        "Step 5/5: bounded read benchmark, at most 10 clients / 60 seconds / 6000 requests.",
        flush=True,
    )
    full = benchmark(args.concurrency, args.seconds, 6000, token)
    metrics = {}
    for name in (
        "jvm.memory.used",
        "process.cpu.usage",
        "hikaricp.connections.active",
        "http.server.requests",
    ):
        status, data = request("/actuator/metrics/" + name, token)
        if status != 200:
            raise RuntimeError("Administrator metric unavailable: " + name)
        metrics[name] = data.get("measurements")
    summary = {
        "source": args.source_db,
        "mysqlPort": args.mysql_port,
        "apiPort": PORT,
        "restoreDatabase": database,
        "tableCount": len(tables),
        "rowsBeforeSeed": counts,
        "perTableContentHashesMatch": True,
        "mediaHashesMatch": True,
        "migrations": counts["flyway_schema_history"],
        "dataset": dataset,
        "smoke": smoke,
        "limited": limited,
        "full": full,
        "metrics": metrics,
        "backupSha256": hashlib.sha256(backup.read_bytes()).hexdigest(),
        "externalProviders": "disabled",
        "publicDeployment": False,
        "evidenceDirectory": str(folder),
    }
    (folder / "result.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (runtime / "runtime-verification.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "restoreDatabase": database,
                "tables": len(tables),
                "contentsMatch": True,
                "dataset": dataset,
                "full": full,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    if full["errors"]:
        raise RuntimeError(
            "Unexpected full benchmark errors; inspect retained evidence."
        )
finally:
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    log.close()
    if not re.fullmatch(r"sv_[a-f0-9]{16}", username):
        raise RuntimeError("Unexpected temporary database user identity.")
    sql("DROP USER '" + username + "'@'127.0.0.1';")
