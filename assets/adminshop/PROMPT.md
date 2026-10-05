# 怪しいお薬のテクスチャ

Codex内蔵image_genで生成。`mystery-medicine-source.png`が透過原画、
`mystery_medicine.png`がゲーム用の128×128 PNG。縮小だけを行い、絵柄とアルファを保持する。
`scripts/build-phone-packs.py`でJava版の共通パックと統合版のAdminShop専用パックへ入れる。

## 生成プロンプト

Use case: stylized-concept. Asset type: a single original game inventory item texture for the Japanese item 怪しいお薬, a drinkable potion that gives a random good or bad effect. Primary request: create one distinctive whimsical suspicious medicine bottle icon. A short rounded glass bottle with a narrow neck, warm brown cork stopper, vivid purple liquid (main palette RGB 128,48,160) and a cream paper label bearing one big dark purple question mark ?. Bold dark plum outline, simple flat cel shaded illustration, a few large pale glass highlights and just two bubbles inside the liquid. Straight-on front view, centered upright, object occupies about 80 percent of a square canvas. Truly transparent background, clean alpha silhouette, all details attached to or inside the bottle. Must remain readable when rendered at 32px in an inventory, use large simple forms and thick lines. Original bottle design, no imitation of Minecraft's vanilla potion sprite, no pixel art, no voxel art. No scene, no floor, no shadow outside bottle, no extra objects, no text except the single ? on label, no watermark, no frame. Output a square power-of-two PNG suitable as a transparent item sprite.
