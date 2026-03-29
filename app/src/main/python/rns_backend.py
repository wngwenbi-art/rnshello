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

router = None
local_destination = None
kotlin_callback = None

def start_rns(storage_path, callback_obj, nickname):
    global router, local_destination, kotlin_callback
    kotlin_callback = callback_obj
    
    # Permanent Storage Directories
    os.environ["TMPDIR"] = os.path.join(str(storage_path), "cache")
    rns_dir = os.path.join(str(storage_path), ".reticulum")
    lxmf_dir = os.path.join(str(storage_path), ".lxmf")
    
    for d in [os.environ["TMPDIR"], rns_dir, lxmf_dir]:
        if not os.path.exists(d): os.makedirs(d)

    # Persistent Config
    config_path = os.path.join(rns_dir, "config")
    if not os.path.exists(config_path):
        with open(config_path, "w") as f:
            f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]")
    
    RNS.Reticulum(configdir=rns_dir)
    
    # IDENTITY PERSISTENCE: Load from file or create once
    id_path = os.path.join(rns_dir, "storage_identity")
    if os.path.exists(id_path):
        local_id = RNS.Identity.from_file(id_path)
    else:
        local_id = RNS.Identity()
        local_id.to_file(id_path)

    router = LXMRouter(identity=local_id, storagepath=lxmf_dir)
    local_destination = router.register_delivery_identity(local_id, display_name=nickname)
    router.register_delivery_callback(on_lxmf)
    
    RNS.Transport.register_announce_handler(discovery_handler())
    
    return RNS.hexrep(local_destination.hash, False)

class discovery_handler:
    def __init__(self): self.aspect_filter = None
    def received_announce(self, dest_hash, id, data):
        if kotlin_callback: kotlin_callback.onAnnounceReceived(RNS.hexrep(dest_hash, False))

def on_lxmf(lxm):
    sender = RNS.hexrep(lxm.source_hash, False)
    content = lxm.content.decode("utf-8")
    
    if content.startswith("ACK:"):
        if kotlin_callback: kotlin_callback.onMessageDelivered(content[4:])
        return

    # Auto ACK
    try:
        dest_id = RNS.Identity.recall(lxm.source_hash)
        ack_dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        router.handle_outbound(LXMessage(ack_dest, local_destination, f"ACK:{RNS.hexrep(lxm.hash, False)}"))
    except: pass

    is_img = content.startswith("IMG:")
    data = content[4:] if is_img else content
    if is_img:
        path = os.path.join(os.environ["TMPDIR"], f"rec_{int(time.time())}.webp")
        with open(path, "wb") as f: f.write(base64.b64decode(data))
        data = path
    
    if kotlin_callback: kotlin_callback.onNewMessage(sender, data, int(time.time()*1000), is_img, False, RNS.hexrep(lxm.hash, False))

def inject_rnode(freq, bw, tx, sf, cr):
    try:
        ictx = {"name": "Bridge", "type": "RNodeInterface", "interface_enabled": True, "outgoing": True,
                "tcp_host": "127.0.0.1", "tcp_port": 7633, "frequency": int(freq), "bandwidth": int(bw),
                "txpower": int(tx), "spreadingfactor": int(sf), "codingrate": int(cr), "flow_control": False}
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        ifac.IN = True; ifac.OUT = True
        RNS.Transport.interfaces.append(ifac)
        time.sleep(1)
        local_destination.announce()
        return "ONLINE"
    except Exception as e: return str(e)

def send_text(dest_hex, text):
    try:
        dest_id = RNS.Identity.recall(bytes.fromhex(dest_hex))
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(dest, local_destination, text)
        router.handle_outbound(lxm)
        return RNS.hexrep(lxm.hash, False)
    except: return ""

def send_image(dest_hex, path):
    with open(path, "rb") as f: data = base64.b64encode(f.read()).decode("utf-8")
    return send_text(dest_hex, f"IMG:{data}")

def announce_now(): local_destination.announce()