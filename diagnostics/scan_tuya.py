"""Read-only LAN discovery; prints only a short suffix of each device ID."""
import tinytuya


def main():
    devices = tinytuya.deviceScan(verbose=False, maxretry=10, poll=False)
    print("IP local        ID virtual (final)  Protocolo")
    for ip, details in sorted(devices.items()):
        device_id = details.get("gwId", "")
        print(f"{ip:<15} {device_id[-6:]:<19} {details.get('version', '?')}")


if __name__ == "__main__":
    main()
