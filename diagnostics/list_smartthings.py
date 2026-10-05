"""List SmartThings devices and switch components using the ignored private token."""

import configparser
import json
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / ".secrets" / "config.ini"
API = "https://api.smartthings.com/v1/devices"


def get_json(url: str, token: str) -> dict:
    request = Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/json"})
    with urlopen(request, timeout=15) as response:
        return json.load(response)


def main() -> None:
    config = configparser.ConfigParser()
    if not config.read(CONFIG, encoding="utf-8"):
        raise SystemExit("Arquivo .secrets/config.ini não encontrado. Execute setup.ps1.")
    token = config.get("smartthings", "token", fallback="").strip()
    if not token:
        raise SystemExit("Preencha smartthings.token em .secrets/config.ini; não cole o token no chat.")
    try:
        devices = get_json(API, token).get("items", [])
        if not devices:
            print("Nenhum dispositivo na conta SmartThings. Confira a vinculação no iPhone.")
            return
        for device in devices:
            device_id = device.get("deviceId", "")
            if not device_id:
                continue
            detail = get_json(f"{API}/{device_id}", token)
            components = detail.get("components", [])
            switches = [c.get("id", "") for c in components
                        if any(cap.get("id") == "switch" for cap in c.get("capabilities", []))]
            if not switches:
                continue
            label = detail.get("label") or detail.get("name") or "Sem nome"
            print(f"{label}: {device_id}")
            for component in switches:
                print(f"  alvo={device_id}|{component}")
        print("Copie apenas os alvos das luzes desejadas para smartthings.targets no arquivo privado.")
    except HTTPError as error:
        raise SystemExit(f"SmartThings respondeu HTTP {error.code}; confira token, permissões e validade.") from None
    except URLError:
        raise SystemExit("Não foi possível acessar a API SmartThings; confira a conexão do PC.") from None


if __name__ == "__main__":
    main()
