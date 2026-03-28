import os
import sys
import time
import RNS
import LXMF
from LXMF import LXMRouter, LXMessage, LXMessageDestination

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def log(msg):
    print(f"RNS-LOG: {msg}")
    sys.stdout.flush()

def start_rns(storage_path, bt_mac, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    # Android SQLite Stability
    temp_dir = os.path.join(str(storage_path), "tmp")
    if not os.path.exists(temp_dir): os.makedirs(temp_dir)
    os.environ["TMPDIR"] = temp_dir

    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir): os.makedirs(rns_config_dir)

    # --- THE FIXED INTERFACE CONFIG ---
    config_path = os.path.join(rns_config_dir, "config")
    
    # We build a fresh config file every time to ensure the BT MAC is locked in
    # This is the most reliable way to connect to RNodes on Android
    bt_interface_config = ""
    if bt_mac and len(bt_mac) > 10:
        log(f"Configuring RNode Bluetooth: {bt_mac}")
        bt_interface_config = f"""
  [[RNode Bluetooth Interface]]
    type = BluetoothInterface
    interface_enabled = True
    outgoing = True
    device = {bt_mac}
"""

    full_config = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[Auto Interface]]
    type = AutoInterface
    interface_enabled = True
{bt_interface_config}
"""
    with open(config_path, "w") as f:
        f.write(full_config)
    # ----------------------------------

    log("Starting Reticulum Stack...")
    RNS.Reticulum(configdir=rns_config_dir)
    
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    log("Starting LXMF Router...")
    router = LXMRouter(identity=local_identity, storagepath=os.path.join(str(storage_path), ".lxmf"))
    local_destination = LXMessageDestination(local_identity, router, "rnshello")
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    log(f"Backend Ready. Address: {addr}")
    return addr

def on_lxmf_delivery(lxm):
    try:
        sender = RNS.hexrep(lxm.source_hash, delimit=False)
        content = lxm.content.decode("utf-8")
        log(f"Message from {sender}")
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender, content)
    except: pass

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(dest, local_destination, text)
        router.handle_outbound(lxm)
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False