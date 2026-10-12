"""Live routing check against the running app. Every case uses a fresh session that has one uploaded file.
Single-message cases test the coordinator's routing alone; two-message cases test the follow-up rewrite."""
import json
import sys
import time
import urllib.request
import uuid

import os
BASE = os.environ.get("BASE", "http://localhost:8080")
NOTES = open(sys.argv[1], "rb").read()


def post_json(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.load(r)


def upload(session):
    boundary = "----b" + uuid.uuid4().hex
    parts = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"sessionId\"\r\n\r\n{session}\r\n"
             f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n"
             f"Content-Type: text/plain\r\n\r\n").encode() + NOTES + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/files", data=parts,
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


SINGLE = [
    ("rag", "What does the Reliability pillar say about backups?"),
    ("rag", "How should secrets and credentials be managed and rotated?"),
    ("rag", "What are the four disaster recovery strategies?"),
    ("file", "Summarize my file"),
    ("file", "What does the file I uploaded say about costs?"),
    ("file", "Give me a one-sentence summary of what I attached"),
]
# (expected route of the 2nd message, first message, second message, text the rewrite must NOT contain)
PAIRS = [
    ("rag_agent", "What is the AWS Well-Architected Tool?", "Who can use it?", "uploaded file"),
    ("rag_agent", "What are the pillars of the framework?", "And what does the cost one cover?", "uploaded file"),
    ("rag_agent", "Summarize my file", "What does the Security pillar say about encryption at rest?", "uploaded file"),
    ("file_agent", "Summarize my file", "Make it shorter", None),
]

ok = bad = 0
print("== single messages (routing only)")
for expect, msg in SINGLE:
    sid = "check-" + uuid.uuid4().hex[:8]
    upload(sid)
    t = time.time()
    r = post_json("/api/chat", {"sessionId": sid, "message": msg})
    route = r["route"]
    good = (route == "file_agent") == (expect == "file")
    ok += good
    bad += not good
    print(f"{'OK ' if good else 'BAD'} expect={expect:4} got={route:10} kind={r['kind']:9} {r['totalMillis']:>6} ms | {msg}")

print("== two-message follow-ups (rewrite and routing of the second message)")
for expect_route, first, second, forbidden in PAIRS:
    sid = "check-" + uuid.uuid4().hex[:8]
    upload(sid)
    post_json("/api/chat", {"sessionId": sid, "message": first})
    r = post_json("/api/chat", {"sessionId": sid, "message": second})
    rewritten = r.get("rewrittenQuestion")
    good = r["route"] == expect_route and not (forbidden and rewritten and forbidden in rewritten)
    ok += good
    bad += not good
    print(f"{'OK ' if good else 'BAD'} expect={expect_route:10} got={r['route']:10} kind={r['kind']:9} {r['totalMillis']:>6} ms"
          f"\n      first : {first}\n      second: {second}\n      rewritten: {rewritten}")
print(f"\n{ok} ok, {bad} bad")
