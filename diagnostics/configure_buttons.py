"""Map switch_1..switch_N in the order of the existing private _all targets."""

import configparser
import json
import re
from pathlib import Path
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / ".secrets" / "config.ini"
API = "https://api.smartthings.com/v1/devices"


def main():
    config = configparser.ConfigParser()
    if not config.read(CONFIG, encoding="utf-8"):
        raise SystemExit("Arquivo privado não encontrado.")
    token = config.get("smartthings", "token")
    all_targets = [item.strip().split("|")[0]
                   for item in config.get("smartthings", "targets").split(",")]
    request = Request(API, headers={"Authorization": "Bearer " + token,
                                    "Accept": "application/json"})
    with urlopen(request, timeout=15) as response:
        items = json.load(response).get("items", [])
    by_id = {item["deviceId"]: item for item in items}
    selected = []
    panel_counts = []
    for all_id in all_targets:
        panel = by_id.get(all_id)
        uid = panel.get("viper", {}).get("uniqueIdentifier", "") if panel else ""
        if not uid.endswith("_all"):
            raise SystemExit("Um dos alvos gerais não pôde ser identificado; configuração mantida.")
        prefix = uid[:-4]
        channels = []
        for item in items:
            match = re.fullmatch(re.escape(prefix) + r"_switch_([1-9][0-9]*)",
                                 item.get("viper", {}).get("uniqueIdentifier", ""))
            if match:
                channels.append((int(match.group(1)), item["deviceId"]))
        channels.sort()
        if [number for number, _ in channels] != list(range(1, len(channels) + 1)):
            raise SystemExit("Canais incompletos; configuração mantida.")
        panel_counts.append(len(channels))
        selected.extend(device_id + "|main" for _, device_id in channels)
    if len(selected) != 10 or len(set(selected)) != 10:
        raise SystemExit("Esperados 10 canais únicos; configuração mantida.")
    content = CONFIG.read_text(encoding="utf-8")
    section = re.search(r"(?ms)^\[smartthings\]\s*\n(.*?)(?=^\[|\Z)", content)
    if not section:
        raise SystemExit("Seção SmartThings ausente; configuração mantida.")
    body = section.group(1)
    line = "buttons=" + ",".join(selected) + "\n"
    if re.search(r"(?m)^buttons\s*=", body):
        body = re.sub(r"(?m)^buttons\s*=.*(?:\n|$)", lambda _: line, body, count=1)
    else:
        body = line + body
    content = content[:section.start(1)] + body + content[section.end(1):]
    CONFIG.write_text(content, encoding="utf-8")
    print("Mapeamento privado salvo: " + ", ".join(
        f"painel {index}: {count} teclas" for index, count in enumerate(panel_counts, 1)))


if __name__ == "__main__":
    main()
