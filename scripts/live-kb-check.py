"""Live check of POST /api/kb/documents: add a small PDF with a unique fact, watch the stream, then ask a question about it."""
import json
import sys
import time
import urllib.request
import uuid

BASE = "http://localhost:8080"
FACT = "The Zorblax retention window for quantum audit logs is 47 days."


def make_pdf(lines):
    content = "BT /F1 11 Tf 50 750 Td 14 TL\n" + "\n".join(f"({l}) Tj T*" for l in lines) + "\nET"
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        f"<< /Length {len(content)} >>\nstream\n{content}\nendstream",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = b"%PDF-1.4\n"
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode("latin-1")
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return out


def multipart(fields, files):
    b = "----b" + uuid.uuid4().hex
    body = b""
    for k, v in fields.items():
        body += f'--{b}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    for k, (name, data, ctype) in files.items():
        body += (f'--{b}\r\nContent-Disposition: form-data; name="{k}"; filename="{name}"\r\n'
                 f'Content-Type: {ctype}\r\n\r\n').encode() + data + b"\r\n"
    body += f"--{b}--\r\n".encode()
    return body, "multipart/form-data; boundary=" + b


def add(name, data, replace=False):
    body, ctype = multipart({"replace": str(replace).lower()}, {"file": (name, data, "application/pdf")})
    req = urllib.request.Request(BASE + "/api/kb/documents", data=body, headers={"Content-Type": ctype,
                                                                               "Accept": "text/event-stream"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            print("HTTP", r.status, r.headers.get("Content-Type"))
            for raw in r:
                line = raw.decode().rstrip()
                if line:
                    print(f"  +{time.time() - t0:5.1f}s {line}")
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode())


def ask(session, message):
    req = urllib.request.Request(BASE + "/api/chat", data=json.dumps({"sessionId": session, "message": message}).encode(),
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.load(r)
    return d


name = "kb-live-test.pdf"
good = make_pdf(["Zorblax operations handbook.", FACT, "Logs older than that are deleted by the nightly job.",
                 "This sentence exists so that the page has enough text to be chunked into a passage."])
print("== add a PDF with a text layer")
add(name, good)
print("== the same file again (idempotent)")
add(name, good)
print("== a scan without a text layer")
add("scan.pdf", make_pdf([]))
print("== not a PDF")
add("notes.pdf", b"just some text, not a pdf")
print("== ask about the new document right away")
for attempt in range(1, 4):
    d = ask("kb-live-" + uuid.uuid4().hex[:6], "What is the Zorblax retention window for quantum audit logs?")
    print(f"attempt {attempt}: kind={d['kind']} {d['totalMillis']} ms | {d['answer'][:200]!r}")
    if d["kind"] == "answer":
        break
    time.sleep(5)
