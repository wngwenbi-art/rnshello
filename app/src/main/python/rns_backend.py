import os
import RNS
import LXMF

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def start_rns(storage_path, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    kotlin_ui_callback = callback_obj
    
    # Setup config directory
    rns_config_dir = os.path.join(str(storage_path), ".reticulum")
    if not os.path.exists(rns_config_dir):
        os.makedirs(rns_config_dir)

    # Minimal config for AutoInterface
    config_file = os.path.join(rns_config_dir, "config")
    if not os.path.exists(config_file):
        with open(config_file, "w") as f:
            f.write("[reticulum]\n  enable_transport = True\n[interfaces]\n  [[Auto Interface]]\n    type = AutoInterface\n    interface_enabled = True\n")

    # Initialize RNS
    RNS.Reticulum(configdir=rns_config_dir)
    
    # Setup Identity
    identity_path = os.path.join(rns_config_dir, "storage_identity")
    if os.path.exists(identity_path):
        local_identity = RNS.Identity.from_file(identity_path)
    else:
        local_identity = RNS.Identity()
        local_identity.to_file(identity_path)

    # Setup LXMF
    router = LXMF.LXMRouter(storage_path=rns_config_dir)
    local_destination = router.register_delivery_destination(local_identity, display_name="rnshello")
    
    return RNS.hexrep(local_destination.hash, delimit=False)