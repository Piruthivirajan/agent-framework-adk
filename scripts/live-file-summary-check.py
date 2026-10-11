"""Live scenario C measurement: upload each given file to a fresh session, ask for a summary through the
streaming endpoint, print the stage timeline, the strategy, and the summary. Needs the application running.

    python scripts/live-file-summary-check.py corpus/microservices-on-aws.pdf [more files...]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("AGENTFW_BASE", "http://localhost:8080")


def upload(session, path):
    boundary = "----b" + uuid.uuid4().hex
    name = os.path.basename(path)
    with open(path, "rb") as f:
        data = f.read()
    ctype = "application/pdf" if name.lower().endswith(".pdf") else "text/plain"
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="sessionId"\r\n\r\n{session}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            f'Content-Type: {ctype}\r\n\r\n').encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/files", data=body,
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def summarize(session, file_id, instruction):
    req = urllib.request.Request(
        BASE + "/api/chat/stream",
        data=json.dumps({"sessionId": session, "message": instruction, "fileId": file_id}).encode(),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
    t0 = time.time()
    stages, result, error = [], None, None
    with urllib.request.urlopen(req, timeout=1800) as r:
        event = None
        for raw in r:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data = json.loads(line[5:])
                if event == "stage":
                    stages.append((round(time.time() - t0, 1), data["stage"], data.get("detail", "")))
                elif event == "result":
                    result = data
                elif event == "error":
                    error = data
    return time.time() - t0, stages, result, error


def main(paths):
    for path in paths:
        session = "scenc-" + uuid.uuid4().hex[:8]
        print("=" * 100)
        print(os.path.basename(path), f"({os.path.getsize(path) / 1e6:.1f} MB)")
        try:
            t = time.time()
            f = upload(session, path)
            print(f"upload+extract: {time.time() - t:.1f} s -> {f['chars']:,} chars, pages={f['pages']}")
        except urllib.error.HTTPError as e:
            print("REJECTED", e.code, e.read().decode())
            continue
        elapsed, stages, result, error = summarize(session, f["id"], "Summarize this document in about 150 words.")
        if error:
            print("ERROR", error)
            continue
        info = result["file"]
        print(f"turn: {elapsed:.1f} s | strategy {info['strategy']} | {info['segments']} parts | "
              f"{info['modelCalls']} model calls | summarizer {info['millis'] / 1000:.1f} s")
        compact = {}
        for at, stage, detail in stages:
            compact.setdefault(stage, []).append((at, detail))
        for stage, items in compact.items():
            print(f"  {stage}: {len(items)} events, first at {items[0][0]} s, last at {items[-1][0]} s "
                  f"{items[-1][1]}")
        print("summary:", result["answer"].replace("\n", " ")[:900])


if __name__ == "__main__":
    main(sys.argv[1:])
