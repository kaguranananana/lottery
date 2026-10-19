"""核对本地资源、源码锚点与步骤对应关系，不访问业务系统或计算内容摘要。"""
import json
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

OUT = Path(__file__).resolve().parent
ROOT = OUT.parent.parent

class Links(HTMLParser):
    """提取页面实际使用的链接、静态资源与锚点。"""
    def __init__(self):
        super().__init__()
        self.targets, self.ids = [], set()

    def handle_starttag(self, tag, attrs):
        data = dict(attrs)
        if data.get("id"): self.ids.add(data["id"])
        for key in ["href", "src"]:
            if data.get(key): self.targets.append(data[key])

def parse(path):
    parser = Links()
    parser.feed(path.read_text())
    return parser

# 学习页面由本工具检查，独立图已经由 Archify 完整检查。
pages = [OUT / name for name in ["index.html", "reference.html", "sources.html"]]
parsed = {path: parse(path) for path in pages}
checked_links = 0
for path, parser in parsed.items():
    for target in parser.targets:
        url = urlsplit(target)
        if url.scheme or url.netloc: continue
        destination = (path.parent / unquote(url.path)).resolve() if url.path else path
        if not destination.exists(): raise ValueError(f"缺失本地资源：{path.name} → {target}")
        if url.fragment and destination in parsed:
            if unquote(url.fragment) not in parsed[destination].ids:
                raise ValueError(f"源码/页面锚点不存在：{path.name} → {target}")
        checked_links += 1

# 每步指向图中真实存在的关系，避免说明面板与图上的执行位置脱节。
raw = (OUT / "guide-data.js").read_text()
data = json.loads(raw.split("window.LOTTERY_GUIDE = ", 1)[1].rsplit(";", 1)[0])
specs = {}
for candidate in OUT.glob("diagrams/*/candidate.json"):
    spec = json.loads(candidate.read_text())
    specs[candidate.parent.name] = spec
    for collection in ["components", "participants", "nodes", "states"]:
        for item in spec.get(collection, []):
            for source in item.get("sources", []):
                lines = (ROOT / source["path"]).read_text().splitlines()
                if source.get("end_line", source["line"]) > len(lines):
                    raise ValueError(f"图内源码行号越界：{source['path']}")

checked_steps = 0
for mode in ["sync", "losing", "async"]:
    for item in data[mode]:
        spec = specs[item["diagram"]]
        relations = spec.get("messages", spec.get("edges", spec.get("connections", [])))
        if item["relation"] not in {relation.get("id") for relation in relations}:
            raise ValueError(f"步骤关系不存在：{mode} / {item['id']}")
        for source in item["sources"]:
            anchor = data["sources"][source["path"]]["id"] + "-L" + str(source["line"])
            if anchor not in parsed[OUT / "sources.html"].ids:
                raise ValueError(f"步骤源码锚点缺失：{item['id']} / {anchor}")
        checked_steps += 1

print(f"通过：{len(specs)} 张图、{checked_steps} 个路径步骤、{checked_links} 个本地链接/资源；源码与关系定位有效。")
