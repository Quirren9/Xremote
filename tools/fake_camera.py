#!/usr/bin/env python3
"""
Fake Fujifilm X-T3 for testing X Remote without a camera.

Speaks the same Fuji PTP/IP dialect the app expects: registration on 55740,
USB-style PTP containers, events on 55741, JPEG live view on 55742.
It's a model of the protocol as the app understands it, not the real camera.

Run:  python3 fake_camera.py [--host 0.0.0.0]
Then point the app (or the Android emulator, host 10.0.2.2) at this machine.
Needs Pillow for the animated live view:  pip install pillow
"""
import argparse, io, socket, struct, threading, time, math

OK, NOT_SUPPORTED, BAD_VALUE, PROP_NOT_SUPPORTED = 0x2001, 0x2005, 0x201C, 0x200A
U16, U32, I16 = 4, 6, 3

def ptp_str(s):
    if not s: return b"\x00"
    s += "\x00"
    return bytes([len(s)]) + s.encode("utf-16-le")

def u16arr(a): return struct.pack("<I", len(a)) + b"".join(struct.pack("<H", x) for x in a)

FMT = {U16: "<H", U32: "<I", I16: "<h"}

class Prop:
    def __init__(self, code, typ, value, enum=None, rng=None, writable=True):
        self.code, self.typ, self.value, self.enum, self.rng, self.writable = code, typ, value, enum, rng, writable
    def desc(self):
        f = FMT[self.typ]
        b = struct.pack("<HHB", self.code, self.typ, 1 if self.writable else 0)
        b += struct.pack(f, self.value) * 2
        if self.enum:
            b += b"\x02" + struct.pack("<H", len(self.enum)) + b"".join(struct.pack(f, v) for v in self.enum)
        elif self.rng:
            b += b"\x01" + b"".join(struct.pack(f, v) for v in self.rng)
        else:
            b += b"\x00"
        return b

PROPS = {p.code: p for p in [
    Prop(0x500E, U16, 3, writable=False),                                   # mode A
    Prop(0xD240, U32, 4000, enum=[250, 500, 1000, 2000, 4000, 8000, 16666, 33333, 66666, 125000, 250000, 500000, 1000000, 2000000]),
    Prop(0x5007, U16, 280, enum=[140, 200, 280, 400, 560, 800, 1100, 1600]),
    Prop(0xD02A, U32, 400, enum=[160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 3200, 6400, 12800, 0x80000000 | 3200]),
    Prop(0x5010, I16, 0, rng=[-3000, 3000, 333]),
    Prop(0xD001, U16, 11, enum=list(range(1, 17))),
    Prop(0x5005, U16, 2, enum=[2, 4, 6, 0x8001, 0x8006, 0x8007, 0x8008]),
    Prop(0xD018, U16, 4, enum=[1, 2, 3, 4, 5]),
    Prop(0x500A, U16, 0x8001, enum=[1, 0x8001, 0x8002]),
    Prop(0x5012, U16, 0, enum=[0, 2000, 10000]),
    Prop(0xD17C, U32, 0x0604, rng=[0, 0x0C08, 1]),
    Prop(0xD242, U16, 3, writable=False),
    Prop(0xD229, U32, 1234, writable=False),
    Prop(0xD105, U16, 2, enum=[1, 2, 3]),   # an unknown vendor prop, to test generic UI
]}
changed = {}
lock = threading.Lock()
event_socks = []
PHOTOS = {}   # handle -> (filename, jpeg bytes)
STATE = {'client': 5}

def make_photo():
    try:
        from PIL import Image
        img = Image.new("RGB", (1200, 800), (90, 140, 200)); b = io.BytesIO(); img.save(b, "JPEG", quality=85); return b.getvalue()
    except Exception:
        return b"\xff\xd8" + b"\x00" * 200000 + b"\xff\xd9"

