bl_info = {
    "name": "AI Visual Tutor State Adapter",
    "author": "AIVisualTutor",
    "version": (0, 1, 0),
    "blender": (4, 0, 0),
    "location": "Preferences > Add-ons",
    "description": "Read-only, loopback-only scene snapshots for AIVisualTutor",
    "category": "Interface",
}

import base64
from datetime import datetime, timezone
import select
import socket
import uuid
import os

import bpy


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = int(os.environ.get("AIVT_BLENDER_STATE_PORT", "47629"))
MAX_REQUEST_BYTES = 64
MAX_OBJECTS = 10000
POLL_INTERVAL_SECONDS = 0.1
SESSION_ID = str(uuid.uuid4())
_server = None
_pending_connection = None
_pending_request = bytearray()
_pending_payload = None
_pending_offset = 0


def _encode(value):
    if value is None:
        return ""
    encoded = base64.urlsafe_b64encode(str(value).encode("utf-8")).decode("ascii")
    return encoded.rstrip("=")


def _snapshot():
    scene = bpy.context.scene
    if scene is None:
        raise RuntimeError("There is no active Blender scene.")

    complete = True
    records = []
    try:
        objects = list(scene.objects)
    except Exception as error:
        raise RuntimeError("Could not enumerate the active scene: " + str(error))

    for obj in objects[:MAX_OBJECTS]:
        try:
            pointer = obj.as_pointer()
            session_uid = getattr(obj, "session_uid", None)
            identity = "session:" + str(session_uid) if session_uid is not None else "pointer:" + format(pointer, "x")
            try:
                selected = bool(obj.select_get(view_layer=bpy.context.view_layer))
            except Exception:
                selected = None
                complete = False
            location = ",".join(format(float(value), ".9g") for value in obj.location)
            records.append(
                "\t".join(
                    (
                        "OBJECT",
                        _encode(SESSION_ID + ":" + identity),
                        _encode(obj.name),
                        _encode(obj.type),
                        _encode(scene.name),
                        "unknown" if selected is None else str(selected).lower(),
                        _encode(location),
                    )
                )
            )
        except Exception:
            complete = False

    if len(objects) > MAX_OBJECTS:
        complete = False

    captured_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    scene_pointer = format(scene.as_pointer(), "x")
    header = "\t".join(
        (
            "AIVT_STATE_V1",
            _encode(SESSION_ID),
            captured_at,
            _encode(bpy.app.version_string),
            _encode(SESSION_ID + ":scene:" + scene_pointer),
            _encode(scene.name),
            _encode(getattr(bpy.context, "mode", None)),
            str(complete).lower(),
            str(len(records)),
        )
    )
    return "\n".join((header, *records, "END", ""))


def _response_for(request):
    if request != b"AIVT_STATE_V1\n":
        return ("ERROR\t" + _encode("Unsupported read-only request.") + "\n").encode("ascii")
    try:
        payload = _snapshot()
    except Exception as error:
        payload = "ERROR\t" + _encode(str(error)) + "\n"
    return payload.encode("ascii")


def _close_pending_connection():
    global _pending_connection, _pending_request, _pending_payload, _pending_offset
    if _pending_connection is not None:
        _pending_connection.close()
    _pending_connection = None
    _pending_request = bytearray()
    _pending_payload = None
    _pending_offset = 0


def _poll_server():
    global _server, _pending_connection, _pending_request, _pending_payload, _pending_offset
    if _server is None:
        return None

    if _pending_connection is None:
        try:
            _pending_connection, _ = _server.accept()
            _pending_connection.setblocking(False)
        except BlockingIOError:
            return POLL_INTERVAL_SECONDS
        except OSError:
            return None

    try:
        if _pending_payload is None:
            readable, _, _ = select.select([_pending_connection], [], [], 0)
            if readable:
                request = _pending_connection.recv(MAX_REQUEST_BYTES)
                if not request:
                    _close_pending_connection()
                    return POLL_INTERVAL_SECONDS
                _pending_request.extend(request)
                if len(_pending_request) > MAX_REQUEST_BYTES:
                    _pending_payload = ("ERROR\t" + _encode("Request exceeded the supported limit.") + "\n").encode("ascii")
                elif b"\n" in _pending_request:
                    _pending_payload = _response_for(bytes(_pending_request))

        if _pending_payload is not None:
            _, writable, _ = select.select([], [_pending_connection], [], 0)
            if writable:
                chunk_end = min(_pending_offset + 65536, len(_pending_payload))
                sent = _pending_connection.send(_pending_payload[_pending_offset:chunk_end])
                if sent <= 0:
                    _close_pending_connection()
                else:
                    _pending_offset += sent
                    if _pending_offset >= len(_pending_payload):
                        _close_pending_connection()
    except (BlockingIOError, ConnectionError, OSError):
        _close_pending_connection()
    return POLL_INTERVAL_SECONDS


def register():
    global _server
    if _server is not None:
        return

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((DEFAULT_HOST, DEFAULT_PORT))
    server.listen(4)
    server.setblocking(False)
    _server = server
    bpy.app.timers.register(_poll_server, first_interval=POLL_INTERVAL_SECONDS, persistent=True)


def unregister():
    global _server
    if bpy.app.timers.is_registered(_poll_server):
        bpy.app.timers.unregister(_poll_server)
    _close_pending_connection()
    if _server is not None:
        _server.close()
        _server = None


if __name__ == "__main__":
    register()
