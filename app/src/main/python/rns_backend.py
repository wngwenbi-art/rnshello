import os
import sys
import time
import RNS
import LXMF

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

    config_path = os.path.join(rns_config_dir, "config")
    
    bridge_config = ""
    if use_bridge == "true":
        log("Configuring TCP-to-Bluetooth Bridge Interface...")
        bridge_config = """
  [[Android TCP Bridge]]
    type = RNodeInterface
    interface_enabled = True
    outgoing = True
    port = socket://127.0.0.1:4321
"""

    # FIXED THE SPACING HERE! Added double newlines so [interfaces] is on its own block.
    full_config = f"""[reticulum]
enable_transport = True
share_instance = Yes

[interfaces]
  [[Auto Interface]]
    type = AutoInterface
    interface_enabled = True
{bridge_config}
"""
    with open(config_path, "w") as f:
        f.write(full_config)

    log("Starting Reticulum Stack...")
    RNS.Reticulum(configdir=rns_config_dir)
    
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    log("Starting LXMF Router...")
    router = LXMF.LXMRouter(identity=local_identity, storagepath=os.path.join(str(storage_path), ".lxmf"))
    local_destination = router.register_delivery_identity(local_identity, display_name="rnshello")
    router.register_delivery_callback(on_lxmf_delivery)
    
    addr = RNS.hexrep(local_destination.hash, delimit=False)
    local_destination.announce()
    log(f"Backend Ready. Address: {addr}")
    return addr

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
        return True
    except Exception as e:
        log(f"Send Error: {e}")
        return False