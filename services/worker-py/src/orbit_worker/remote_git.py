"""Fetch into a fresh, quota-bounded bare repository. No file checkout or repository scripts."""
from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import urlsplit

OBJECT_LIMIT = 20000
COMMIT_LIMIT = 4000
REF_LIMIT = 2048


def observe(source: dict, directory: Path, output_limit: int) -> bytes:
    url = source["repositoryUrl"]
    parsed = urlsplit(url)
    hosts = set(os.environ["ORBIT_GIT_TRUSTED_HOSTS"].split(","))
    if (parsed.scheme != "https" or parsed.netloc not in hosts or parsed.query or parsed.fragment
            or not re.fullmatch(r"(/[a-zA-Z0-9._-]+){2,}", parsed.path)
            or any(part in {".", ".."} for part in parsed.path.split("/"))):
        raise ValueError("REMOTE_URL_FORBIDDEN")
    proxy = os.environ.get("HTTPS_PROXY")
    if proxy != "http://egress-proxy:3128":
        raise ValueError("EGRESS_PROXY_REQUIRED")

    env = {
        "PATH": os.defpath, "HOME": str(directory), "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_ATTR_NOSYSTEM": "1", "GIT_CONFIG_SYSTEM": "/dev/null",
        "GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": "/bin/false", "GIT_TEMPLATE_DIR": "",
        "GIT_NO_REPLACE_OBJECTS": "1", "HTTPS_PROXY": proxy,
    }
    token = source.pop("token", None)
    if token:
        username = {"gitlab.com": "oauth2", "bitbucket.org": "x-token-auth"}.get(parsed.hostname, "x-access-token")
        basic = base64.b64encode((username + ":" + token).encode()).decode("ascii")
        env.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="http.extraHeader",
                   GIT_CONFIG_VALUE_0="Authorization: Basic " + basic)
        del token, basic

    common = ["git", "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
              "-c", "submodule.recurse=false", "-c", "core.hooksPath=/dev/null",
              "-c", "http.sslVerify=true", "-c", "http.followRedirects=false", "-c", "http.proxy=" + proxy,
              "-c", "fetch.fsckObjects=true", "-c", "transfer.fsckObjects=true", "-c", "gc.auto=0",
              "-c", "credential.helper=", "-c", "pack.threads=1", "-c", "core.attributesFile=/dev/null"]
    repo = directory / "repo.git"

    def git(args, stdin=None, cwd=repo):
        completed = subprocess.run(common + args, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   cwd=cwd, env=env, shell=False, check=True)
        return completed.stdout

    try:
        git(["clone", "--bare", "--no-local", "--no-recurse-submodules", "--", url, str(repo)], cwd=directory)
    finally:
        for key in ("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0"):
            env.pop(key, None)

    counts = dict(line.split(": ", 1) for line in git(["count-objects", "-v"]).decode().splitlines())
    if int(counts["count"]) + int(counts["in-pack"]) > OBJECT_LIMIT:
        raise ValueError("OBJECT_LIMIT")
    git(["fsck", "--full", "--strict", "--no-reflogs"])
    object_format = git(["rev-parse", "--show-object-format"]).decode().strip()
    refs = []
    tips = set()
    for line in git(["for-each-ref", "--format=%(objecttype) %(objectname) %(refname)", "refs/heads", "refs/tags"]).splitlines():
        kind, oid, name = line.decode("utf-8").split(" ", 2)
        if kind == "tag":
            try:
                oid = git(["rev-parse", "--verify", oid + "^{commit}"]).decode().strip()
            except subprocess.CalledProcessError:
                continue  # A tag may point directly at a blob or tree.
        elif kind != "commit":
            continue
        if len(name) > 300 or len(refs) >= REF_LIMIT:
            raise ValueError("REF_LIMIT")
        tips.add(oid)
        refs.append({"name": name, "oid": oid})

    oids = git(["rev-list", "--topo-order", "--stdin"], ("\n".join(sorted(tips))).encode()).splitlines()
    if len(oids) > COMMIT_LIMIT:
        raise ValueError("COMMIT_LIMIT")
    queries = b"".join(oid + b"\n" for oid in oids)
    headers = git(["cat-file", "--batch-check=%(objectsize)"], queries).splitlines()
    if len(headers) != len(oids) or sum(map(int, headers)) * 4 // 3 > output_limit:
        raise ValueError("OUTPUT_LIMIT")
    objects = git(["cat-file", "--batch"], queries)
    offset = 0
    commits = []
    for expected in oids:
        end = objects.index(b"\n", offset)
        oid, kind, size = objects[offset:end].split()
        if oid != expected or kind != b"commit":
            raise ValueError("INVALID_OBJECT")
        data = objects[end + 1:end + 1 + int(size)]
        offset = end + int(size) + 2
        commits.append({"oid": oid.decode(), "rawBase64": base64.b64encode(data).decode()})
    result = json.dumps(dict(objectFormat=object_format, refs=refs, commits=commits, completeHistory=True),
                        separators=(",", ":")).encode()
    if len(result) > output_limit:
        raise ValueError("OUTPUT_LIMIT")
    return result
