import os
import RNS
import LXMF
import time

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def log(msg):
    print(f"RNS-LOG: {msg}")

def start_rns(storage_path, bt_mac, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

    # 1. Start Reticulum
    RNS.Reticulum(configdir=rns_config_dir)
    
    # 2. Identity
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    # 3. Start LXMF Router (The Sideband Core)
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    local_destination = router.register_delivery_destination(local_identity, display_name="rnshello")
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    if bt_mac:
        connect_rnode_bluetooth(bt_mac)

    local_destination.announce()
    return RNS.hexrep(local_destination.hash, delimit=False)

def connect_rnode_bluetooth(mac_address):
    log(f"Attempting Bluetooth connect to {mac_address}")
    try:
        interface_conf = {
            "name": "RNode-BT",
            "type": "BluetoothInterface",
            "device": mac_address,
            "outgoing": True,
            "enabled": True
        }
        RNS.Transport.setup_interface(interface_conf)
        return True
    except Exception as e:
        log(f"BT Error: {e}")
        return False

def on_lxmf_delivery(lxm):
    sender = RNS.hexrep(lxm.source_hash, delimit=False)
    content = lxm.content.decode("utf-8")
    if kotlin_ui_callback:
        kotlin_ui_callback.onTextReceived(sender, content)

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        destination = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMF.LXMessage(destination, local_destination, text)
        router.handle_outbound(lxm)
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False
