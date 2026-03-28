import os
import RNS
import LXMF
import time

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def start_rns(storage_path, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

try:
        RNS.Reticulum(configdir=rns_config_dir)
    except Exception as e:
        print(f"RNS Init Warning: {e}")


    # 1. Start Reticulum
    RNS.Reticulum(configdir=rns_config_dir)
    
    # 2. Setup Identity
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    # 3. START LXMF ROUTER (Crucial for Sideband-like behavior)
    # This handles message storage, retries, and announcements
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    local_identity = RNS.Identity() # Or load from file
    local_destination = router.register_delivery_destination(local_identity, display_name="rnshello")
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # 4. Register our local destination
    local_destination = router.register_delivery_destination(
        local_identity, 
        display_name="rnshello"
    )
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # Send an announcement so the mesh knows we are online
    local_destination.announce()
    
    return RNS.hexrep(local_destination.hash, delimit=False)

def connect_rnode_bluetooth(mac_address):
    """
    Tells Reticulum to open an RFCOMM socket to the paired RNode.
    mac_address format: 'AA:BB:CC:DD:EE:FF'
    """
    try:
        # Sideband style dynamic interface injection
        interface_conf = {
            "name": f"RNode-BT-{mac_address}",
            "type": "BluetoothInterface",
            "mac_address": mac_address,
            "enabled": True
        }
        RNS.Transport.setup_interface(interface_conf)
        # Give it a second to initialize the socket
        return True
    except Exception as e:
        print(f"BT Connection Error: {e}")
        return False

def on_lxmf_delivery(lxm):
    # LXMF sends an LXMessage object
    sender_hash = RNS.hexrep(lxm.source_hash, delimit=False)
    content = lxm.content.decode("utf-8")
    
    if content.startswith("IMG:"):
        # Image handling logic (as established in Step 5)
        # We decode the base64 and save it to a file
        pass
    else:
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender_hash, content)

def send_text(dest_hex, text_content):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        # Try to find identity in local cache
        dest_id = RNS.Identity.recall(dest_hash)
        
        # Create an LXMF Destination
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        
        # Create LXMessage
        lxm = LXMF.LXMessage(dest, local_destination, text_content, title="rnshello")
        
        # The Router handles the actual mesh transport
        router.handle_outbound(lxm)
        return True
    except Exception as e:
        print(f"LXMF Send Error: {e}")
        return False