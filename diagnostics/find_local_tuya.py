"""Read-only TCP reachability check on a small private LAN subnet."""
import argparse
import ipaddress
import socket
from concurrent.futures import ThreadPoolExecutor


def reachable(address):
    try:
        with socket.create_connection((str(address), 6668), timeout=0.4):
            return str(address)
    except (OSError, TimeoutError):
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("subnet", help="Your private IPv4 LAN subnet in CIDR notation")
    args = parser.parse_args()
    subnet = ipaddress.ip_network(args.subnet, strict=False)
    if (subnet.version != 4 or subnet.num_addresses > 256 or not subnet.is_private
            or subnet.is_loopback or subnet.is_link_local or subnet.is_reserved):
        parser.error("Use only your private IPv4 LAN with at most 256 addresses")
    with ThreadPoolExecutor(max_workers=24) as pool:
        hits = [address for address in pool.map(reachable, subnet.hosts()) if address]
    if hits:
        print("Porta TCP 6668 acessível em:")
        for address in hits:
            print(address)
    else:
        print("Nenhum dispositivo respondeu na porta TCP 6668 nesta rede.")


if __name__ == "__main__":
    main()
