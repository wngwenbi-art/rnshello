import os
import sys
import time
from types import ModuleType
import importlib.util
import importlib.machinery

# --- THE ULTIMATE MOCK 3.0 (Columba/Sideband Edition) ---
# We mock the Kivy-specific libraries so Reticulum's Android RNode driver 
# starts up, but we use the TCP host to bypass physical USB checks.

class Dummy:
    def __getattr__(self, name): return Dummy()
    def __call__(self, *args, **kwargs): return Dummy()

# Create fake usbserial4a
mock_usb = ModuleType("usbserial4a")
mock_usb.__spec__ = importlib.machinery.ModuleSpec("usbserial4a", None)
mock_usb.serial4a = Dummy()
mock_usb.get_ports_list = lambda: []
sys.modules["usbserial4a"] = mock_usb

# Create fake jnius
mock_jnius = ModuleType("jnius")
mock_jnius.__spec__ = importlib.machinery.ModuleSpec("jnius", None)
mock_jnius.autoclass = lambda x: Dummy()
mock_jnius.cast = lambda x, y: Dummy()
sys.modules["jnius"] = mock_jnius

# Patch importlib so find_spec returns our mocks
_orig_find_spec = importlib.util.find_spec
def _mock_find_spec(name, package=None):
    if name in ["usbserial4a", "jnius"]: return sys.modules[name].__spec__
    return _orig_find_spec(name, package)
importlib.util.find_spec = _mock_find_spec
# -------------------------------------------------------

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

    # Building the interface config using Sideband's tcp_host parameter
    bridge_config = ""
    if str(use_bridge).lower() == "true":
        log("Connecting to Native Kotlin Bridge...")
        bridge_config = """
  [[Android RNode Bridge]]
    type = RNodeInterface
    interface_enabled = True
    outgoing = True
    # Port 7633 is the hardcoded default for RNode TCP on Android
    tcp_host = 127.0.0.1
    frequency = 433000000
    bandwidth = 125000
    txpower = 2
    spreadingfactor = 7
    codingrate = 5
"""

    full_config = f"""[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[Auto Interface]]
    type = AutoInterface
    interface_enabled = False
{bridge_config}
"""
    with open(os.path.join(rns_config_dir, "config"), "w") as f:
        f.write(full_config)

    log("Starting Reticulum...")
    RNS.Reticulum(configdir=rns_config_dir)
    
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    log("Starting LXMF...")
    router = LXMRouter(identity=local_identity, storagepath=os.path.join(str(storage_path), ".lxmf"))
    local_destination = router.register_delivery_identity(local_identity, display_name="rnshello")
    router.register_delivery_callback(on_lxmf_delivery)
    
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    log(f"RNS Online: {addr}")
    return addr

def on_lxmf_delivery(lxm):
    try:
        sender = RNS.hexrep(lxm.source_hash, delimit=False)
        content = lxm.content.decode("utf-8")
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender, content)
    except: pass

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(dest, local_destination, text, title="rnshello")
        router.handle_outbound(lxm)
        return True
    except: return False