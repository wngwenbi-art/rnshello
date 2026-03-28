import os
import RNS
import LXMF
import time
import threading

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def log(msg):
    # This sends logs to Android Logcat via Python print
    print(f"RNS-LOG: {msg}")

def start_rns(storage_path, bt_mac, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    log("Initializing Reticulum...")
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

    # 1. Initialize Reticulum with basic config
    # We allow Reticulum to create its own default config if none exists
    rns = RNS.Reticulum(configdir=rns_config_dir)
    log("Reticulum Stack Started.")

    # 2. Setup Identity (Section: 'Using Identities')
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
        log("Identity loaded from file.")
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)
        log("New identity created.")

    # 3. Start LXMF Router (Like Sideband)
    # The Router is the core engine for LXMF message propagation
    log("Starting LXMF Router...")
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    
    local_destination = router.register_delivery_destination(
        local_identity, 
        display_name="rnshello"
    )
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # 4. If a BT MAC was provided, try to inject it now
    if bt_mac:
        connect_rnode_bluetooth(bt_mac)

    log("LXMF Router is active.")
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    
    # Send a mesh announcement to wake up RNodes
    local_destination.announce()
    log(f"Announced address: {addr}")
    
    return addr

def connect_rnode_bluetooth(mac_address):
    log(f"Attempting to inject Bluetooth Interface for {mac_address}")
    try:
        # We manually setup the interface in the Transport layer
        interface_conf = {
            "name": "RNode Bluetooth",
            "type": "BluetoothInterface",
            "device": mac_address,
            "outgoing": True,
            "enabled": True
        }
        RNS.Transport.setup_interface(interface_conf)
        log("Bluetooth Interface configuration injected.")
        return True
    except Exception as e:
        log(f"Bluetooth Error: {e}")
        return False

def on_lxmf_delivery(lxm):
    # Handle incoming LXMF messages
    try:
        sender = RNS.hexrep(lxm.source_hash, delimit=False)
        content = lxm.content.decode("utf-8")
        log(f"Received LXMF from {sender}")
        
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender, content)
    except Exception as e:
        log(f"Delivery Error: {e}")

def send_text(dest_hex, text):
    try:
        log(f"Preparing to send to {dest_hex}")
        dest_hash = bytes.fromhex(dest_hex)
        dest_id = RNS.Identity.recall(dest_hash)
        
        if not dest_id:
            log("Identity not found in mesh yet, attempting to discover...")
            # We skip sending if not discovered, but in mesh it may fail first time
        
        destination = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMF.LXMessage(destination, local_destination, text, title="rnshello")
        router.handle_outbound(lxm)
        log("Message handed to LXMF Router.")
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False
