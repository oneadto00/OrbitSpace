"""CONNECT tunnel for the isolated Git worker. TLS terminates at the Git host."""

import asyncio
import ipaddress
import os
import re
import socket

HOSTS = frozenset(host.strip().lower() for host in os.environ["ORBIT_GIT_TRUSTED_HOSTS"].split(","))
REQUEST = re.compile(rb"CONNECT ([a-zA-Z0-9.-]+):443 HTTP/1\.[01]")
GLOBAL_V6 = ipaddress.ip_network("2000::/3")
# Also exclude publicly routable special-purpose prefixes and Azure platform services.
SPECIAL = tuple(map(ipaddress.ip_network, (
    "168.63.129.16/32", "192.0.0.0/24", "192.31.196.0/24", "192.52.193.0/24",
    "192.88.99.0/24", "192.175.48.0/24", "2001::/23", "2001:db8::/32",
    "2002::/16", "2620:4f:8000::/48", "3fff::/20",
)))


def public_destination(address):
    ip = ipaddress.ip_address(address)
    return (
        ip.is_global
        and not ip.is_multicast
        and not ip.is_reserved
        and (ip.version == 4 or ip in GLOBAL_V6)
        and not any(ip in block for block in SPECIAL)
    )


async def connect_upstream(host):
    records = await asyncio.get_running_loop().getaddrinfo(
        host, 443, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP)
    # Reject mixed public/private answers. Connect only to an address from this lookup.
    if not records or not all(public_destination(record[4][0]) for record in records):
        raise PermissionError("Blocked destination")
    for family, _, _, _, address in records:
        try:
            return await asyncio.wait_for(asyncio.open_connection(
                address[0], 443, family=family, flags=socket.AI_NUMERICHOST), timeout=4)
        except OSError:
            continue
    raise ConnectionError("Upstream unavailable")


async def copy_stream(source, destination):
    while chunk := await source.read(65536):
        destination.write(chunk)
        await destination.drain()
    destination.write_eof()


async def tunnel(reader, client):
    remote = None
    try:
        async with asyncio.timeout(12):
            headers = await reader.readuntil(b"\r\n\r\n")
            request = REQUEST.fullmatch(headers.split(b"\r\n", 1)[0])
            if not request or request[1].decode("ascii").lower() not in HOSTS:
                raise PermissionError("Blocked authority")
            upstream, remote = await connect_upstream(request[1].decode("ascii").lower())
        client.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        async with asyncio.timeout(300), asyncio.TaskGroup() as group:
            group.create_task(copy_stream(reader, remote))
            group.create_task(copy_stream(upstream, client))
    except PermissionError:
        client.write(b"HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
    except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, OSError, ExceptionGroup):
        if remote is None:
            client.write(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
    finally:
        if remote is not None:
            remote.close()
        client.close()


async def main():
    server = await asyncio.start_server(tunnel, "0.0.0.0", 3128, limit=8192)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
