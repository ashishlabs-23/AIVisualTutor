bl_info = {
    "name": "AI Visual Tutor State Adapter",
    "author": "AIVisualTutor",
    "version": (0, 2, 0),
    "blender": (4, 0, 0),
    "location": "Preferences > Add-ons",
    "description": "Read-only, loopback-only versioned scene snapshots and HTTP adapter for AIVisualTutor",
    "category": "Interface",
}

import base64
from datetime import datetime, timezone
import json
import os
import select
import socket
import uuid

import bpy
import mathutils
from bpy_extras import view3d_utils

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = int(os.environ.get("AIVT_BLENDER_STATE_PORT", "47629"))
AUTH_TOKEN = os.environ.get("AIVT_BLENDER_TOKEN", "").strip()
MAX_REQUEST_BYTES = 2048
MAX_OBJECTS = 10000
POLL_INTERVAL_SECONDS = 0.05
SESSION_ID = str(uuid.uuid4())
SNAPSHOT_VERSION = 0
EVENT_COUNTER = 0

_server = None
_pending_connection = None
_pending_request = bytearray()
_pending_payload = None
_pending_offset = 0


@bpy.app.handlers.persistent
def _on_depsgraph_update(scene, depsgraph):
    global EVENT_COUNTER, SNAPSHOT_VERSION
    EVENT_COUNTER += 1


@bpy.app.handlers.persistent
def _on_load_post(dummy):
    global EVENT_COUNTER, SNAPSHOT_VERSION
    EVENT_COUNTER += 1


_last_transform_state = None


def _check_viewport_transforms():
    global EVENT_COUNTER, _last_transform_state
    try:
        active = getattr(bpy.context, "active_object", None)
        active_state = None
        if active is not None:
            loc = tuple(round(v, 4) for v in active.location)
            rot = tuple(round(v, 4) for v in getattr(active, "rotation_euler", (0.0, 0.0, 0.0)))
            scale = tuple(round(v, 4) for v in active.scale)
            active_state = (active.name, loc, rot, scale)

        vp_state = []
        wm = getattr(bpy.context, "window_manager", None)
        if wm:
            for window in wm.windows:
                for area in window.screen.areas:
                    if area.type == 'VIEW_3D':
                        rv3d = getattr(area.spaces.active, "region_3d", None)
                        if rv3d:
                            vp_state.append((
                                round(getattr(rv3d, "view_distance", 0.0), 2),
                                tuple(round(v, 2) for v in getattr(rv3d, "view_location", (0.0, 0.0, 0.0))),
                                tuple(round(v, 3) for v in getattr(rv3d, "view_rotation", (1.0, 0.0, 0.0, 0.0)))
                            ))

        current = (active_state, tuple(vp_state))
        if _last_transform_state is not None and current != _last_transform_state:
            EVENT_COUNTER += 1
        _last_transform_state = current
    except Exception:
        pass
    return 0.1


def _encode(value):
    if value is None:
        return ""
    encoded = base64.urlsafe_b64encode(str(value).encode("utf-8")).decode("ascii")
    return encoded.rstrip("=")


def _get_object_identity(obj):
    session_uid = getattr(obj, "session_uid", None)
    if session_uid is not None:
        return f"session:{SESSION_ID}:uid:{session_uid}"
    pointer = format(obj.as_pointer(), "x")
    return f"session:{SESSION_ID}:pointer:{pointer}"


