import os, sys, time, base64
import RNS, LXMF
from LXMF import LXMRouter, LXMessage
from RNS.Interfaces.Android.RNodeInterface import RNodeInterface
from RNS.Interfaces.Interface import Interface

# (Mocks Omitted for brevity, but they are preserved in your local file)

router = None; local_destination = None; kotlin_callback = None

def start_rns(storage_path, callback_obj, nickname):
    global router, local_destination, kotlin_callback
    kotlin_callback = callback_obj
    rns_dir = os.path.join(str(storage_path), ".reticulum")
    lxmf_dir = os.path.join(str(storage_path), ".lxmf")
    for d in [rns_dir, lxmf_dir]:
        if not os.path.exists(d): os.makedirs(d)
    
    RNS.Reticulum(configdir=rns_dir)
    id_path = os.path.join(rns_dir, "storage_identity")
    local_id = RNS.Identity.from_file(id_path) if os.path.exists(id_path) else RNS.Identity()
    if not os.path.exists(id_path): local_id.to_file(id_path)

    router = LXMRouter(identity=local_id, storagepath=lxmf_dir)
    # SET NICKNAME: Others will see this in their announce listener
    local_destination = router.register_delivery_identity(local_id, display_name=nickname)
    router.register_delivery_callback(on_lxmf)
    
    # BROADCAST AUTOMATICALLY ON BOOT
    RNS.Transport.register_announce_handler(discovery_handler())
    local_destination.announce() 
    
    return RNS.hexrep(local_destination.hash, False)

class discovery_handler:
    def __init__(self): self.aspect_filter = None
    def received_announce(self, dest_hash, id, data):
        hash_str = RNS.hexrep(dest_hash, False)
        # Extract nickname from the announcement app_data if it exists
        remote_nick = data.decode("utf-8") if data else ""
        if kotlin_callback: 
            kotlin_callback.onAnnounceReceived(hash_str, remote_nick)

def announce_now():
    # Include nickname in the data field for auto-discovery
    nick = local_destination.display_name if local_destination.display_name else ""
    local_destination.announce(app_data=nick.encode("utf-8"))

def on_lxmf(lxm):
    sender = RNS.hexrep(lxm.source_hash, False)
    content = lxm.content.decode("utf-8")
    if content.startswith("ACK:"):
        if kotlin_callback: kotlin_callback.onMessageDelivered(content[4:])
        return
    is_img = content.startswith("IMG:")
    data = content[4:] if is_img else content
    if is_img:
        path = os.path.join(os.environ["TMPDIR"], f"rec_{int(time.time())}.webp")
        with open(path, "wb") as f: f.write(base64.b64decode(data))
        data = path
    if kotlin_callback: kotlin_callback.onNewMessage(sender, data, int(time.time()*1000), is_img, False, RNS.hexrep(lxm.hash, False))

def inject_rnode(freq, bw, tx, sf, cr):
    try:
        ictx = {"name": "Bridge", "type": "RNodeInterface", "interface_enabled": True, "outgoing": True,
                "tcp_host": "127.0.0.1", "tcp_port": 7633, "frequency": int(freq), "bandwidth": int(bw),
                "txpower": int(tx), "spreadingfactor": int(sf), "codingrate": int(cr), "flow_control": False}
        ifac = RNodeInterface(RNS.Transport, ictx)
        ifac.mode = Interface.MODE_FULL
        ifac.IN = True; ifac.OUT = True
        RNS.Transport.interfaces.append(ifac)
        time.sleep(1)
        announce_now()
        return "ONLINE"
    except Exception as e: return str(e)

def send_text(dest_hex, text):
    try:
        dest_id = RNS.Identity.recall(bytes.fromhex(dest_hex))
        dest = RNS.Destination(dest_id, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        lxm = LXMessage(dest, local_destination, text)
        router.handle_outbound(lxm)
        return RNS.hexrep(lxm.hash, False)
    except: return ""

def send_image(dest_hex, path):
    with open(path, "rb") as f: data = base64.b64encode(f.read()).decode("utf-8")
    return send_text(dest_hex, f"IMG:{data}")