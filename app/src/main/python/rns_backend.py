import os, sys, time, base64
from types import ModuleType
import importlib.util, importlib.machinery

# --- SIDEBAND/COLUMBA COMPATIBILITY MOCKS ---
class Dummy:
    def __getattr__(self, name): return Dummy()
    def __call__(self, *args, **kwargs): return Dummy()
mock_usb = ModuleType("usbserial4a"); mock_usb.__spec__ = importlib.machinery.ModuleSpec("usbserial4a", None)
mock_usb.serial4a = Dummy(); mock_usb.get_ports_list = lambda: []; sys.modules["usbserial4a"] = mock_usb
mock_jnius = ModuleType("jnius"); mock_jnius.__spec__ = importlib.machinery.ModuleSpec("jnius", None)
mock_jnius.autoclass = lambda x: Dummy(); mock_jnius.cast = lambda x, y: Dummy(); sys.modules["jnius"] = mock_jnius
_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec

import RNS, LXMF
from LXMF import LXMRouter, LXMessage
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

router = None; local_destination = None; kotlin_callback = None; is_rns_running = False

def log(msg):
    print(f"RNS-LOG: {msg}")
    sys.stdout.flush()

def start_rns(storage_path, callback_obj, nickname):
    global router, local_destination, kotlin_callback, is_rns_running
    kotlin_callback = callback_obj
    if is_rns_running and local_destination is not None: return RNS.hexrep(local_destination.hash, False)
    os.environ["TMPDIR"] = os.path.join(str(storage_path), "cache")
    rns_dir = os.path.join(str(storage_path), ".reticulum")
    lxmf_dir = os.path.join(str(storage_path), ".lxmf")
    for d in [os.environ["TMPDIR"], rns_dir, lxmf_dir]:
        if not os.path.exists(d): os.makedirs(d)
    with open(os.path.join(rns_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]")
    try: RNS.Reticulum(configdir=rns_dir)
    except OSError: pass
    id_path = os.path.join(rns_dir, "storage_identity")
    local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
    if not os.path.exists(id_path): local_id.to_file(id_path)
    router = LXMRouter(identity=local_id, storagepath=lxmf_dir)
    local_destination = router.register_delivery_identity(local_id, display_name=nickname)
    router.register_delivery_callback(on_lxmf)
    RNS.Transport.register_announce_handler(discovery_handler())
    is_rns_running = True
    return RNS.hexrep(local_destination.hash, False)

class discovery_handler:
    def __init__(self): self.aspect_filter = None
    def received_announce(self, destination_hash, announced_identity, app_data):
        h = RNS.hexrep(destination_hash, False); n = app_data.decode("utf-8") if app_data else ""
        log(f"DISCOVERY: {h} ({n})")
        if kotlin_callback: kotlin_callback.onAnnounceReceived(h, n)

def on_lxmf(lxm):
    sender = RNS.hexrep(lxm.source_hash, False)
    log(f"LXMF RECEIVED from {sender}")
    content = lxm.content.decode("utf-8")
    if content.startswith("ACK:"):
        if kotlin_callback: kotlin_callback.onMessageDelivered(content[4:])
        return
    is_img = content.startswith("IMG:")
    if is_img:
        path = os.path.join(os.environ["TMPDIR"], f"rec_{int(time.time())}.webp")
        with open(path, "wb") as f: f.write(base64.b64decode(content[4:]))
        content = path
    if kotlin_callback: kotlin_callback.onNewMessage(sender, content, int(time.time()*1000), is_img, False, RNS.hexrep(lxm.hash, False))

def inject_rnode(freq, bw, tx, sf, cr):
    log(f"TUNING: F:{freq} BW:{bw} SF:{sf} CR:{cr}")
    try:
        ictx = {"name": "Bridge", "type": "RNodeInterface", "interface_enabled": True, "outgoing": True,
                "tcp_host": "127.0.0.1", "tcp_port": 7633, "frequency": int(freq), "bandwidth": int(bw),
                "txpower": int(tx), "spreadingfactor": int(sf), "codingrate": int(cr), "flow_control": False}
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        ifac.IN = True; ifac.OUT = True
        RNS.Transport.interfaces.append(ifac)
        log("Interface Injection Done.")
        return "ONLINE"
    except Exception as e: return str(e)

def send_text(dest_hex, text):
    try:
        log(f"LXMF SENDING to {dest_hex}...")
        dest_id = RNS.Identity.recall(bytes.fromhex(dest_hex))
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(dest, local_destination, text)
        router.handle_outbound(lxm)
        return RNS.hexrep(lxm.hash, False)
    except: return ""

def send_image(dest_hex, path):
    with open(path, "rb") as f: data = base64.b64encode(f.read()).decode("utf-8")
    return send_text(dest_hex, f"IMG:{data}")

def announce_now():
    if local_destination:
        log("SENDING ANNOUNCE...")
        local_destination.announce(app_data=local_destination.display_name.encode("utf-8"))