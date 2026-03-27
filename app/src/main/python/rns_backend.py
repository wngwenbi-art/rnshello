import os
import time
import base64
import RNS
import LXMF

router = None
local_identity = None
local_destination = None
kotlin_ui_callback = None

def start_rns(storage_path, callback_obj):
    global router, local_identity, local_destination, kotlin_ui_callback
    
    kotlin_ui_callback = callback_obj
    
    # 1. Initialize RNS
    rns_storage = os.path.join(storage_path, "reticulum")
    os.makedirs(rns_storage, exist_ok=True)
    RNS.Reticulum(rns_storage)
    
    # 2. Initialize LXMF Router
    router = LXMF.LXMRouter(storage_path=rns_storage)
    local_identity = RNS.Identity()
    local_destination = router.register_delivery_destination(
        local_identity,
        display_name="rnshello"
    )
    
    # 3. Set callback for incoming messages
    local_destination.set_delivery_callback(on_lxmf_delivery)
    
    # Return our local address to Kotlin to display
    return RNS.hexrep(local_destination.hash, delimit=False)

def on_lxmf_delivery(message):
    sender_hash = RNS.hexrep(message.source_hash, delimit=False)
    content = message.content_as_string()
    
    # Check if it's an image payload
    if content.startswith("IMG:"):
        base64_img = content[4:]
        img_bytes = base64.b64decode(base64_img)
        
        # Write to disk IN PYTHON, pass path to Kotlin
        save_path = os.path.join(os.environ["HOME"], "received_img.webp")
        with open(save_path, "wb") as f:
            f.write(img_bytes)
            
        if kotlin_ui_callback:
            kotlin_ui_callback.onImageReceived(sender_hash, save_path)
    else:
        if kotlin_ui_callback:
            kotlin_ui_callback.onTextReceived(sender_hash, content)

def send_text(dest_hex, text):
    dest_hash = bytes.fromhex(dest_hex)
    dest_id = RNS.Identity.recall(dest_hash)
    if dest_id is None:
        return False # Identity not known yet on the mesh
        
    destination = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
    lxm = LXMF.LXMessage(destination, local_destination, text, title="rnshello", desired_method=LXMF.LXMessage.DIRECT)
    router.handle_outbound(lxm)
    return True

def send_image(dest_hex, file_path):
    # READ FROM DISK IN PYTHON (Bypasses JNI size limits!)
    try:
        with open(file_path, 'rb') as f:
            img_bytes = f.read()
            
        encoded = base64.b64encode(img_bytes).decode('utf-8')
        payload = f"IMG:{encoded}"
        
        return send_text(dest_hex, payload)
    except Exception as e:
        print(f"Failed to send image: {e}")
        return False