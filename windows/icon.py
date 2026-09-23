"""Generate a native Windows launcher icon during the Windows build."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent
sizes = [16, 24, 32, 48, 64, 128, 256]
image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
d = ImageDraw.Draw(image)
for y in range(256):
    t = y / 255
    c = (int(26 + 28*t), int(52 + 44*t), int(113 + 66*t), 255)
    d.line([(0, y), (255, y)], fill=c)
d.rounded_rectangle((10, 10, 245, 245), radius=54, outline=(150, 201, 255, 255), width=5)
d.rounded_rectangle((43, 72, 213, 185), radius=24, fill=(16, 25, 54, 195))
d.polygon([(108, 89), (108, 169), (177, 129)], fill=(139, 224, 255, 255))
d.arc((52, 37, 203, 223), 36, 148, fill=(164, 208, 255, 255), width=6)
image.save(root / "icon.ico", sizes=[(s, s) for s in sizes])
print("Created", root / "icon.ico")