def _build_snapshot_dict():
    global SNAPSHOT_VERSION
    scene = bpy.context.scene
    if scene is None:
        raise RuntimeError("There is no active Blender scene.")

    SNAPSHOT_VERSION += 1
    complete = True
    objects_data = []

    try:
        objects = list(scene.objects)
    except Exception as error:
        raise RuntimeError(f"Could not enumerate the active scene: {error}")

    active_obj = getattr(bpy.context, "active_object", None)
    active_obj_dict = None
    if active_obj is not None:
        try:
            active_obj_dict = {
                "identity": _get_object_identity(active_obj),
                "name": active_obj.name,
                "type": active_obj.type,
            }
        except Exception:
            pass

    selected_names = set()
    for obj in objects[:MAX_OBJECTS]:
        try:
            ident = _get_object_identity(obj)
            try:
                selected = bool(obj.select_get(view_layer=bpy.context.view_layer))
                if selected:
                    selected_names.add(obj.name)
            except Exception:
                selected = None
                complete = False

            loc = [float(v) for v in obj.location]
            rot = [float(v) for v in getattr(obj, "rotation_euler", (0.0, 0.0, 0.0))]
            scale = [float(v) for v in getattr(obj, "scale", (1.0, 1.0, 1.0))]
            visible = not getattr(obj, "hide_viewport", False)

            objects_data.append({
                "identity": ident,
                "name": obj.name,
                "type": obj.type,
                "sceneName": scene.name,
                "selected": selected,
                "location": loc,
                "rotation": rot,
                "scale": scale,
                "visible": visible,
                "properties": {
                    "location": ",".join(format(v, ".9g") for v in loc)
                }
            })
        except Exception:
            complete = False

    if len(objects) > MAX_OBJECTS:
        complete = False

    captured_at = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    scene_pointer = format(scene.as_pointer(), "x")
    workspace_name = getattr(getattr(bpy.context, "workspace", None), "name", "Default")

    return {
        "schemaVersion": 1,
        "application": "Blender",
        "applicationVersion": bpy.app.version_string,
        "sessionId": SESSION_ID,
        "snapshotVersion": SNAPSHOT_VERSION,
        "observedAt": captured_at,
        "scene": {
            "identity": f"{SESSION_ID}:scene:{scene_pointer}",
            "name": scene.name
        },
        "activeObject": active_obj_dict,
        "selectedObjects": sorted(list(selected_names)),
        "activeMode": getattr(bpy.context, "mode", "OBJECT"),
        "workspace": workspace_name,
        "objects": objects_data,
        "isComplete": complete,
        "metadata": {
            "objectCount": str(len(objects_data)),
            "transport": "loopback-http-json-v1"
        }
    }


