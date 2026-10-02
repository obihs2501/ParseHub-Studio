from pathlib import Path

from PIL import Image, ImageDraw

output = Path(__file__).resolve().parents[1] / "src-tauri/icons"
output.mkdir(parents=True, exist_ok=True)
image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.rounded_rectangle((8, 8, 248, 248), radius=64, fill="#101c2d")
draw.rounded_rectangle((65, 49, 191, 160), radius=40, fill="#63d5c3")
draw.rectangle((65, 98, 98, 211), fill="#63d5c3")
draw.rounded_rectangle((98, 80, 158, 128), radius=18, fill="#101c2d")
image.save(output / "icon.png")
image.save(output / "icon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
