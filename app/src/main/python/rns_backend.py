import os
import sys
import RNS
import LXMF
import time

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
    
    # --- CRITICAL ANDROID FIX: Set TMPDIR for SQLite ---
    temp_dir = os.path.join(str(storage_path), "tmp")
    if not os.path.exists(temp_dir):
        os.makedirs(temp_dir)
    os.environ["TMPDIR"] = temp_dir
    # ---------------------------------------------------

    log("Initializing Reticulum...")
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    lxmf_storage_dir = os.path.join(str(storage_path), ".lxmf")
    
    for d in [rns_config_dir, lxmf_storage_dir]:
        if not os.path.exists(d): os.makedirs(d)

    # 1. Start RNS
    RNS.Reticulum(configdir=rns_config_dir)
    log("RNS Stack is online.")

    # 2. Identity Generation
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
        log("Identity loaded.")
    else:
        log("Generating NEW Identity...")
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)
        log("Identity generated.")

    # 3. Initialize LXMF Router (The Sideband way)
    # We add a small 1s delay to ensure RNS background threads are ready
    time.sleep(1)
    log("Initializing LXMF Router (with isolation)...")
    
    try:
        # We pass the identity directly to the constructor for stability
        router = LXMF.LXMRouter(
            identity=local_identity,
            storage_path=lxmf_storage_dir
        )
        log("LXMF Router instance created.")
        
        local_destination = router.register_delivery_destination(
            local_identity, 
            display_name="rnshello"
        )
        local_destination.set_delivery_callback(on_lxmf_delivery)
        log("Destination registered.")
        
    except Exception as e:
        log(f"CRITICAL ERROR during LXMF Init: {e}")
        return "ERROR"
    
    if bt_mac and len(bt_mac) > 10:
        connect_rnode_bluetooth(bt_mac)

    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    log(f"Backend fully ready. Address: {addr}")
    
    return addr

def connect_rnode_bluetooth(mac_address):
    log(f"Connecting to RNode BT MAC: {mac_address}")
    try:
        interface_conf = {
            "name": "RNode-BT",
            "type": "BluetoothInterface",
            "device": mac_address,
            "outgoing": True,
            "enabled": True
        }
        RNS.Transport.setup_interface(interface_conf)
        log("Bluetooth Interface injected.")
        return True
    except Exception as e:
        log(f"BT Error: {e}")
        return False

def on_lxmf_delivery(lxm):
    try:
        sender = RNS.hexrep(lxm.source_hash, delimit=False)
        content = lxm.content.decode("utf-8")
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender, content)
    except Exception as e:
        log(f"Delivery Error: {e}")

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMF.LXMessage(dest, local_destination, text, title="rnshello")
        router.handle_outbound(lxm)
        log(f"Message sent to {dest_hex}")
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False