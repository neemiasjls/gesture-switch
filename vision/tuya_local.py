"""Authenticated LAN control for the three configured Tuya switches.

The configuration is local and ignored by Git. --probe reads status only.
"""
import argparse
import configparser
import ipaddress
import sys
from pathlib import Path

import tinytuya


def load_devices(path):
    config = configparser.ConfigParser(interpolation=None)
    if not config.read(Path(path), encoding="utf-8"):
        raise ValueError("Arquivo de configuração não encontrado")
    home_subnet = ipaddress.ip_network(config.get("network", "cidr"), strict=False)
    if home_subnet.version != 4 or home_subnet.num_addresses > 256 or not home_subnet.is_private:
        raise ValueError("A rede configurada deve ser uma sub-rede IPv4 privada de até 256 endereços")
    version = config.getfloat("tuya", "version")
    if version not in (3.1, 3.2, 3.3, 3.4, 3.5):
        raise ValueError("Versão Tuya inválida")
    devices = []
    for number in range(1, 4):
        section = f"device_{number}"
        suffix = config.get(section, "suffix").strip()
        device_id = config.get(section, "id").strip()
        dps_text = config.get(section, "dps").strip()
        item = {
            "suffix": suffix,
            "id": device_id,
            "ip": config.get(section, "ip").strip(),
            "local_key": config.get(section, "local_key").strip(),
            "dps": [int(part.strip()) for part in dps_text.split(",")] if dps_text else [],
            "version": version,
        }
        if len(suffix) != 6 or not device_id.endswith(suffix):
            raise ValueError(f"ID completo ausente ou incompatível em {section}")
        devices.append(item)
    if len({item["suffix"] for item in devices}) != 3 or len({item["id"] for item in devices}) != 3:
        raise ValueError("Os três dispositivos devem ter IDs distintos")
    for item in devices:
        address = ipaddress.ip_address(item.get("ip", ""))
        if address not in home_subnet:
            raise ValueError("O IP do dispositivo está fora da rede local configurada")
        if not isinstance(item.get("local_key"), str) or len(item["local_key"]) != 16:
            raise ValueError("Cada dispositivo precisa de uma chave local de 16 caracteres")
        dps = item.get("dps")
        if not isinstance(dps, list) or not dps or any(
                not isinstance(dp, int) or isinstance(dp, bool) or not 1 <= dp <= 255
                for dp in dps) or len(set(dps)) != len(dps):
            raise ValueError("Informe os DPS de luz válidos, sem repetir canais")
    return devices


def open_and_read(item):
    device = tinytuya.OutletDevice(
        item["id"], item["ip"], item["local_key"], version=item["version"]
    )
    device.set_socketTimeout(3)
    device.set_socketRetryLimit(1)
    status = device.status()
    dps = status.get("dps") if isinstance(status, dict) else None
    if not isinstance(dps, dict):
        raise RuntimeError(f"Não foi possível autenticar/ler {item['id'][-6:]}")
    for dp in item["dps"]:
        if not isinstance(dps.get(str(dp)), bool):
            raise RuntimeError(f"DPS {dp} ausente ou não booleano em {item['id'][-6:]}")
    return device, dps


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--probe", action="store_true", help="Somente consulta, sem comando")
    action.add_argument("--set", choices=("on", "off"))
    args = parser.parse_args()

    devices = load_devices(args.config)
    ready = [(item, *open_and_read(item)) for item in devices]
    if args.probe:
        for item, _, dps in ready:
            values = ", ".join(f"DPS {dp}={dps[str(dp)]}" for dp in item["dps"])
            print(f"{item['id'][-6:]} ({item['ip']}): {values}")
        return

    desired = args.set == "on"
    for item, device, before in ready:
        for dp in item["dps"]:
            if before[str(dp)] != desired:
                device.set_status(desired, dp)
        _, after = open_and_read(item)
        if any(after[str(dp)] != desired for dp in item["dps"]):
            raise RuntimeError(f"Estado não confirmado em {item['id'][-6:]}; confira no app")
        print(f"[TUYA LOCAL] {item['id'][-6:]}: {'LIGADO' if desired else 'DESLIGADO'}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, configparser.Error, RuntimeError) as exc:
        print(f"Controle local indisponível: {exc}", file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("Falha na comunicação local. Confira a chave, o IP e o app.", file=sys.stderr)
        sys.exit(1)
