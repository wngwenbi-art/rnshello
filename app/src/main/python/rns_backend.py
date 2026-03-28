import os, sys, time, base64, platform
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
# --------------------------------------------

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

# --- THE DISCOVERY HANDLER CLASS ---
class MeshDiscoveryHandler:
    def __init__(self):
        self.aspect_filter = None # Listen to everything

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            hash_str = RNS.hexrep(destination_hash, delimit=False)
            log(f"MESH DISCOVERY: Found node {hash_str}")
            if kotlin_ui_callback:
                kotlin_ui_callback.onAnnounceReceived(hash_str)
        except Exception as e:
            log(f"Discovery Error: {e}")

def start_rns(storage_path, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    tmp = os.path.join(str(storage_path), "tmp")
    if not os.path.exists(tmp): os.makedirs(tmp)
    os.environ["TMPDIR"] = tmp
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir): os.makedirs(rns_config_dir)

    with open(os.path.join(rns_config_dir, "config"), "w") as f:
        f.write("[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]")

    RNS.Reticulum(configdir=rns_config_dir)
    
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path): local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    router = LXMRouter(identity=local_identity, storagepath=os.path.join(str(storage_path), ".lxmf"))
    local_destination = router.register_delivery_identity(local_identity, display_name="rnshello")
    router.register_delivery_callback(on_lxmf_delivery)
    
    # REGISTER THE DISCOVERY CLASS
    discovery_handler = MeshDiscoveryHandler()
    RNS.Transport.register_announce_handler(discovery_handler)
    
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    log(f"RNS Node Ready: {addr}")
    return addr

def inject_rnode():
    log("Tuning RNode: 433.025 MHz SF8 CR6...")
    try:
        from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
        from RNS.Interfaces.Interface import Interface
        ictx = {
            "name": "Android RNode Bridge",
            "type": "RNodeInterface",
            "interface_enabled": True,
            "outgoing": True,
            "tcp_host": "127.0.0.1",
            "tcp_port": 7633,
            "frequency": 433025000,
            "bandwidth": 125000,
            "txpower": 17,
            "spreadingfactor": 8,
            "codingrate": 6,
            "flow_control": False
        }
        new_ifac = RNodeInterface(RNS.Transport, ictx)
        new_ifac.mode = Interface.MODE_FULL
        new_ifac.IN = True
        new_ifac.OUT = True
        RNS.Transport.interfaces.append(new_ifac)
        
        log("RNode Hardware Synchronized.")
        time.sleep(1)
        # First announce to mesh
        local_destination.announce()
        return "ONLINE"
    except Exception as e:
        return str(e)

def on_lxmf_delivery(lxm):
    sender = RNS.hexrep(lxm.source_hash, delimit=False)
    content = lxm.content.decode("utf-8")
    if content.startswith("IMG:"):
        try:
            file_path = os.path.join(os.environ["TMPDIR"], f"rec_{int(time.time())}.webp")
            with open(file_path, "wb") as f: f.write(base64.b64decode(content[4:]))
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
        with open(file_path, "rb") as f: encoded = base64.b64encode(f.read()).decode("utf-8")
        return send_text(dest_hex, f"IMG:{encoded}")
    except: return False

def announce_now():
    if local_destination: 
        log("Broadcasting Identity to Mesh...")
        local_destination.announce()