def object_info(h):
    name, data = PHOTOS[h]
    return (struct.pack("<IHHI", 0x10000001, 0x3801, 0, len(data)) + struct.pack("<HIII", 0x3808, 0, 0, 0)
            + struct.pack("<IIIIHII", 1200, 800, 24, 0, 0, 0, 0) + ptp_str(name) + ptp_str("") + ptp_str("") + ptp_str(""))

def send_event(code, *params):
    pkt = struct.pack("<HHI", 4, code, 1) + b"".join(struct.pack("<I", p) for p in params)
    for es in list(event_socks):
        try: es.sendall(struct.pack("<I", 4 + len(pkt)) + pkt)
        except Exception: event_socks.remove(es)

def container(typ, code, tid, payload=b""):
    return struct.pack("<IHHI", 12 + len(payload), typ, code, tid) + payload

def recv_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise EOFError
        b += c
    return b

def recv_packet(s):
    n = struct.unpack("<I", recv_exact(s, 4))[0]
    return recv_exact(s, n - 4)

def device_info():
    return (struct.pack("<HIH", 100, 0x0E, 100) + ptp_str("fujifilm.co.jp: 1.0;") + struct.pack("<H", 0)
            + u16arr([0x1001, 0x1002, 0x1003, 0x100E, 0x1014, 0x1015, 0x1016, 0x902B, 0x9037])
            + u16arr([0x4002, 0x400C]) + u16arr(sorted(PROPS)) + u16arr([]) + u16arr([0x3801])
            + ptp_str("FUJIFILM") + ptp_str("X-T3") + ptp_str("4.10") + ptp_str("FAKE0001"))

def handle_control(s):
    pkt = recv_packet(s)
    name = pkt[24:78].decode("utf-16-le", "ignore").rstrip("\x00")
    print(f"[ctl] register type={struct.unpack('<I', pkt[:4])[0]} client='{name}'")
    s.sendall(struct.pack("<II", 68, 2) + b"\x00" * 20 + "X-T3".encode("utf-16-le") + b"\x00" * 32)
    pending = {}
    while True:
        body = recv_packet(s)
        typ, code, tid = struct.unpack("<HHI", body[:8])
        payload = body[8:]
        if typ == 1:
            params = [struct.unpack("<I", payload[i:i+4])[0] for i in range(0, len(payload), 4)]
            if code == 0x1016:   # wait for data phase
                pending[tid] = params; continue
            resp, data = run(code, params, None)
        elif typ == 2:
            resp, data = run(code, pending.pop(tid), payload)
        else:
            continue
        if resp == "LATE":
            threading.Timer(4.0, lambda t=tid: s.sendall(container(3, OK, t))).start()
            continue
        if data is not None: s.sendall(container(2, code, tid, data))
        s.sendall(container(3, resp, tid))

