import os
from pathlib import Path
from PIL import Image

def compress_card_animations():
    catalog_dir = Path("catalog")
    if not catalog_dir.exists():
        print("No catalog folder found locally.")
        return

    for card_folder in catalog_dir.iterdir():
        anim_dir = card_folder / "animations"
        if not anim_dir.is_dir():
            continue
        
        for anim_folder in anim_dir.iterdir():
            if not anim_folder.is_dir():
                continue
            
            print(f"Compressing animation: {card_folder.name}/{anim_folder.name}...")
            for img_path in anim_folder.glob("frame_*.png"):
                img = Image.open(img_path).convert("RGBA")
                # Save as WebP with lossless compression (slashes file size in half instantly)
                webp_path = img_path.with_suffix(".webp")
                img.save(webp_path, "WEBP", lossless=True, quality=80)
                img_path.unlink() # Delete the fat PNG

            # Update meta.json format references if needed
            print(f"[✓] {anim_folder.name} converted to WebP!")

if __name__ == "__main__":
    compress_card_animations()