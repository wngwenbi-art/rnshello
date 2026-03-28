import os
import RNS
import LXMF
import time

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def start_rns(storage_path, bt_mac, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

    # DYNAMIC CONFIG: We add the BluetoothInterface here
    config_file = os.path.join(rns_config_dir, "config")
    
    # We rebuild the config to ensure the BT MAC is correct
    config_content = f"""
[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[Auto Interface]]
    type = AutoInterface
    interface_enabled = True

  [[RNode Bluetooth Interface]]
    type = BluetoothInterface
    interface_enabled = True
    outgoing = True
    # If bt_mac is empty, it will try to find any paired RNode
    device = {bt_mac if bt_mac else "none"}
"""
    with open(config_file, "w") as f:
        f.write(config_content)

    # Initialize Reticulum
    RNS.Reticulum(configdir=rns_config_dir)
    
    # Identity Management
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    # START LXMF ROUTER (Like Sideband does)
    # This acts as the postman for all LXM messages
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    
    local_destination = router.register_delivery_destination(
        local_identity, 
        display_name="rnshello"
    )
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # Return our address to Kotlin
    return RNS.hexrep(local_destination.hash, delimit=False)

def on_lxmf_delivery(message):
    # This triggers when an LXM message arrives via the Mesh
    sender_hash = RNS.hexrep(message.source_hash, delimit=False)
    content = message.content.decode("utf-8")
    
    if content.startswith("IMG:"):
        # Image processing logic as established before
        pass
    else:
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender_hash, content)

def send_text(dest_hex, text):
    try:
        dest_hash = bytes.fromhex(dest_hex)
        # Recall identity from mesh
        dest_id = RNS.Identity.recall(dest_hash)
        
        # Create Destination object
        destination = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        
        # Wrap in LXMF Message
        lxm = LXMF.LXMessage(destination, local_destination, text, title="rnshello")
        
        # Hand over to the Router to actually send it across the RNode/Mesh
        router.handle_outbound(lxm)
        return True
    except Exception as e:
        print(f"Send failed: {e}")
        return False