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
own_address_hex = "" # Store own address in Python

def log(msg):
    print(f"RNS-LOG: {msg}")
    sys.stdout.flush()

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
    global router, local_identity, local_destination, kotlin_ui_callback, own_address_hex
    kotlin_ui_callback = callback_obj
    
    tmp = os.path.join(str(storage_path), "tmp")
    if not os.path.exists(tmp): os.makedirs(tmp)
    os.environ["TMPDIR"] = tmp

    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir): os.makedirs(rns_config_dir)

    full_config = "[reticulum]\nenable_transport = True\nshare_instance = Yes\n\n[interfaces]"
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
    
    discovery_handler = MeshDiscoveryHandler()
    RNS.Transport.register_announce_handler(discovery_handler)
    
    own_address_hex = RNS.hexrep(local_destination.hash, delimit=False)
    # local_destination.announce() # Initial announce is done by Kotlin in this setup
    return own_address_hex

def inject_rnode():
    log("Injecting RNode Parameters...")
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
        time.sleep(1) # Give it time to sync
        local_destination.announce() # Announce after connection
        return "ONLINE"
    except Exception as e:
        log(f"Injection failed: {e}")
        return str(e)

def on_lxmf_delivery(lxm):
    sender_hash = RNS.hexrep(lxm.source_hash, delimit=False)
    content = lxm.content.decode("utf-8")
    current_timestamp = int(time.time() * 1000) # Milliseconds
    
    if content.startswith("IMG:"):
        try:
            base64_data = content[4:]
            img_bytes = base64.b64decode(base64_data)
            file_name = f"rec_{current_timestamp}.webp"
            file_path = os.path.join(os.environ["TMPDIR"], file_name)
            with open(file_path, "wb") as f: f.write(img_bytes)
            
            # Send full message object
            if kotlin_ui_callback:
                kotlin_ui_callback.onNewMessage(sender_hash, file_path, current_timestamp, True, False) # isImage=True, isSent=False
        except Exception as e:
            log(f"Image decode failed: {e}")
    else:
        if kotlin_ui_callback:
            # Send full message object
            kotlin_ui_callback.onNewMessage(sender_hash, content, current_timestamp, False, False) # isImage=False, isSent=False

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
        log("Manual Broadcast Triggered")
        local_destination.announce()
