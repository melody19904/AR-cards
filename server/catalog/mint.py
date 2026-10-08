import sys
import os
import glob
import json
from PIL import Image
from supabase import create_client, Client

# --- 1. SUPABASE CONFIGURATION ---
SUPABASE_URL = "Superbase_url"
SUPABASE_KEY = "service_key"
BUCKET_NAME = "cards"

supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)

# --- 2. CONFIGURATION & SIZES ---
MAX_ANIMATION_SIZE_MB = 3.0  # Max allowed size for the animation folder
TARGET_MAX_DIMENSION = 400   # Resize frames if they are too big to save space

def optimize_and_extract_frames(gif_path, output_dir, anim_name):
    """Extracts frames from a GIF, optimizes them, and handles size limits."""
    print(f"🎬 Processing GIF: {gif_path}")
    
    # Create the target animation folder: ./card_XXX/animations/{anim_name}/
    anim_folder = os.path.join(output_dir, "animations", anim_name)
    os.makedirs(anim_folder, exist_ok=True)
    
    # Open GIF and calculate duration/FPS
    gif = Image.open(gif_path)
    frames = []
    
    try:
        avg_duration_ms = gif.info.get('duration', 100)
        if avg_duration_ms == 0:
            avg_duration_ms = 100
        fps = round(1000.0 / avg_duration_ms)
    except:
        fps = 15 # Fallback safe FPS
        
    print(f"⏱️ Estimated GIF Speed: {fps} FPS")

    frame_index = 0
    total_size_bytes = 0
    
    try:
        while True:
            # Create a clean RGBA frame to handle the transparent background perfectly
            new_frame = Image.new("RGBA", gif.size)
            new_frame.paste(gif)
            
            # Resize if necessary to save memory
            if max(new_frame.width, new_frame.height) > TARGET_MAX_DIMENSION:
                ratio = TARGET_MAX_DIMENSION / max(new_frame.width, new_frame.height)
                new_size = (int(new_frame.width * ratio), int(new_frame.height * ratio))
                new_frame = new_frame.resize(new_size, Image.Resampling.LANCZOS)
                
            # Strict naming: frame_000.png, frame_001.png
            frame_filename = f"frame_{frame_index:03d}.png"
            frame_path = os.path.join(anim_folder, frame_filename)
            
            new_frame.save(frame_path, "PNG", optimize=True)
            
            total_size_bytes += os.path.getsize(frame_path)
            frames.append(frame_filename)
            
            frame_index += 1
            gif.seek(gif.tell() + 1)
    except EOFError:
        pass # Reached end of GIF
        
    total_mb = total_size_bytes / (1024 * 1024)
    print(f"📦 Extracted {len(frames)} frames. Total Size: {total_mb:.2f} MB")
    
    # --- 3. SIZE VALIDATION & COMPRESSION ---
    if total_mb > MAX_ANIMATION_SIZE_MB:
        print(f"⚠️ WARNING: Animation size ({total_mb:.2f}MB) exceeds {MAX_ANIMATION_SIZE_MB}MB limit!")
        print("🔧 Attempting aggressive frame dropping to reduce size...")
        
        # Drop every other frame and halve the FPS to keep animation timing correct
        frames_to_keep = frames[::2]
        frames_to_delete = [f for f in frames if f not in frames_to_keep]
        
        for f in frames_to_delete:
            os.remove(os.path.join(anim_folder, f))
            
        frames = frames_to_keep
        fps = max(1, fps // 2)
        print(f"✅ Dropped frames to save space. New FPS: {fps}, Total Frames: {len(frames)}")

    return frames, fps, anim_folder


def process_card_folder(card_id, raw_gif_name):
    """Validates the folder, processes assets, creates JSONs, and uploads."""
    
    # Strip .gif if the user accidentally types it in PowerShell
    anim_name = raw_gif_name.replace(".gif", "").strip()
    
    # Because we are running from inside /catalog, the folder is just ./card_id
    card_folder = os.path.join(".", card_id)
    
    if not os.path.exists(card_folder):
        print(f"❌ Error: Folder '{card_folder}' not found in the current directory.")
        return
        
    print(f"\n🌟 MINTING INITIATED: {card_id} | Animation: {anim_name}")
    
    # --- 4. LOCATE REQUIRED ASSETS ---
    render_path = os.path.join(card_folder, "render.png")
    card_png_path = os.path.join(card_folder, "card.png")
    gif_path = os.path.join(card_folder, f"{anim_name}.gif")
    
    if not os.path.exists(render_path) or not os.path.exists(card_png_path):
        print(f"❌ Error: Missing 'render.png' or 'card.png' in {card_folder}.")
        return
        
    if not os.path.exists(gif_path):
        print(f"❌ Error: Could not find '{anim_name}.gif' in {card_folder}.")
        return
        
    # --- 5. PROCESS GIF TO FRAMES ---
    frames_list, fps, anim_folder = optimize_and_extract_frames(gif_path, card_folder, anim_name)
    
    # --- 6. GENERATE JSON MANIFESTS ---
    anim_manifest = {
        "anim_name": anim_name,
        "fps": fps,
        "frames": frames_list
    }
    anim_json_path = os.path.join(anim_folder, "anim.json")
    with open(anim_json_path, "w") as f:
        json.dump(anim_manifest, f, indent=4)
        
    card_manifest = {
        "id": card_id,
        "animations": [anim_name]
    }
    card_json_path = os.path.join(card_folder, "card.json")
    with open(card_json_path, "w") as f:
        json.dump(card_manifest, f, indent=4)
        
    print("📝 JSON Manifests generated.")

    # --- 7. UPLOAD TO SUPABASE ---
    print(f"🚀 Syncing {card_id} to Supabase bucket '{BUCKET_NAME}'...")
    
    base_files = [render_path, card_png_path, card_json_path]
    for file_path in base_files:
        upload_path = f"{card_id}/{os.path.basename(file_path)}"
        _upload_file(file_path, upload_path)
        
    anim_files = glob.glob(os.path.join(anim_folder, "*"))
    for file_path in anim_files:
        filename = os.path.basename(file_path)
        upload_path = f"{card_id}/animations/{anim_name}/{filename}"
        _upload_file(file_path, upload_path)

    print(f"🎉 Minting complete for {card_id}!")

def _upload_file(local_path, storage_path):
    """Helper to upload a single file to Supabase with Upsert."""
    try:
        with open(local_path, 'rb') as f:
            supabase.storage.from_(BUCKET_NAME).upload(
                file=f,
                path=storage_path,
                file_options={"cacheControl": "3600", "upsert": "true"}
            )
        print(f"  -> Uploaded: {storage_path}")
    except Exception as e:
        print(f"  -> ⚠️ Failed to upload {storage_path}: {e}")

if __name__ == "__main__":
    # Ensure PowerShell passed both arguments
    if len(sys.argv) < 3:
        print("❌ Error: Missing arguments.")
        print("Usage: python mint.py <card_id> <gif_name>")
        print("Example: python mint.py card_006 magic")
        sys.exit(1)
        
    target_card_id = sys.argv[1]
    target_gif_name = sys.argv[2]
    
    process_card_folder(target_card_id, target_gif_name)