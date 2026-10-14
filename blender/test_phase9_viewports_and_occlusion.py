"""
Automated unit tests for Phase 4 (Multi-viewport reliability) and Phase 5 (Occlusion and off-screen classification)
Executed directly against Blender 5.2.2 LTS Python runtime.
"""
import sys
import os
import unittest
import bpy
import mathutils

# Add parent directory of ai_visual_tutor_state to sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ai_visual_tutor_state import _project_object_dict


class Phase9ViewportAndOcclusionTest(unittest.TestCase):

    def setUp(self):
        # Reset to default factory startup state with Cube, Camera, Light
        bpy.ops.wm.read_homefile(use_empty=False)

    def test_01_target_not_found(self):
        status, resp = _project_object_dict("NonExistentObject_XYZ")
        self.assertEqual(status, 404)
        self.assertEqual(resp.get("status"), "NOT_FOUND")
        self.assertIn("not found", resp.get("error", "").lower())
        print("  [PASSED] Target Not Found -> 404 NOT_FOUND")

    def test_02_single_viewport_projection(self):
        # In default homefile there is 1 VIEW_3D area
        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 200)
        self.assertEqual(resp.get("status"), "PROJECTED")
        self.assertEqual(resp.get("target"), "Cube")
        self.assertIn("bounds", resp)
        bounds = resp["bounds"]
        self.assertGreater(bounds["width"], 20.0)
        self.assertGreater(bounds["height"], 20.0)
        self.assertEqual(resp.get("visibility"), "VISIBLE")
        print(f"  [PASSED] Single Viewport Projection -> 200 PROJECTED ({bounds})")

    def test_03_missing_viewport_closure(self):
        # Change all VIEW_3D areas to TEXT_EDITOR to simulate missing/closed viewport
        for window in bpy.context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D':
                    area.type = 'TEXT_EDITOR'

        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 422)
        self.assertEqual(resp.get("status"), "PROJECTION_UNAVAILABLE")
        print("  [PASSED] Missing/Closed Viewport -> 422 PROJECTION_UNAVAILABLE")

    def test_04_target_outside_viewport_frustum(self):
        # Move Cube far outside viewport frustum
        cube = bpy.data.objects.get("Cube")
        self.assertIsNotNone(cube)
        cube.location = (5000.0, 5000.0, 5000.0)
        bpy.context.view_layer.update()

        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 422)
        self.assertEqual(resp.get("status"), "OUTSIDE_VIEWPORT")
        print(f"  [PASSED] Target Outside Viewport -> 422 OUTSIDE_VIEWPORT ({resp.get('reason')})")

    def test_05_target_behind_camera(self):
        # Move Cube behind camera
        cube = bpy.data.objects.get("Cube")
        self.assertIsNotNone(cube)
        # In default view, camera/view looks towards (0,0,0) from +X, -Y, +Z
        # Moving Cube to (100, -100, 100) places it behind the standard view
        for window in bpy.context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D':
                    rv3d = area.spaces.active.region_3d
                    # Set view to look along +Y from (0, -10, 0)
                    rv3d.view_location = (0, 0, 0)
                    rv3d.view_distance = 10.0
                    rv3d.view_rotation = (1, 0, 0, 0)

        cube.location = (0, -20.0, 0) # behind camera plane
        bpy.context.view_layer.update()

        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 422)
        self.assertEqual(resp.get("status"), "OUTSIDE_VIEWPORT")
        print("  [PASSED] Target Behind Camera -> 422 OUTSIDE_VIEWPORT")

    def test_06_target_occluded_by_geometry(self):
        # Place a large occluding Sphere directly between the view and the Cube
        cube = bpy.data.objects.get("Cube")
        self.assertIsNotNone(cube)
        cube.location = (0, 0, 0)

        # Find 3D viewport view origin
        for window in bpy.context.window_manager.windows:
            for area in window.screen.areas:
                if area.type == 'VIEW_3D':
                    rv3d = area.spaces.active.region_3d
                    cam_pos = rv3d.view_matrix.inverted().translation
                    # Place a sphere right in front of the Cube towards cam_pos
                    dir_to_cam = (cam_pos - cube.location).normalized()
                    occluder_loc = cube.location + dir_to_cam * 2.0
                    bpy.ops.mesh.primitive_uv_sphere_add(radius=2.5, location=occluder_loc)
                    occluder = bpy.context.active_object
                    occluder.name = "BigShield"
                    break

        bpy.context.view_layer.update()

        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 200)
        self.assertEqual(resp.get("status"), "PROJECTED")
        self.assertEqual(resp.get("visibility"), "POTENTIALLY_OCCLUDED")
        self.assertEqual(resp.get("occludedBy"), "BigShield")
        print(f"  [PASSED] Occlusion Detection -> 200 PROJECTED [POTENTIALLY_OCCLUDED by {resp.get('occludedBy')}]")

    def test_07_target_partially_clipped(self):
        cube = bpy.data.objects.get("Cube")
        self.assertIsNotNone(cube)
        cube.location = (4.0, 0, 0)
        bpy.context.view_layer.update()

        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 200)
        self.assertEqual(resp.get("status"), "PROJECTED")
        self.assertIn("bounds", resp)
        print(f"  [PASSED] Partially Clipped Bounds -> 200 PROJECTED ({resp.get('visibility')})")

    def test_08_multiple_split_viewports_ambiguity(self):
        # Configure another area to be VIEW_3D so that multiple 3D viewports exist
        orig_3d_idx = -1
        for i, area in enumerate(bpy.context.screen.areas):
            if area.type == 'VIEW_3D':
                orig_3d_idx = i
                break

        # Convert DOPESHEET_EDITOR to VIEW_3D
        for area in bpy.context.screen.areas:
            if area.type == 'DOPESHEET_EDITOR':
                area.type = 'VIEW_3D'
                # Initialize its view to match the original
                area.spaces.active.region_3d.view_distance = 15.0
                area.spaces.active.region_3d.view_rotation = (0.5, 0.5, 0.5, 0.5)
                break

        # Calling without viewport index must return AMBIGUOUS_VIEWPORT
        status, resp = _project_object_dict("Cube")
        self.assertEqual(status, 422)
        self.assertEqual(resp.get("status"), "AMBIGUOUS_VIEWPORT")
        self.assertGreaterEqual(resp.get("viewportCount", 0), 2)
        self.assertIn("viewports", resp)
        print(f"  [PASSED] Multiple Split Viewports Ambiguity -> 422 AMBIGUOUS_VIEWPORT (count={resp.get('viewportCount')})")

        # Calling with explicit valid viewport index for the active viewport
        # Find index in candidate_viewports
        status_idx, resp_idx = _project_object_dict("Cube", viewport_index=0)
        self.assertIn(status_idx, (200, 422)) # either 200 (if Cube in view) or 422 (if outside view)
        # Find which viewport has status 200
        found_projected = False
        for v in range(resp.get("viewportCount", 0)):
            s, r = _project_object_dict("Cube", viewport_index=v)
            if s == 200:
                found_projected = True
                print(f"  [PASSED] Explicit Viewport Index {v} -> 200 PROJECTED ({r['bounds']})")
                break
        self.assertTrue(found_projected, "At least one viewport must project the Cube successfully")

        # Calling with invalid index must return 400 INVALID_VIEWPORT
        status_inv, resp_inv = _project_object_dict("Cube", viewport_index=99)
        self.assertEqual(status_inv, 400)
        self.assertEqual(resp_inv.get("status"), "INVALID_VIEWPORT")
        print("  [PASSED] Invalid Viewport Index 99 -> 400 INVALID_VIEWPORT")


if __name__ == "__main__":
    print("\n============================================================")
    print("RUNNING PHASE 9 VIEWPORT AND OCCLUSION BLENDER SUITE")
    print("============================================================\n")
    suite = unittest.TestLoader().loadTestsFromTestCase(Phase9ViewportAndOcclusionTest)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    if not result.wasSuccessful():
        sys.exit(1)
    print("\nALL PHASE 9 BLENDER PYTHON TESTS COMPLETED SUCCESSFULLY!\n")
