import os, sys, time, base64, platform
from types import ModuleType
import importlib.util, importlib.machinery

# --- THE SIDEBAND/COLUMBA MOCKS ---
class Dummy:
    def __getattr__(self, name): return Dummy()
    def __call__(self, *args, **kwargs): return Dummy()
mock_usb = ModuleType("usbserial4a")
mock_usb.__spec__ = importlib.machinery.ModuleSpec("usbserial4a", None)
mock_usb.serial4a = Dummy()
mock_usb.get_ports_list = lambda: []
sys.modules["usbserial4a"] = mock_usb
mock_jnius = ModuleType("jnius")
mock_jnius.__spec__ = importlib.machinery.ModuleSpec("jnius", None)
mock_jnius.autoclass = lambda x: Dummy()
mock_jnius.cast = lambda x, y: Dummy()
sys.modules["jnius"] = mock_jnius
_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec
# ----------------------------------

import RNS
import LXMF
from LXMF import LXMRouter, LXMessage

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def log(msg):
    print(f"RNS-LOG: {msg}")
    sys.stdout.flush()

def start_rns(storage_path, use_bridge, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    os.environ["TMPDIR"] = os.path.join(str(storage_path), "tmp")
    if not os.path.exists(os.environ["TMPDIR"]): os.makedirs(os.environ["TMPDIR"])
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir): os.makedirs(rns_config_dir)

    bridge_config = ""
    if str(use_bridge).lower() == "true":
        bridge_config = """
  [[Android RNode Bridge]]
    type = RNodeInterface
    interface_enabled = True
    outgoing = True
    tcp_host = 127.0.0.1
    frequency = 433000000
    bandwidth = 125000
    txpower = 2
    spreadingfactor = 7
    codingrate = 5
"""
    full_config = f"[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]\n  [[Auto Interface]]\n    type = AutoInterface\n    interface_enabled = False\n{bridge_config}"
    with open(os.path.join(rns_config_dir, "config"), "w") as f: f.write(full_config)

    RNS.Reticulum(configdir=rns_config_dir)
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path): local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    router = LXMRouter(identity=local_identity, storagepath=os.path.join(str(storage_path), ".lxmf"))
    local_destination = router.register_delivery_identity(local_identity, display_name="rnshello")
    router.register_delivery_callback(on_lxmf_delivery)
    
    # REGISTER ANNOUNCE HANDLER: This lets us discover others!
    RNS.Transport.register_announce_handler(on_announce)
    
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    return addr

def on_announce(aspect_filter, data, announce_identity, announce_destination):
    # When we hear an announce, send the HEX address back to Kotlin
    dest_hash = RNS.hexrep(announce_destination.hash, delimit=False)
    if kotlin_ui_callback:
        kotlin_ui_callback.onAnnounceReceived(dest_hash)

def on_lxmf_delivery(lxm):
    sender = RNS.hexrep(lxm.source_hash, delimit=False)
    content = lxm.content.decode("utf-8")
    
    if content.startswith("IMG:"):
        # We received an image payload
        try:
            img_data = content[4:]
            img_bytes = base64.b64decode(img_data)
            # Use fixed path in app cache
            file_path = os.path.join(os.environ["TMPDIR"], f"rec_{sender}.webp")
            with open(file_path, "wb") as f: f.write(img_bytes)
            if kotlin_ui_callback: kotlin_ui_callback.onImageReceived(sender, file_path)
        except: pass
    else:
        if kotlin_ui_callback: kotlin_ui_callback.onTextReceived(sender, content)

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        destination = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(destination, local_destination, text, title="rnshello")
        router.handle_outbound(lxm)
        return True
    except: return False

def send_image(dest_hex, file_path):
    try:
        with open(file_path, "rb") as f:
            encoded = base64.b64encode(f.read()).decode("utf-8")
        payload = f"IMG:{encoded}"
        return send_text(dest_hex, payload)
    except: return False

def announce_now():
    if local_destination: local_destination.announce()