def _build_legacy_snapshot_tsv():
    global SNAPSHOT_VERSION
    SNAPSHOT_VERSION += 1
    scene = bpy.context.scene
    if scene is None:
        raise RuntimeError("There is no active Blender scene.")

    complete = True
    records = []
    try:
        objects = list(scene.objects)
    except Exception as error:
        raise RuntimeError(f"Could not enumerate the active scene: {error}")

    for obj in objects[:MAX_OBJECTS]:
        try:
            ident = _get_object_identity(obj)
            try:
                selected = bool(obj.select_get(view_layer=bpy.context.view_layer))
            except Exception:
                selected = None
                complete = False
            try:
                location = ",".join(format(float(value), ".9g") for value in obj.location)
            except Exception:
                location = "0,0,0"
                complete = False
            try:
                rot_vals = getattr(obj, "rotation_euler", (0.0, 0.0, 0.0))
                rotation = ",".join(format(float(value), ".9g") for value in rot_vals)
            except Exception:
                rotation = "0,0,0"
            try:
                scale_vals = getattr(obj, "scale", (1.0, 1.0, 1.0))
                scale = ",".join(format(float(value), ".9g") for value in scale_vals)
            except Exception:
                scale = "1,1,1"
            try:
                visible = str(not getattr(obj, "hide_viewport", False)).lower()
            except Exception:
                visible = "true"

            records.append(
                "\t".join(
                    (
                        "OBJECT",
                        _encode(ident),
                        _encode(obj.name),
                        _encode(obj.type),
                        _encode(scene.name),
                        "unknown" if selected is None else str(selected).lower(),
                        _encode(location),
                        _encode(rotation),
                        _encode(scale),
                        _encode(visible),
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


def _handle_http_request(request_text):
    lines = request_text.split("\r\n")
    if not lines or not lines[0]:
        lines = request_text.split("\n")
    request_line = lines[0].strip()
    parts = request_line.split(" ")
    if len(parts) < 2:
        return _http_response(400, "Bad Request", {"error": "Malformed HTTP request."})

    method, path = parts[0], parts[1]
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()

    # Optional authentication token check
    if AUTH_TOKEN:
        token_hdr = headers.get("x-session-token", "")
        auth_hdr = headers.get("authorization", "")
        bearer = auth_hdr[7:].strip() if auth_hdr.lower().startswith("bearer ") else ""
        if token_hdr != AUTH_TOKEN and bearer != AUTH_TOKEN:
            return _http_response(401, "Unauthorized", {"error": "Invalid or missing session authentication token."})

    if path == "/api/v1/health":
        return _http_response(200, "OK", {
            "status": "HEALTHY",
            "application": "Blender",
            "applicationVersion": bpy.app.version_string,
            "sessionId": SESSION_ID,
            "snapshotVersion": SNAPSHOT_VERSION,
            "activeScene": getattr(bpy.context.scene, "name", "None")
        })

    elif path == "/api/v1/snapshot" and method in ("GET", "POST"):
        try:
            snapshot = _build_snapshot_dict()
            return _http_response(200, "OK", snapshot)
        except Exception as error:
            return _http_response(500, "Internal Server Error", {"error": str(error)})

    elif path == "/api/v1/version":
        active_obj = getattr(bpy.context, "active_object", None)
        return _http_response(200, "OK", {
            "snapshotVersion": SNAPSHOT_VERSION,
            "eventCounter": EVENT_COUNTER,
            "sessionId": SESSION_ID,
            "activeMode": getattr(bpy.context, "mode", "OBJECT"),
            "activeObject": active_obj.name if active_obj else None,
            "selectedCount": len(getattr(bpy.context, "selected_objects", []))
        })

    elif path in ("/api/v1/close", "/api/v1/disconnect"):
        return _http_response(200, "OK", {"status": "DISCONNECTED", "sessionId": SESSION_ID})

    elif path.startswith("/api/v1/project") and method in ("GET", "POST"):
        target_name = None
        viewport_idx = None
        if "?" in path:
            qs = path.split("?", 1)[1]
            for pair in qs.split("&"):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    if k.lower() == "target":
                        target_name = v.strip()
                    elif k.lower() in ("viewport", "viewport_index", "viewportindex"):
                        viewport_idx = v.strip()
        if not target_name and method == "POST":
            body_start = request_text.find("\r\n\r\n")
            if body_start != -1:
                body_str = request_text[body_start + 4:].strip()
                try:
                    data = json.loads(body_str)
                    target_name = data.get("target") or data.get("name") or data.get("identity")
                    if viewport_idx is None:
                        viewport_idx = data.get("viewport") or data.get("viewport_index")
                except Exception:
                    pass
        status_code, resp_payload = _project_object_dict(target_name, viewport_index=viewport_idx)
        status_msg = "OK" if status_code == 200 else ("Not Found" if status_code == 404 else "Error")
        return _http_response(status_code, status_msg, resp_payload)

    else:
        return _http_response(404, "Not Found", {"error": f"Unknown endpoint '{path}'."})


def _project_object_dict(target, viewport_index=None):
    scene = bpy.context.scene
    if scene is None:
        raise RuntimeError("There is no active Blender scene.")

    target_clean = (target or "").strip()
    if not target_clean:
        return 400, {"status": "INVALID_QUERY", "error": "Missing target object name or identity."}

    obj = None
    if target_clean.lower() in ("selected", "active", "active_object"):
        obj = getattr(bpy.context, "active_object", None)
        if obj is None:
            for candidate in bpy.data.objects:
                try:
                    if candidate.select_get(view_layer=bpy.context.view_layer):
                        obj = candidate
                        break
                except Exception:
                    pass
    elif target_clean in bpy.data.objects:
        obj = bpy.data.objects[target_clean]
    else:
        for candidate in bpy.data.objects:
            if _get_object_identity(candidate) == target_clean or candidate.name.lower() == target_clean.lower():
                obj = candidate
                break

    if obj is None:
        return 404, {"status": "NOT_FOUND", "error": f"Object '{target_clean}' not found in active scene."}

    candidate_viewports = []
    for window in bpy.context.window_manager.windows:
        screen = window.screen
        total_h = window.height if window.height > 0 else max((a.y + a.height for a in screen.areas), default=1080)
        for area in screen.areas:
            if area.type == 'VIEW_3D':
                space = area.spaces.active
                is_quad = bool(getattr(space, "region_quadviews", None))
                for region in area.regions:
                    if region.type == 'WINDOW':
                        rv3d = space.region_3d
                        candidate_viewports.append({
                            "window": window,
                            "screen": screen,
                            "area": area,
                            "region": region,
                            "rv3d": rv3d,
                            "total_h": total_h,
                            "is_quad": is_quad,
                            "bounds": {
                                "x": region.x,
                                "y": region.y,
                                "width": region.width,
                                "height": region.height
                            }
                        })

    if not candidate_viewports:
        return 422, {
            "status": "PROJECTION_UNAVAILABLE",
            "target": obj.name,
            "error": "No active 3D viewport available in Blender window."
        }

    selected_vp = None
    if viewport_index is not None:
        try:
            vp_idx = int(viewport_index)
            if 0 <= vp_idx < len(candidate_viewports):
                selected_vp = candidate_viewports[vp_idx]
            else:
                return 400, {
                    "status": "INVALID_VIEWPORT",
                    "error": f"Specified viewport index {vp_idx} is out of bounds (0..{len(candidate_viewports)-1})."
                }
        except (ValueError, TypeError):
            pass

    if selected_vp is None:
        if len(candidate_viewports) > 1 or candidate_viewports[0]["is_quad"]:
            viewports_summary = [
                {
                    "index": i,
                    "x": vp["bounds"]["x"],
                    "y": vp["bounds"]["y"],
                    "width": vp["bounds"]["width"],
                    "height": vp["bounds"]["height"],
                    "isQuadView": vp["is_quad"]
                }
                for i, vp in enumerate(candidate_viewports)
            ]
            return 422, {
                "status": "AMBIGUOUS_VIEWPORT",
                "target": obj.name,
                "viewportCount": len(candidate_viewports),
                "error": f"Multiple 3D viewports detected ({len(candidate_viewports)}). Target viewport cannot be determined unambiguously.",
                "viewports": viewports_summary
            }
        else:
            selected_vp = candidate_viewports[0]

    region = selected_vp["region"]
    rv3d = selected_vp["rv3d"]
    total_h = selected_vp["total_h"]
    matrix = obj.matrix_world

    corners_2d = []
    points_behind_cam = 0
    for corner in obj.bound_box:
        world_pt = matrix @ mathutils.Vector(corner)
        try:
            pt = view3d_utils.location_3d_to_region_2d(region, rv3d, world_pt)
            if pt is not None:
                corners_2d.append(pt)
            else:
                points_behind_cam += 1
        except Exception:
            points_behind_cam += 1

    if not corners_2d:
        return 422, {
            "status": "OUTSIDE_VIEWPORT",
            "reason": "BEHIND_CAMERA",
            "target": obj.name,
            "error": f"Object '{obj.name}' is completely behind the viewport camera."
        }

    min_x = min(p.x for p in corners_2d)
    max_x = max(p.x for p in corners_2d)
    min_y = min(p.y for p in corners_2d)
    max_y = max(p.y for p in corners_2d)

    if max_x < 0 or min_x > region.width or max_y < 0 or min_y > region.height:
        return 422, {
            "status": "OUTSIDE_VIEWPORT",
            "reason": "OUTSIDE_FRUSTUM",
            "target": obj.name,
            "error": f"Object '{obj.name}' is completely outside the 3D viewport canvas."
        }

    is_partially_clipped = (
        points_behind_cam > 0 or
        min_x < 0 or max_x > region.width or
        min_y < 0 or max_y > region.height
    )

    clamped_min_x = max(0.0, min_x)
    clamped_max_x = min(float(region.width), max_x)
    clamped_min_y = max(0.0, min_y)
    clamped_max_y = min(float(region.height), max_y)

    x = region.x + clamped_min_x
    y = total_h - (region.y + clamped_max_y)
    w = max(1.0, clamped_max_x - clamped_min_x)
    h = max(1.0, clamped_max_y - clamped_min_y)

    visibility = "PARTIALLY_CLIPPED" if is_partially_clipped else "VISIBLE"
    occluded_by = None
    try:
        depsgraph = bpy.context.evaluated_depsgraph_get()
        center_3d = matrix @ (0.125 * sum((mathutils.Vector(c) for c in obj.bound_box), mathutils.Vector()))
        if getattr(rv3d, "is_perspective", True):
            cam_pos = rv3d.view_matrix.inverted().translation
            ray_dir = (center_3d - cam_pos).normalized()
            dist_to_center = (center_3d - cam_pos).length
        else:
            view_inv = rv3d.view_matrix.inverted().to_3x3()
            ray_dir = (view_inv @ mathutils.Vector((0, 0, -1))).normalized()
            cam_pos = center_3d - ray_dir * 1000.0
            dist_to_center = 1000.0

        hit, hit_loc, hit_norm, hit_idx, hit_obj, _ = scene.ray_cast(depsgraph, cam_pos, ray_dir)
        if hit and hit_obj is not None and hit_obj != obj:
            dist_to_hit = (hit_loc - cam_pos).length
            if dist_to_hit < dist_to_center - 0.05:
                visibility = "POTENTIALLY_OCCLUDED"
                occluded_by = hit_obj.name
    except Exception:
        pass

    return 200, {
        "status": "PROJECTED",
        "target": obj.name,
        "identity": _get_object_identity(obj),
        "scene": scene.name,
        "visible": not getattr(obj, "hide_viewport", False),
        "selected": bool(obj.select_get(view_layer=bpy.context.view_layer)),
        "visibility": visibility,
        "occludedBy": occluded_by,
        "isPartiallyClipped": is_partially_clipped,
        "bounds": {
            "x": round(x, 2),
            "y": round(y, 2),
            "width": round(w, 2),
            "height": round(h, 2)
        },
        "center": {
            "x": round(x + w / 2, 2),
            "y": round(y + h / 2, 2)
        }
    }


def _http_response(status_code, reason, body_dict):
    body_bytes = json.dumps(body_dict, indent=2).encode("utf-8")
    header = (
        f"HTTP/1.1 {status_code} {reason}\r\n"
        f"Content-Type: application/json; charset=utf-8\r\n"
        f"Content-Length: {len(body_bytes)}\r\n"
        f"Connection: close\r\n"
        f"\r\n"
    ).encode("ascii")
    return header + body_bytes


def _response_for(request_bytes):
    # Check if request is HTTP
    text = request_bytes.decode("utf-8", errors="replace")
    if text.startswith("GET ") or text.startswith("POST ") or text.startswith("HEAD "):
        return _handle_http_request(text)

    # Legacy AIVT_STATE_V1 protocol
    if request_bytes == b"AIVT_STATE_V1\n" or request_bytes.startswith(b"AIVT_STATE_V1"):
        try:
            payload = _build_legacy_snapshot_tsv()
        except Exception as error:
            payload = "ERROR\t" + _encode(str(error)) + "\n"
        return payload.encode("ascii")

    return ("ERROR\t" + _encode("Unsupported request protocol.") + "\n").encode("ascii")


def _close_pending_connection():
    global _pending_connection, _pending_request, _pending_payload, _pending_offset
    if _pending_connection is not None:
        try:
            _pending_connection.shutdown(socket.SHUT_WR)
        except OSError:
            pass
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
                    _pending_payload = _http_response(413, "Payload Too Large", {"error": "Request exceeded size limit."})
                elif b"\r\n\r\n" in _pending_request or b"\n\n" in _pending_request or _pending_request.endswith(b"\n"):
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
    if hasattr(socket, "SO_REUSEADDR"):
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server.bind((DEFAULT_HOST, DEFAULT_PORT))
        server.listen(4)
        server.setblocking(False)
        _server = server
        bpy.app.timers.register(_poll_server, first_interval=POLL_INTERVAL_SECONDS, persistent=True)
        bpy.app.timers.register(_check_viewport_transforms, first_interval=0.1, persistent=True)
        if _on_depsgraph_update not in bpy.app.handlers.depsgraph_update_post:
            bpy.app.handlers.depsgraph_update_post.append(_on_depsgraph_update)
        if _on_load_post not in bpy.app.handlers.load_post:
            bpy.app.handlers.load_post.append(_on_load_post)
        print(f"[AIVT] AI Visual Tutor Adapter v0.2.0 listening on http://{DEFAULT_HOST}:{DEFAULT_PORT}")
    except OSError as error:
        server.close()
        print(f"[AIVT] Adapter could not bind to {DEFAULT_HOST}:{DEFAULT_PORT}: {error}")


def unregister():
    global _server
    if bpy.app.timers.is_registered(_poll_server):
        bpy.app.timers.unregister(_poll_server)
    if bpy.app.timers.is_registered(_check_viewport_transforms):
        bpy.app.timers.unregister(_check_viewport_transforms)
    if _on_depsgraph_update in bpy.app.handlers.depsgraph_update_post:
        bpy.app.handlers.depsgraph_update_post.remove(_on_depsgraph_update)
    if _on_load_post in bpy.app.handlers.load_post:
        bpy.app.handlers.load_post.remove(_on_load_post)
    _close_pending_connection()
    if _server is not None:
        _server.close()
        _server = None


if __name__ == "__main__":
    register()
