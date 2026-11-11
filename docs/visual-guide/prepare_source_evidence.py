"""把当前源码复制为独立 Git 证据快照，不修改项目工作区、暂存区或历史。"""
import shutil
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

ROOT = Path(__file__).resolve().parents[2]


def prepare():
    """Archify 按提交校验行号，因此未提交优化需在独立临时仓库中冻结。"""
    snapshot = Path(tempfile.mkdtemp(prefix="lottery-archify-source-"))
    # 复制源码、SQL 与被图解引用的说明；临时目录不加入 HTTP 预览资源。
    files = [ROOT / "pom.xml", ROOT / "docs/architecture.md"]
    files.extend(ROOT.glob("lottery-*/pom.xml"))
    files.extend(ROOT.glob("lottery-*/src/main/**/*.java"))
    files.extend(ROOT.glob("lottery-*/src/main/**/*.xml"))
    files.extend(ROOT.glob("lottery-interfaces/src/main/resources/application.yml"))
    files.extend(ROOT.glob("docs/sql/*.sql"))
    for source in files:
        target = snapshot / source.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    # 保留源仓库身份；HTTPS 地址移除可能存在的用户信息。
    origin = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=ROOT, text=True).strip()
    if origin.startswith(("http://", "https://")):
        parsed = urlsplit(origin)
        origin = urlunsplit(parsed._replace(netloc=parsed.netloc.rsplit("@", 1)[-1]))
    subprocess.run(["git", "init", "--quiet"], cwd=snapshot, check=True)
    subprocess.run(["git", "remote", "add", "origin", origin], cwd=snapshot, check=True)
    subprocess.run(["git", "add", "."], cwd=snapshot, check=True)
    # 只提交临时证据副本；禁用用户全局签名，避免触发交互式签名程序。
    subprocess.run(["git", "-c", "user.name=Archify local evidence", "-c", "user.email=archify@localhost",
                    "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                    "commit", "--quiet", "-m", "Current Lottery working tree source evidence"],
                   cwd=snapshot, check=True)
    return snapshot


if __name__ == "__main__":
    print(prepare())
