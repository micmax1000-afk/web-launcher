#!/usr/bin/env python3
"""Controlla i feed in app/src/main/assets/news/sources.json (indirizzo principale e di riserva)."""
import json, re, sys, concurrent.futures as cf, urllib.request

SRC = "app/src/main/assets/news/sources.json"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

def check(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/rss+xml, application/xml, text/xml, */*"})
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read(3_000_000).decode("utf-8", "replace")
            items = len(re.findall(r"<(item|entry)[\s>]", body))
            imgs = len(re.findall(r"<enclosure|<media:(content|thumbnail)|<img", body))
            return (items > 0, f"{r.status} items={items} img={imgs} final={r.geturl()}")
    except Exception as e:
        return (False, f"ERR {type(e).__name__}: {str(e)[:80]}")

sources = json.load(open(SRC))
jobs = [(s["id"], i, u) for s in sources for i, u in enumerate([s["url"]] + s.get("alt", []))]
with cf.ThreadPoolExecutor(16) as ex:
    res = dict(zip(jobs, ex.map(lambda j: check(j[2]), jobs)))
bad = 0
for s in sources:
    oks = [(i, u, res[(s["id"], i, u)]) for (sid, i, u) in jobs if sid == s["id"]]
    working = [o for o in oks if o[2][0]]
    status = "OK  " if working else "FAIL"
    if not working: bad += 1
    print(f"{status} {s['id']:<18} {s['cat']}")
    for i, u, (ok, info) in oks:
        print(f"      {'✓' if ok else '✗'} [{i}] {u}  {info}")
print(f"\n{len(sources) - bad}/{len(sources)} fonti funzionanti")
