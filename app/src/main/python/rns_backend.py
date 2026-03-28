import os
import sys
import RNS
import LXMF
import threading
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
    
    log("Initializing Reticulum...")
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

    # Start Reticulum
    RNS.Reticulum(configdir=rns_config_dir)
    log("RNS Configured.")

    # Identity Management
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    # Initialize LXMF Router (The Sideband way)
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    
    local_destination = router.register_delivery_destination(
        local_identity, 
        display_name="rnshello"
    )
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # Process BT Mac if passed at startup
    if bt_mac and len(bt_mac) > 10:
        connect_rnode_bluetooth(bt_mac)

    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    log(f"Backend Ready. Address: {addr}")
    return addr

def connect_rnode_bluetooth(mac_address):
    log(f"Connecting to RNode via BT: {mac_address}")
    try:
        # Dynamic Interface Injection
        interface_conf = {
            "name": "RNode-Bluetooth",
            "type": "BluetoothInterface",
            "device": mac_address,
            "outgoing": True,
            "enabled": True
        }
        RNS.Transport.setup_interface(interface_conf)
        log("Bluetooth Interface requested.")
        return True
    except Exception as e:
        log(f"BT Connect Error: {e}")
        return False

def on_lxmf_delivery(lxm):
    try:
        sender = RNS.hexrep(lxm.source_hash, delimit=False)
        content = lxm.content.decode("utf-8")
        log(f"Received message from {sender}")
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender, content)
    except Exception as e:
        log(f"Delivery Error: {e}")

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        # Create LXMF Destination and Message
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMF.LXMessage(dest, local_destination, text, title="rnshello")
        router.handle_outbound(lxm)
        log(f"Outbound message queued for {dest_hex}")
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False