def run(code, params, data):
    p0 = params[0] if params else 0
    with lock:
        if code in (0x1002, 0x1003, 0x101C, 0x1018): return OK, None
        if code == 0x902B:  # like the real X-T3: u32 count, then (u32 len incl. itself + DevicePropDesc) per prop
            listed = [p for c, p in PROPS.items() if c not in (0xD242, 0xD229, 0xD17C)]
            return OK, struct.pack("<I", len(listed)) + b"".join(struct.pack("<I", 4 + len(p.desc())) + p.desc() for p in listed)
        if code == 0x9037: return "LATE", None   # real X-T3 doesn't answer in time
        if code == 0x1001: return OK, b""         # real X-T3 returns empty DeviceInfo over WiFi
        if code == 0x1014:
            return (OK, PROPS[p0].desc()) if p0 in PROPS else (PROP_NOT_SUPPORTED, None)
        if code == 0x1015:
            if p0 == 0xD212:   # like the X-T3: a snapshot that includes the object count
                changed[0xD222] = len(PHOTOS)
                items = list(changed.items()); changed.clear()
                return OK, struct.pack("<H", len(items)) + b"".join(struct.pack("<HI", c, v & 0xFFFFFFFF) for c, v in items)
            if p0 == 0xDF24: return OK, struct.pack("<I", 0x00020005)
            if p0 in (0xDF00, 0xDF01): return OK, struct.pack("<H", 5)
            if p0 in PROPS: p = PROPS[p0]; return OK, struct.pack(FMT[p.typ], p.value)
            return PROP_NOT_SUPPORTED, None
        if code == 0x1016:
            if p0 == 0xDF01: STATE['client'] = struct.unpack('<H', data[:2])[0]; return OK, None
            if p0 in (0xDF00, 0xDF24, 0xDF25, 0xD208): return OK, None
            p = PROPS.get(p0)
            if not p: return PROP_NOT_SUPPORTED, None
            if not p.writable: return 0x200E, None  # AccessDenied (dial not on A)
            v = struct.unpack(FMT[p.typ], data[:struct.calcsize(FMT[p.typ])])[0]
            if p.enum and v not in p.enum: return BAD_VALUE, None
            p.value = v; print(f"[ctl] set {p0:#06x} = {v:#x}"); return OK, None
        if code == 0x100E:
            time.sleep(0.3); PROPS[0xD229].value -= 1; changed[0xD229] = PROPS[0xD229].value
            h = 0x220 + 6 * len(PHOTOS); PHOTOS[h] = (f"DSCF{1000 + len(PHOTOS):04d}.JPG", make_photo())
            threading.Timer(0.5, lambda: (send_event(0xC004, h - 6, h - 6), send_event(0xC001, h, h, 0x64a8))).start()
            print(f"[ctl] *click* -> handle {h:#x}"); return OK, None
        if code == 0x9022:   # GetCapturePreview
            return (OK, make_photo()) if PHOTOS else (0x2009, None)
        gallery = list(PHOTOS)[::-1]   # newest first, numbered 1..N in gallery mode
        if code == 0x1008:
            if STATE['client'] != 11 or not 1 <= p0 <= len(gallery): return 0x2009, None
            return OK, object_info(gallery[p0 - 1])
        if code == 0x101B:
            if STATE['client'] != 11 or not 1 <= p0 <= len(gallery): return 0x2009, None
            data = PHOTOS[gallery[p0 - 1]][1]; off, n = params[1], params[2]; return OK, data[off:off + n]
    return NOT_SUPPORTED, None

def frames():
    from PIL import Image, ImageDraw
    t = 0
    while True:
        img = Image.new("RGB", (640, 427), (20, 24, 30))
        d = ImageDraw.Draw(img)
        x = 320 + int(200 * math.sin(t / 15)); y = 213 + int(120 * math.cos(t / 11))
        d.ellipse([x - 40, y - 40, x + 40, y + 40], fill=(230, 160, 40))
        p = PROPS
        d.text((12, 10), f"FAKE X-T3  film={p[0xD001].value} iso={p[0xD02A].value & 0xFFFFFF} f/{p[0x5007].value/100:.1f}", fill=(255, 255, 255))
        buf = io.BytesIO(); img.save(buf, "JPEG", quality=70)
        yield buf.getvalue(); t += 1

def handle_liveview(s):
    for jpg in frames():
        header = b"\x00" * 14
        s.sendall(struct.pack("<I", 4 + len(header) + len(jpg)) + header + jpg)
        time.sleep(1 / 25)

def handle_events(s):
    event_socks.append(s)
    while True:
        time.sleep(30)

def serve(host, port, fn):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port)); srv.listen(2)
    def loop():
        while True:
            c, a = srv.accept(); print(f"[{port}] connection from {a[0]}")
            threading.Thread(target=lambda: _safe(fn, c, port), daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()

def _safe(fn, c, port):
    try: fn(c)
    except (EOFError, ConnectionError, BrokenPipeError): print(f"[{port}] closed")
    finally: c.close()

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--host", default="0.0.0.0"); a = ap.parse_args()
    serve(a.host, 55740, handle_control); serve(a.host, 55741, handle_events); serve(a.host, 55742, handle_liveview)
    print(f"Fake X-T3 listening on {a.host}:55740-55742, Ctrl+C to stop")
    while True: time.sleep(1)
